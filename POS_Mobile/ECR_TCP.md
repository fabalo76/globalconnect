# ECR over TCP/IP — initial Sale implementation

Android listens for the existing A10 LATAM ECR protocol. The Windows UICUpeAPI service connects to it; customers continue using the service's HTTP API.

## Terminal setup

Open More → Configuration → App Config. Enable ECR, select TCP/IP, choose a listening port (default 9100), and save. Return to Sale. The main screen now waits for ECR requests. The app must be running in the foreground and idle to accept a new Sale.

USB and RS232 are listed for the next transport phase; enabling either is blocked in this version. They do not silently fall back to TCP. USB remains available for Android debugging.

Enable ECR kiosk mode to hide app navigation. Ten consecutive Clear presses on a physical-keypad model, or ten taps on the ECR idle screen on a touch-only model, opens the existing bank/configuration password screen. Another key resets the sequence; gaps over five seconds reset it. Successful authentication opens More. After 60 seconds without touch/key activity the app returns to ECR. System navigation restrictions use the installed xTMSAgent kiosk service and its existing device permissions.

## Protocol and recovery

- Frame: STX + payload + ETX + LRC. LRC XOR includes payload and ETX, excluding STX.
- Payload: four ASCII length digits (remaining payload length), version, request/response indicator, two-character command, two-character response code, more flag, FS, then fields.
- Field: two-character identifier, three ASCII length digits, data, FS (0x1C).
- Binary and ASCII version/indicator headers are accepted. Replies use the Windows service's binary header representation.
- `20` Sale: `80` unique POS transaction ID (1–64 printable ASCII characters), `40` positive base amount in 12-digit minor units, optional `44`/`45` taxes, `49` three-digit currency, `P1` receipt flag 0/1.
- `D1`: device information. `P3`: current-batch totals; `P2`: current-batch audit; `P1`: transaction reprint. Settlement and last-settlement reprint remain unsupported (`30`).
- Replies include POS ID, outcome, authorization, masked PAN, amounts, currency, entry mode, invoice, STAN, RRN and available EMV/receipt information. No PIN or track data is returned.
- `UC`: terminal cancellation; `BZ`: busy/not ready; `94`: POS ID reused with different fields; `TO`: unresolved previous request requiring reconciliation.

Requests are durably recorded before opening the payment flow. The completed response is saved before delivery. Resending the same POS ID and fields returns that response; a disconnect does not cancel or restart a payment. Only one Sale can own the terminal at a time. A process interrupted with a pending journal entry returns an unknown outcome instead of risking a second charge. Reconcile it on the terminal; do not generate a new POS ID as an automatic retry. Journal records survive restarts and in-place updates, but not clearing app data or uninstalling.

The transport validates lengths/checksums, handles fragmented reads and ACK/NAK retries, limits concurrent connections, and closes partial-frame timeouts. Settings cannot change during an active ECR Sale. Use a trusted network restricted to the ECR service: the legacy wire protocol has no authentication or encryption.

The existing payment flow performs card processing and host authorization. `P1=1` requests one merchant receipt; `P2` reports its printing result. Printing failure does not turn an approved payment into a decline. Partial approvals return `10`; the ECR client must explicitly handle the remaining amount.

ECR approvals play the full branding/sensory animation, then show a five-second result without receipt or new-transaction buttons before returning to ECR idle. Chip-card removal must complete before dismissal. Errors also return after five seconds without a manual Retry button. Field `82` carries the captured cardholder name when available; a blank name in the card data remains blank in the response.

## Validation

Android protocol unit tests and Globalconnect debug build pass. Windows loopback checks cover fragmented responses, bad checksum rejection, ACK/response separation, and close.

The Android unit-test suite passed 278 tests, including eight ECR protocol tests. Full lint remains blocked by existing permission, receiver, translation, formatting, and Compose issues; the new ECR composition warning identified in that run was corrected.

Connected CT20: HTTP Sale opened the reader; cancellation returned to Windows; replay did not reopen it. A user-authorized US$1.00 contactless Sale was approved (`00`); replay returned identical authorization, STAN and invoice. Ten Clear presses opened the password prompt, successful authentication opened More, and inactivity returned to ECR after one minute.

The approved test used POS ID `ECR-CT20-CARD-001`, with receipt printing disabled. Its replay was also verified after app/service restart. Reuse with a different amount returned `94`. No automatic void or settlement was performed.

Touch-only gesture hardware validation, full serial hardware regression, Android USB/RS232, settlement, and production-host qualification remain separate work.

## Reports and reprint

P3/P2 accept request ID in field 80 and printing flag in P1. Report data is returned even when P1=0. Totals reuse calcTotals; audit returns the current batch with masked PANs. Large responses use multiple ACKed frames, more-indicator and S1 sequence numbers. The Windows parser ignores duplicate S1 frames. Existing legacy totals limits (three-digit counts and twelve-character signed minor-unit amounts) are validated before printing; oversized data fails explicitly.

P1 accepts optional invoice field 65; omitted selects the most recent current-batch transaction. Missing/ambiguous invoices fail without choosing another receipt. Printing uses the standard customer-copy receipt path and saved signature. A printing callback confirms P2=1; failure/timeout returns P2=0. Each operation is durably journaled by command/request ID. Retries return the same snapshot without printing again; request a new copy with a new ID. No reports or reprints change financial records or settle the batch.

