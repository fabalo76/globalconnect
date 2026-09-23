# Android ECR integration

Android supports the A10 LATAM/UPE framing over TCP/IP, Nexgo USB CDC/base serial, and RS232. Customers use the existing Windows UICUpeAPI HTTP service.

## Configuration

More → Configuration → App Config → ECR. Enable ECR, choose TCP/IP, USB or RS232, and return to Sale.

- TCP/IP: terminal listens on the configured port (default 9100). The idle screen displays its IP address and port.
- USB/RS232: choose the Nexgo device serial-port number and baud rate. Framing is 8 data bits, no parity, one stop bit. The device port number is not the Windows COM number. CT20/CT20P USB CDC is fixed to port 0 (no editable port selector); saved alternative USB ports are normalized to 0. N6ProLite USB base uses port 0; configure the physical RS232 port for the model/base in use. USB CDC is enabled only when USB is selected; this may change the USB debugging connection. Windows continues using its COM transport.
- Reconnection does not restart a transaction. Serial and TCP share the same framing, ACK/retry logic and persistent request journal. A serial-port open failure appears on the idle screen and is retried.
- Only one ECR operation owns the terminal at a time. New requests require the application to be foreground and idle. A duplicate request retrieves its original result.

ECR kiosk mode hides navigation. Ten Clear presses (keypad devices) or ten taps (touch-only devices) open the password prompt. After unlocking, one minute without activity returns to ECR.

## Supported commands

| Wire | Windows operation | Behavior |
| --- | --- | --- |
| D1 | Automatic device initialization | Model, serial number, printer capability |
| D0 | PrintReport: CommunicationsTest | Local communications check; no host request |
| 20 | Transaction: Sale | Normal sale |
| 26 | Transaction: Refund | Card-present refund using the existing Android refund flow |
| 38 | Transaction: Payment | Payment, subject to terminal/acquirer enablement |
| E7 | Transaction: Cash | Cash advance, subject to terminal/acquirer enablement |
| E6 | Transaction: Balance | Non-financial balance inquiry |
| 10 | Transaction: Authorization | Authorization using the existing host transaction configuration |
| CI | Transaction: CheckIn | Hotel check-in; Folio required |
| CO | Transaction: CheckOut | Folio required; matches exactly one saved check-in for the same acquirer and card. Optional OriginalInvoice disambiguates. A successful checkout removes that check-in. |
| 31 / 33 | Transaction: LoyaltySale / LoyaltyBalance | Existing loyalty contracts and permissions |
| 32 / 35 | Transaction: QuotaSale / ExtraSale | Banpais plan query, plan/installment selection, then financial sale |
| 34 / 36 | Transaction: QuotaBalance / ExtraBalance | Banpais installment-balance inquiry; both use the A10 installment-balance contract |
| 42 | VoidTransaction | Ten-second device confirmation; Cancel/expiry returns UC; normal processing/result flow |
| P1 | ReprintTransaction | Current-batch invoice, or latest transaction if invoice omitted |
| P2 / P3 | PrintReport: Audit / Totals | Current-batch reports |
| P4 | PrintReport: LastSettlement | Saved last settlement per acquirer, independent of the current batch |
| P5 | PrintReport: TotalsByAcquirer | Current totals grouped by acquirer; optional AcquirerId selects one |
| 61 | Settlement | All configured acquirers, without confirmation |
| F2 | PrintReport: HostEcho | Existing host echo client; all acquirers or optional AcquirerId |
| 00 | PrintReport: SoftReset | Relaunches the payment application after the response ACK, while idle; preserves settings, transactions and the request journal. Does not reboot Android. |

The Windows API accepts AcquirerId on LastSettlement, TotalsByAcquirer, and HostEcho. Wire field AI is the configured acquirer ID. Maintenance operations use PrintReport as the existing asynchronous service endpoint; PrintReceipt has no effect for those operations.

## Transaction fields

Financial requests use RequestID, POSTransactionID, TransactionAmount, optional Tax1Amount/Tax2Amount/TipAmount, CurrencyCode and PrintReceipt. Amounts have at most two decimal places. TransactionAmount is the base amount; taxes, tip and cashback are added for authorization. Aggregate amounts must fit twelve minor-unit digits.

