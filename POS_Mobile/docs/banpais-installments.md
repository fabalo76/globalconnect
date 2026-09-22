# Flavor-specific installment transactions

The shared UI coordinates card query, plan selection (automatic for one plan), allowed installment selection, amount entry, and a fresh card read for the financial sale. `InstallmentContract` owns the bank wire rules; flavor source sets supply `InstallmentContracts`. Banpais enables this flow for Quota Sale and Extra Sale. Globalconnect returns no contract and retains its existing behavior.

Banpais uses the supplied PayPlan transaction traces and POS ISO8583 Msg Spec (October 2022), pages 80-84:

- Query: MTI 0100, processing code 310000, zero amount, tag 45 contains 22 zeros followed by S (Quota) or C (Extra).
- Tag 46: up to 255 ASCII bytes; repeated records of 4-character plan ID, 25-character padded name, 2-digit count, then that many 2-digit installment values. Parsing consumes the entire response and rejects truncated records, duplicate plan IDs/counts, and zero installment values.
- Final sale: MTI 0200, processing code 000000 (Quota) or 010000 (Extra). Tag 45 contains selected count, plan ID, 16 zeros and S/C respectively. DE37 carries the query reference.
- Page 80's short layout/blank final indicator conflicts with page 81 and the supplied packed transaction traces. The implementation follows the expanded page 81 format and traces, not the old A10 final-sale C for both transaction types.
- The query does not persist a financial transaction or issue an authorization reversal. Selection is bound to the queried card and acquirer; the final transaction runs through the normal authorization flow. Card identity is hashed in memory only.

Tests cover single/multiple plans (including the exact PDF and trace examples), malformed responses, flavor isolation, and packed query/final messages including DE37 and tag 45 across Isswitch/Banpais GL/Banpais IO.

Device validation still requires the Banpais test host: test one/multiple plans, decline/malformed query, cancel/timeout at each selection, unsupported installment count, different card on second read, then an authorized final sale. Use test cards and host credentials; do not run on a live merchant automatically.

## Receipt and recovery data

The existing transaction `paymentPlan` and `paymentPlanQueryResponse` columns retain the selected count/code and the original plan names. Reprints and void copies resolve that saved snapshot, independent of current TMS settings. Receipt and detailed audit/settlement lists print plan name/code and count. Pending reversals already retain the original DE63/45; database migration 11 to 12 additionally preserves the plan catalogue for reversal receipts. Older records without a catalogue display the saved plan code.

The selection UI uses two columns with the flavor's existing menu tile shape and colors, up to six visible rows with scrolling, and a noninteractive filler tile for odd option counts.
