# Global Connect Key Injection

Global Connect Key Injection is a Windows operator application for loading keys into a Nexgo payment terminal running the companion Global Connect Key Injection Android app. It replaces the proprietary legacy device protocol with the Futurex Direct Key Injection (LKI) serial protocol.

## Delivered workflows

- Connect to a Nexgo terminal over a selectable COM port at 9600 baud, 8 data bits, no parity, and 1 stop bit.
- Restore the last successfully used COM port and timeout on the next run. These non-secret preferences are stored under `%LocalAppData%\GlobalConnect\KeyInjection\settings.json` and never contain keys or passwords.
- Keep connection controls isolated on the **Device Connection** tab and destructive key erasure isolated on the **Erase Keys** tab.
- Keep the metadata-only operation history on a dedicated **Audit Log** tab so the key workflows can use the full vertical workspace.
- Futurex STX/ETX packet framing, XOR LRC verification, ACK/NAK exchange, timeout handling, and up to three NAK retries.
- Automatically read and log the terminal serial number immediately before every injection run.
- Optionally clear all terminal keys before an injection run. When selected, command `05` is sent only after command `03` has verified the serial number and before the first command `02`; an erase failure stops the run.
- Inject all supported key types through Futurex command `02`.
- For staged Futurex type `03` or `08` records, retain the double-length TDES BDK inside the dual-password vault, build a terminal-specific counter-zero Initial KSN from the verified device serial, derive the 16-byte Initial Key (IPEK) locally, and transmit only a type `02` Initial Key. The BDK is never sent to the terminal.
- Select the Futurex Key Type—including its documented modifier—and the Key Encryption value directly in the primary form.
- Select clear (`00`), under a preloaded KTK (`01`), or under a supplied clear KTK (`02`).
- Mixed batches send TLK/default-KTK destinations first in required clear mode, then apply the selected KTK mode to operational keys.
- Combine destination-key components, calculate the clear-key KCV, and automatically TDES-encrypt the payload when a KTK-protected mode is selected.
- Erase terminal keys using the companion app's Futurex command `05` extension.
- Combine two or three independently entered key components using XOR and verify the resulting TDES KCV.
- Select double-length (32 hex) or triple-length (48 hex) components, enter them in four-character groups, and see a six-digit KCV beside every complete component plus a separate six-digit combined-key KCV.
- Stage supported Futurex key types and a component-derived KTK in a working key vault, independently from terminal injection.
- Save and reopen `.gckv` protected key files using two independent custodian passwords.

## Security behavior

- Key components and key payloads are masked.
- During component entry, only the active four-character group is visible. A completed group is immediately masked and entry advances to the next group; empty-group Backspace returns to the previous group. Tab advances between groups, and a hex character typed while a completed group is active is forwarded into the next available group.
- Pasting a complete component into a component's first group distributes it across the visible groups. Component KCVs remain hidden until all groups required by the selected key length are complete.
- Destination-key staging is a single step: once the selected-length components are complete, the six-digit calculated KCV appears and **Stage Key in Protected Vault** becomes available beside it. Futurex command `02` still receives the protocol-required first four KCV digits.
- The audit window records operation metadata, response status, Nexgo key index, and KCV only. It never records keys, components, or KTK values.
- Component and payload fields are cleared after protected staging or injection.
- Every injection and erase operation requires explicit confirmation.
- Protected key files use independent PBKDF2-HMAC-SHA-256 derivation for both password parts (600,000 iterations each) and authenticated AES-256-GCM encryption. Both password parts are required; a wrong part or modified file is rejected.
- Password parts must each contain 8–128 characters, must differ, and are confirmed when saving a new file.
- Unprotected key files are not supported.

This workstation application is one part of a controlled key ceremony. Production deployment still requires the organization's physical access controls, dual-control procedures, audit process, approved key sources, and secure workstation hardening.

## Nexgo destination policy

The desktop UI intentionally exposes Nexgo PED destinations rather than the legacy A10 `00`–`0F` slot list:

