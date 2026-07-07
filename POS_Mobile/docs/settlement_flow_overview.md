# Settlement Flow Notes

This note summarises the existing Isswitch settlement flow implemented in the legacy C++ reference (`A10TSTD`).

- `TransSettle` initialises the settlement, enforcing password protection when required, selects a single acquirer or iterates all, and recalculates the batch totals before proceeding.【F:A10TSTD/source/tran/Settle.cpp†L49-L88】
- The routine aborts empty batches unless zero-amount settlements are explicitly allowed, ensuring there are transactions to close.【F:A10TSTD/source/tran/Settle.cpp†L90-L111】
- Once totals are confirmed, the process prints optional audit totals, then either calls `TransSettleSub` for the chosen acquirer or loops through all acquirers, printing settlements and clearing each acquirer’s records after success.【F:A10TSTD/source/tran/Settle.cpp†L114-L350】
- `TransSettleSub` sends reversals and offline transactions first, builds the Isswitch `0500/960000` or `0500/920000` settlement packet from acquirer totals, and resubmits with a `95` retry if reconciliation is requested.【F:A10TSTD/source/tran/Settle.cpp†L1087-L1165】
- Successful settlements print the settlement receipt, mark the batch closed, and prevent further transaction processing until logs are cleared.【F:A10TSTD/source/tran/Settle.cpp†L212-L327】【F:A10TSTD/source/tran/Settle.cpp†L1170-L1178】
- When the host returns code `95`, the terminal automatically uploads unsettled items using the `TransUpLoad` routine that walks pending logs and transmits `0320` batch-upload messages until the queue is empty.【F:A10TSTD/source/tran/Settle.cpp†L1150-L1165】【F:A10TSTD/source/tran/Settle.cpp†L1196-L1400】

These steps are the baseline for replicating settlement in the Kotlin application, ensuring message construction and batch handling mirror the proven C++ implementation.