Direct TCP checks on CT20 verified totals, seven audit rows, last-receipt data, identical replay and missing-invoice rejection without starting a Windows service or making a payment.

The printer subsequently reported success (P2=1) for one totals report, one audit report, and one transaction reprint. Replaying each returned the stored outcome without another print invocation. The Android suite now passes 285 tests, including seven report-format tests; the Windows x86 build passes. Physical paper layout and the HTTP calls from Visual Studio remain available for user review.


## Loyalty over ECR

Use the existing `POST /Transaction` endpoint with `TransactionType: "LoyaltySale"`
(command 31) or `TransactionType: "LoyaltyBalance"` (command 33).

Example loyalty sale body:
```json
{"POSTransactionID":"LOY-SALE-001","TransactionType":"LoyaltySale","TransactionAmount":12.25,"Tax1Amount":0,"Tax2Amount":0,"CurrencyCode":"840","PrintReceipt":false,"RequestID":"LOY-SALE-001","TimeOutSeconds":120}
```

For balance, use `"TransactionType":"LoyaltyBalance"`, a new ID, and
`"TransactionAmount":0`. Both taxes must also be zero. Raw legacy command 33
may omit amount field 40. Sale requires a positive amount.

The terminal and selected acquirer must enable loyalty. Contactless additionally
requires the terminal's contactless loyalty setting; the existing reader and
host processing rules apply.

Balance points are returned in the existing API `MiscAmount` property. On the
wire field 48 uses the A10 scale (points multiplied by 100); the service divides
by 100, so 12345 points becomes `"MiscAmount":12345`, not 123.45. A balance
inquiry is not added to the settlement batch. The ECR transaction type in field
70 is 31 or 33, respectively.

Loyalty sale retains the payment branding animation and brief ECR result.
Balance shows its points result for five seconds and returns to ECR, with no
interactive receipt controls. `PrintReceipt` is handled before the response.
Duplicate requests replay the stored response without executing or printing
again; reuse the same ID and fields after a connection interruption. IDs for
loyalty commands are scoped by command; changing fields for a used ID is rejected.


## Void (Banpais / command 42)

Use `POST /VoidTransaction`, then poll `GET /VoidTransaction` as with the existing
asynchronous service operations. Do not use `POST /Transaction` for void.

```json
{
  "RequestID": "VOID-001",
  "POSTransactionID": "VOID-001",
  "OriginalInvoice": "000010",
  "PrintReceipt": false,
  "TimeOutSeconds": 120
}
```

`OriginalInvoice` is required. The terminal selects that exact invoice from the
current active batch (leading zeros are ignored), rejecting missing, ambiguous,
already returned, unapproved, or unsupported records. There is no automatic
fallback to the latest transaction. The original issuer must permit void.
The full stored amount is voided; no card read or replacement amount is needed.
Supported original types are sale/manual sale, refund/manual refund, loyalty
sale, quota sale, extras sale, cash and payment. Authorization/check-in reversal
and partial void are outside this command.

On host approval, the existing record is marked voided and reported to TMS;
no second sale record is created. Response field 70 is 42, field 71 identifies
the original transaction type, and P2 reports receipt printing success. The
terminal shows the result briefly and returns to ECR ready.

Retries must use the same RequestID and identical fields: the terminal
replays the saved response without another host call or receipt. If the host
outcome is uncertain, another void of that record is blocked even with a new ID;
reconcile it before further action. Stopping the Windows wait does not cancel
an in-flight host void. An approved void remains approved if receipt printing fails.

ECR void now displays the normal void confirmation with transaction details and
an explicit Cancel button. No host request is sent until Start Void is pressed.
Cancel returns UC; no decision within 10 seconds also returns UC without contacting
the host. During host processing, cancellation/navigation is disabled. Processing
progress and the final result are displayed; Done or five seconds returns to ECR.
Duplicate pending requests share the same confirmation and never open another.
Cached legacy confirmation expiries explicitly marked "nothing sent to host"
are returned as UC. Other TO responses retain their original meaning. Replaying
a completed request does not open a new confirmation or resubmit a host void.

### ECR Logcat diagnostics

Build with `-PenableEcrDebugLogs=true` to enable `BuildConfig.ENABLE_ECR_DEBUG_LOGS`
(default: false). Filter Logcat by tag `ECR`. This logs listener and connection
events, request IDs and commands, readiness, cached responses, confirmation,
response codes, ACK/NAK, retries, and exception types. It does not dump message
payloads, cardholder data, or exception messages. Disable with
`-PenableEcrDebugLogs=false` and rebuild.


### Request identity versus transaction reference

Sale, loyalty, and void requests now send API `RequestID` in optional wire field
`RQ` (1–64 printable ASCII characters). Field `80` remains `POSTransactionID`
and continues to populate transaction records, receipts, and audit reports.
Android echoes `RQ` in responses and keys duplicate detection on it, in a separate
journal namespace. Same RequestID plus identical payload replays the saved result;
same RequestID with changed payload returns 94. A new RequestID is a new operation,
even when POSTransactionID is unchanged. Reuse the same RequestID when retrying
an operation with an unknown outcome. Legacy requests without RQ keep their
existing field-80 duplicate behavior; reports already use RequestID in field 80.
The service checks the echoed RQ when present and retains field-80 matching for
legacy terminals. Update both Android and the Windows service together.