- Wire RQ = RequestID; 80 = POSTransactionID; 40 = base amount; 41 = tip; 42 = cashback; 44/45 = taxes; 49 = numeric ISO currency; P1 = print flag.
- Cashback is supported on Sale. It reaches EMV amount-other and the host, is saved in the transaction, and is retained for receipt reprinting, voids and batch upload. The response exposes CashBackAmount. Partial approvals with cashback are declined and reversed rather than allocating cash against an incomplete approval.
- Balance requests (E6/33/34/36) use zero amounts, taxes, tip and cashback. They do not create active-batch financial records or reversals. Regular/extra balances use host DE4 when present and host receipt text as fallback. Loyalty points use field 48.
- CheckIn/CheckOut carry Folio in field 66; optional checkout OriginalInvoice uses 65. CheckOut does not silently select a different folio/card when no unique match exists.
- The existing Windows Refund request requires OriginalInvoice and OriginalTransactionDate (65/69). Android uses its normal card-present refund flow; it does not void the original sale.
- Quotas is 1–99 for a requested installment count, or 0 for terminal selection (wire 47). The bank must offer the requested count. A single plan auto-selects; multiple plans prompt. Financial installments require a fresh read of the same card after the query. The plan contract remains flavor-specific; unsupported flavors return 58.

## Reports, settlement and printing

Report field 80 carries RequestID. P1=0 returns data without printing. Reports use multiple ACKed frames with S1 sequence numbers and the more indicator. Windows ignores retransmitted S1 frames.

TT/AT return terminal/acquirer totals. TD returns masked transaction audit data with the original POS transaction ID. AQ/TQ return installment breakdowns by acquirer/terminal currency, grouped by quota or extras and installment count. Voided rows are excluded from these net installment breakdowns. Legacy rows with no recoverable count use 00. Stored settlement batch numbers are retained on P4.

Settlement snapshots report data before successful batches are cleared. SD rows carry `acquirer|code|message^`. All-success returns 00; partial failure returns 96 with each acquirer outcome, and failed batches remain available. The service allows ten minutes for multi-acquirer settlement and printing.

Settlement emits a 200 ms start beep and displays the summary for three seconds on success, five seconds on failure. The terminal stays busy until the summary closes. Cached requests do not repeat settlement, printing, the beep or the summary.

P2 in a response indicates successful printing. Printing failure does not undo an approved transaction or completed settlement. P4 reprints persisted snapshots and never closes a batch. Use a new RequestID for an intentional additional copy.

## Recovery and diagnostics

STX + length-prefixed payload + ETX + LRC; the checksum includes ETX and excludes STX. The payload contains version, indicator, command, response, more flag, and tagged fields separated by FS. Binary and ASCII version/indicator headers are accepted.

Requests are saved before execution and responses before delivery. Same RequestID and identical fields replay the saved result. Changed fields with an existing ID return 94. Legacy requests without RQ use field 80; report field 80 already carries RequestID. Disconnects do not cancel a transaction. An interrupted pending operation returns TO (unknown outcome); reconcile the terminal before submitting a new request. UC is cancellation and BZ is busy/not ready.

BuildConfig.ENABLE_ECR_DEBUG_LOGS controls Logcat tag ECR (`-PenableEcrDebugLogs=true/false`). Logs identify command, request, transport, ACK/retry and cached replies without dumping card payloads. The legacy protocol has no built-in authentication or encryption; use the deployment's trusted POS network.

## Verification limits

Both Android flavor unit suites and Banpais builds are exercised for protocol changes. Device installation is in-place and preserves application data. Do not run connected instrumentation tests on a payment terminal. Host transaction qualification, serial cables/port selection per device, and physical paper inspection require hardware validation. No automated test should execute a real payment, void or settlement merely to verify command routing.

### CT20 USB CDC validation

Confirmed on the CT20 that SDK port 0 reaches Windows COM8 with `Port:0` probes. The installed Banpais build passed a read-only D0 exchange over COM8 at 9600/8N1/no flow control, including LRC validation, ACK, and COM close/reopen. Windows COM numbers vary by PC.

Nexgo SDK v3.08.002 limits individual serial send/receive calls to 2048 bytes. Receive buffers respect this limit and large output frames are split without changing ECR framing. A disconnected receive closes the native port in this SDK, so the runtime reopens it. Do not clear RX on reconnect: the host may already have sent a request. Hosts should retain the existing identical-frame ACK retry policy for connection startup races.