- TLK is fixed at Nexgo master-key index `0` and is represented as Futurex destination `00`.
- TMK, TIK, TPK, and TAK operational destinations are limited to Nexgo indices `1–10` (Futurex `01`–`0A`).
- Futurex types `01`, `02`, `03`, `04`, `05`, `06`, `08`, and `09` are available because the Android app maps them to Nexgo PED types.
- Futurex types `07`, `0A`, and `0B` are hidden and rejected because the Android app explicitly returns unsupported-type errors for them.
- Clear TPK and TAK loading is supported by Android app v1.14 through a PED-only parent-master bridge. If the selected Nexgo index has no master key, the app generates a random TDES parent in that slot, encrypts the received clear working key with `encryptByMKey`, passes the result and its actual byte length to `writeWKey`, and verifies the destination KCV. The generated parent remains non-exportable inside the PED because Nexgo stores the working key beneath that master index.
- DUKPT destinations require a non-zero 20-character KSN.
- Type `03` and `08` BDK records are restricted to double-length (32-hex-character) TDES BDKs. The derivation implementation uses classic ANSI X9.24 TDES DUKPT; unsupported 24-byte BDK records are rejected during staging and protected-vault validation.
- The KSN entered for a DUKPT record is normalized to its counter-zero Initial KSN before storage. For type `03`/`08`, the KSN is displayed as `FFFF | Key-set ID (6 hex) | Device ID (5 hex) | Counter (5 hex)`. The last five digits of the verified terminal's trailing numeric serial suffix are interpreted as a decimal 19-bit terminal number and encoded into the five-hex-character Device ID field. For example, `N960W900629` uses serial digits `00629`, terminal number `629`, and Device ID `004EA`.
- The Key Input form presents the four KSN portions separately. Prefix `FFFF` and counter `00000` are fixed. For BDK types `03`/`08`, the operator enters the six-character Key-set ID while Device ID remains `00000` and disabled until serial binding. For an already-derived type `02` IPEK, the operator enters its assigned five-character Device ID; its final hex character must be even for a counter-zero Initial KSN.
- The workstation always sets the 21-bit DUKPT transaction counter to zero during Initial Key injection. That counter belongs to transaction processing in the terminal after initialization and is not incremented by the injection tool.
- The five-digit serial mapping supports IDs `00001`–`99999`. The organization must ensure that terminal serial numbers are unique in their final five digits within a BDK/key-set domain; otherwise two terminals would receive the same Initial KSN and IPEK. A serial without a non-zero trailing numeric suffix is rejected.
- The installed Android implementation converts command `02` payloads directly from hexadecimal, so TR-31A text blocks are not offered even though the general Futurex protocol permits them.
- Standalone clear commands `00` and `01` are rejected by the Android app once a TLK is present; the UI calls this out and directs operators to command `02`.

## Build and test

Requirements: Windows and the .NET 8 SDK.

```powershell
dotnet test .\GlobalConnectKeyInjection.sln -c Release
dotnet build .\GlobalConnectKeyInjection.sln -c Release
```

To produce a self-contained 64-bit Windows executable:

```powershell
dotnet publish .\src\GlobalConnect.KeyInjection.App\GlobalConnect.KeyInjection.App.csproj `
  -c Release -r win-x64 --self-contained true `
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true `
  -o .\publish\win-x64
```

## Operator quick start

1. Install and open the Global Connect Key Injection Android APK on the Nexgo terminal.
2. Connect the Nexgo terminal to the Windows workstation using the serial/USB interface exposed as a COM port.
3. Run `GlobalConnectKeyInjection.exe`. On **Device Connection**, select the COM port and choose **Connect**.
4. On **Key Input**, use the key-vault controls to create, open, or save a protected vault. Select the Futurex type and Nexgo destination, enter two or three clear-key components, verify the KCV, and choose **Stage Key in Protected Vault**. For type `03` or `08`, enter the double-length TDES BDK and its base Initial KSN; the app replaces the terminal-ID bits from the verified serial and derives the IPEK at injection time. Stage the KTK independently when KTK-protected injection will be used.
5. When saving a protected file, have each custodian enter and confirm their independent password part.
6. On **Key Injection — Command 02**, check the staged keys to inject and select clear (`00`), under clear KTK (`02`), or under preloaded KTK (`01`). Select the KTK source for protected modes. Enable **Clear terminal keys before injection** only when the authorized procedure requires it.
7. Choose **Inject Selected Keys**. The application reads command `03`, records the target terminal serial in the audit log, and displays that serial in the final confirmation. When pre-injection clearing is enabled, command `05` is sent next and must succeed before any command `02` packet is sent.
8. Verify every terminal-returned KCV and the audit result before disconnecting the terminal.
9. Use **Erase Keys** only for an authorized erase operation; the application requests confirmation before sending command `05`.
10. Review connection, terminal serial-number, injection, and erase events on **Audit Log**. Key values and components are never written there.

The desktop application intentionally does not contain terminal-side vendor SDK code. The companion Android app owns the Nexgo PED API calls; this desktop application is the serial LKI sender.

## Project structure

- `src/GlobalConnect.KeyInjection.Core` — Futurex codec, request builders, response parser, component XOR, KCV calculation, and TDES DUKPT Initial Key derivation.
- `src/GlobalConnect.KeyInjection.App` — WinForms operator interface and serial client.
- `tests/GlobalConnect.KeyInjection.Tests` — protocol-vector, framing, validation, XOR, KCV, and response tests.

## Protocol sources

Implementation is based on the supplied Futurex Direct Key Injection API document and the working Nexgo Futurex LKI application. The command `05` erase operation is the companion Android app's documented extension.
