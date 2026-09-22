# QK card detection

Request: `<STX>QK[mask[<FS>transaction name[<FS>formatted amount]]]<ETX><LRC>`

FS is byte 0x1C. The mask contains exactly three binary digits in MSR, CHIP, CONTACTLESS order. `111` enables all readers; `001` enables contactless; `011` enables chip and contactless; `100` enables swipe. An omitted/empty mask means `111`. `000`, invalid masks and extra fields are rejected with QK result `00` without starting a search.

Bare QK remains supported. Empty positional fields are preserved, so `QK<FS><FS>US$10.00` uses all readers and specifies only an amount. Display text is UTF-8, with a maximum of 64 characters for the name and 48 for the formatted amount. Control characters are not allowed inside text. The name appears above the amount. These are display-only strings; they do not set EMV amounts, currency or transaction type. Without either display field, existing ZA display context remains in use.

Example payload: `011<FS>Venta<FS>US$10.00`.

Timeout remains 60 seconds. Responses are unchanged: `1` MSR, `2` ICC, `3` contactless, `00` unable to enable detection/invalid request, `03` canceled or timed out. The host acknowledges the result with ACK. Cancel detection with the existing reset-to-idle flow (Z1).
