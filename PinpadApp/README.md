# NEXGO CT20P Traditional Pinpad

This project is the Android 11 pinpad application for the NEXGO CT20P. It emulates the legacy A10-P serial protocol while keeping the Android app logic isolated from the byte-level transport layer.

## Current Scope

Stage 1 is implemented:

- `05` Load Serial Number: accepts a valid frame and echoes the supplied serial value. It does not change the hardware serial number because CT20P devices do not support that operation.
- `06` Get Serial Number: returns the NEXGO SDK device serial number, or `0000000000000000` if unavailable.
- `09` Communication Test: implements the `PROCESSING` echo handshake and final result frame.
- `11` Device Connection Test: returns `ACK` for a valid frame.
- `19` Query Firmware Version: returns a version response using SDK device info where available.
- `1C` Query Hardware Capability: reports `ICC`, `MSR`, and `PCD`.

## Architecture

- `MainActivity` shows only the localized idle message. The default text is `NEXGO WELCOME` in English and `BIENVENIDO NEXGO` in Spanish.
- `PINPADSerialService` owns serial communication and starts automatically from the app and boot receiver.
- `transport/*` abstracts CT20P RS232 and USB CDC access through the NEXGO SDK,
  plus raw TCP/IP over Wi-Fi or Ethernet.
- `protocol/PINPADFrameCodec` e~~~~ncodes and decodes raw PINPAD frames, including LRC validation.
- `protocol/PINPADStreamParser` accepts arbitrary byte chunks, discards garbage before a valid start byte, emits `ACK`/`NAK`/`EOT` controls, and drops incomplete partial frames after one second.
- `protocol/PINPADSessionController` converts valid frames into the Stage 1 command responses.
- `model/PinpadCommandModels` defines the internal JSON-style request/response shape used between the protocol/service layer and the pinpad command layer.

## Startup through xTMSAgent

Pinpad exposes `android.intent.action.PAY_APP` with the `DEFAULT` category on
its main activity, in addition to the normal launcher icon. xTMSAgent's boot
launcher queries this entry after its HOME activity resumes, so it can start
Pinpad after a local installation without relying solely on Pinpad's own boot
receiver. xTMSAgent must be the terminal's HOME launcher. Its existing selection
policy prefers its matching payment-app flavor, or the sole PAY_APP package;
installing multiple payment applications can therefore select another app.

The temporary release log records `boot receiver received BOOT_COMPLETED`,
`ACTIVITY created entry=PAY_APP`, and `ACTIVITY resumed`. A boot receiver launch
request alone does not prove that Android displayed the activity. Validate on
the terminal by installing the release APK through xTMSAgent and rebooting;
check the idle screen and a communication command, then review the daily log.

## SDK Libraries

The Nexgo SmartPOS SDK AAR is stored locally in `app/libs` and loaded by Gradle with `implementation fileTree(dir: 'libs', include: ['*.aar'])`.

## Transport Defaults

The debug build defaults to `RS232` so USB remains available for ADB. The release
build defaults to `SERIAL`, which uses USB CDC. An explicitly saved terminal
setting takes precedence over the build default. The setup screen can change:

- Port mode: `SERIAL` (USB CDC), `RS232`, or `IP`
- RS232 port number
- TCP listening port (default `9100`)
- Baud rate
- Data bits
- Stop bits
- Parity

USB CDC currently keeps the default VID/PID values `0x6352` and `0x294A`.

In `IP` mode the existing byte-for-byte PINPAD framing runs over one raw TCP
connection. Listeners are created only on active Wi-Fi or Ethernet IPv4
addresses; cellular is not accepted. There is no TLS in
this first LAN-only implementation. The terminal answers
`GLOBALCONNECT_PINPAD_DISCOVER_V1` UDP broadcasts on port `39100` with its
serial number, model, IPv4 address, and configured TCP port.

The normal customer screen does not show the development RX/TX diagnostics
panel. If the selected communication transport fails, a concise error-only
banner still tells the operator to restart the terminal or contact support.

The `ENTER + 1` administration menu includes **Cloud Update**, which asks
xTMSAgent to request and reapply the `PINPAD_APP` configuration from TMS.
The same menu opens from the idle screen after either a five-second hold or ten
rapid taps, supporting touchscreen-only models such as N6/N6S.

## Host-Controlled Serial Speed

Administration command `13` changes the persisted serial baud rate and line mode after its response flow completes. The PINPAD sends the command response and final EOT using the current settings, then reopens the transport with the new settings.

## New PIN capture commands

Transaction command `7G` implements the A10-P MK/SK PIN-change capture. It uses the same request payload as command `70`, prompts for `ENTER NEW PIN` and `CONFIRM NEW PIN`, and returns the confirmed first encrypted capture in response command `71`.

Transaction command `7H` is the Global Connect DUKPT equivalent. Its request uses the DUKPT account payload accepted by the existing PIN-entry commands. Both entries are encrypted with the same reserved KSN so their encrypted PIN blocks can be compared inside PinpadApp. The KSN is advanced exactly once after each confirmation attempt, including cancellation or mismatch after the first capture. A match returns command `71` with `0 + KSN + encrypted PIN block`; a mismatch is displayed locally and starts a fresh two-entry attempt with the next KSN. No clear PIN or PIN block is logged.

## Offline PIN change transaction

Transaction command `T37 + SUB + 1` starts the change-PIN operation. It uses only the contact ICC interface, does not display or process a financial amount, and sends a zero amount through the EMV kernel. For the selected contact AID, terminal capabilities are restricted to the configured plaintext and enciphered offline-PIN CVMs so the card must verify its current offline PIN.

After a successful current-PIN verification and ARQC, the PINPAD sends `T38` result `0A1`. The host can then use `7G` or `7H` to collect and confirm the new encrypted PIN, retrieve the EMV authorization data with `T27`, and submit processing code `920000` to the issuer. Issuer authentication and the PIN-change script are supplied through `T19`/`T17`. The operation ends with `T38 0V0` only when issuer response `85` produces the expected final AAC, TSI confirms issuer-script processing, and TVR reports no issuer-script failure. Other completed verification or script outcomes return `T38 0V1`.

`T37 + SUB + 3` performs a contact-only offline-PIN verification and returns `T38 0V0` or `0V1` without exposing an online transaction. `T37 + SUB + 2` starts a forced-online, zero-amount unblock flow without requesting or verifying the blocked current PIN and returns `T38 0A1`. The host captures and confirms the new PIN with `7G` or `7H`, requests the new-PIN issuer script, sends it through `T19`, and finishes with `T17`. Cancellation returns T38 status `1`, reason `3` when the active operation is cancelled through the device UI. A host may also terminate and reset the operation with `T1C`, `72`, and `Z1`.

The request payload is `[baud code][optional mode]`:

| Baud code | Speed |
|---|---:|
| `1` | 1200 |
| `2` | 2400 |
| `3` | 4800 |
| `4` | 9600 |
| `5` | 19200 |
| `6` | 38400 |
| `7` | 57600 |
| `8` | 115200 |

Supported line modes are `1` for 8-N-1, `2` for 7-E-1, and `3` for 7-O-1. Omitting the mode selects 8-N-1. Flow-control modes are rejected because the NEXGO transports do not expose a corresponding configuration.

For accelerated media transfer:

1. Send command `13` with payload `81` at the current speed.
2. Receive status `0`, acknowledge the response, and wait for the final EOT.
3. Change the host port to 115200/8-N-1.
4. Transfer media with `M12` or `M13`.
5. To restore 9600/8-N-1, send command `13` with payload `41` and repeat the same handshake.

## Media Management Protocol

### Temporary production debug logs

Release 1.3.128 includes temporary file diagnostics at
`/sdcard/Logs/Pinpad/log_today.txt`. On the first event of a new day, the previous day becomes
`log_YYYY-MM-DD.txt`. Today plus the newest nine archives are retained (ten log
files total). Each file is capped at 4 MiB; when today's file fills, its recent
tail is retained with a truncation marker. Raw payment/key/PIN/track payloads,
recorded PCM/Base64, and unrestricted SDK logcat output are not written.

Use the administration menu → connection/serial settings → **Release debug log**
→ **Allow storage access**. On Android 11+, allow the app's **All files access**
and return to Pinpad App. Older Android versions use the storage permission
prompt. The status dialog reports the public path or a storage error. No ADB is
required. If firmware policy prevents this grant, logs remain privately under
`files/release-logs` until permission becomes available.

Events include app version/model/firmware, connection settings, RX/TX command
identifiers and byte lengths, parser validation, ACK/NAK/EOT and retry timeouts,
transport errors, audio command statuses/response lengths, and capture start/end
and errors. Writes use a bounded background queue and publish approximately once
per second; overload drops are counted. Abrupt process loss can omit queued events.
Internal and public copies are both bounded to ten logs. Unrelated files in the
public folder are preserved.

Disable this temporary logger in a future build with
`-PtemporaryProductionLogEnabled=false`; regular raw release tracing remains off.

### Audio recording commands (N6 Pro only)

Release 1.3.126 adds background microphone capture, independent of the media table.
From release 1.3.127, the exact identifiers `N6Pro` and `N6ProLite` are enabled
(case and punctuation are ignored). The tested N6 Pro firmware identifies itself
as `N6ProLite`, as confirmed by xTMSAgent diagnostics. N6, N96, CT20P, and all other models return status `U` for
every recording command, without opening the microphone or touching storage.

Grant microphone permission when Pinpad App opens on the N6 Pro. Start capture
while Pinpad App is visible; Android may reject microphone foreground-service
activation from a background app. Once started, capture continues in the serial
foreground service while the host sends other commands or the activity changes.
The service notification indicates that audio is recording. No ADB is needed.

All commands use transaction framing and the usual ACK/response/ACK exchange.
The PINPAD acknowledges before potentially slow microphone/storage operations.
`FS` below is byte `0x1C`; all numeric fields are ASCII decimal without padding.

| Command | Request payload | Successful response payload |
|---|---|---|
| `M20` Start | Empty: standard microphone (no selection) | `0 + FS + newFileName` |
| `M21` Stop | Empty | `0 + FS + savedFileName` |
| `M22` List | Empty | `0`, followed by FS-separated `state\|sizeBytes\|durationMs\|fileName` records |
| `M23` Get | `fileName + FS + byteOffset`, starting at zero | `0 + FS + echoedOffset + FS + totalBytes + FS + Base64(chunk)` |
| `M24` Delete one | Exact filename returned by Start/List | `0` |
| `M25` Reset/Delete all | Empty | `0` |

From release 1.3.128, each M23 chunk independently encodes at most 1,024 raw bytes
(1,368 Base64 characters), keeping the entire response below the NEXGO serial
SDK's 2,048-byte write limit as well as the demo's 4 KiB frame limit. The previous
2,048-raw-byte packets exceeded the SDK limit after encoding, causing all sends
to fail and the retry handler to emit EOT after 15 seconds. Decode each chunk separately,
advance the offset by the decoded byte count, and stop when it equals totalBytes.
Requests for the same filename/offset are repeatable; no temporary Base64 copy or
transfer cursor is stored on the terminal. Files are immutable after capture.
If another command deletes/rotates the requested file, subsequent reads return
not found. Download only stopped recordings. Prefer TCP or 115,200 baud for large
recordings. New AAC recordings are approximately 5.4 MB plus ADTS headers per 30 minutes; old WAV files remain downloadable.

List states: `R` recording, `S` stopped, `F` the current capture ended with an
error. Size and duration of an active recording are snapshots. Start returns only
after the microphone has started; it does not wait for recording to finish.
Starting while already recording returns Busy plus the current filename.
Stop returns only after the worker has finalized the audio, or Busy if it is still
finishing. A second Stop after an acknowledged Stop returns No active recording.
After automatic timeout, Stop can acknowledge the last completion until the next
Start or reset.

From 1.3.130, capture uses AAC-LC at 24 kbps, 16 kHz mono, in ADTS `.aac` files,
with the standard microphone. M20 requires an empty payload; legacy source
parameters `0` and `1` now return Invalid. Recording duration is capped at 30
minutes; complete encoded frames beyond the limit are discarded at finalization.
A 10 MiB safety cap bounds storage if the encoder exceeds its requested bitrate. Capture also stops on `M21`, pinpad
reset `Z1`/`72`, service shutdown, or `M25`. Pinpad reset preserves saved audio;
`M25` stops capture and deletes all recordings. Deleting/getting the active file
returns Busy. At most ten recordings are kept in private `files/audio-recordings`;
starting an eleventh deletes the oldest first. Filenames contain a timestamp and
UUID. The directory is separate from M10–M17 media; media initialization cannot
delete recordings. Startup preserves legacy WAV files and repairs their headers. For AAC, it
retains complete ADTS frames and discards an incomplete tail after process death.
AAC duration comes from frame sample counts, not compressed byte size.
A new capture requires the 10 MiB safety allowance plus 10 MiB reserve after
rotation. Unexpected storage exhaustion returns an error and finalizes available
audio where possible.

| Status | Meaning |
|---|---|
| `0` | Success |
| `1` | Invalid payload, source, filename, or offset |
| `2` | Microphone permission/security restriction |
| `3` | Busy: active recording, file in use, or capture still stopping |
| `4` | Recording not found |
| `5` | No active recording |
| `6` | Capture or storage failure |
| `U` | Unsupported model; protocol response includes `FS + reportedModel` |

### Existing media commands

Media management uses three-character transaction command IDs because all commands beginning with `M` are parsed as three-character IDs. `M03` and `M04` remain reserved for permanent unit serial-number management.

| Command | Direction | Operation |
|---|---|---|
| `M10` | Host → PINPAD | Initialize the media table and delete all stored media |
| `M11` | Host → PINPAD | List stored MP3/MP4 files |
| `M12` | Host → PINPAD | Download an MP3/MP4 file to the PINPAD in Base64 packets |
| `M13` | Host → PINPAD | Upload an MP3/MP4 file from the PINPAD to the host |
| `M14` | Host → PINPAD | Play one stored MP3/MP4 file |
| `M15` | Host → PINPAD | Set media playback volume from `00` to `99` |
| `M16` | Host → PINPAD | Delete one or more stored MP3/MP4 files |
| `M17` | Host → PINPAD | Speak UTF-8 text in English or Spanish |

`M11` returns status `0`, followed by zero or more FS-separated records. Each record contains a one-character type (`3` for MP3, `4` for MP4), a ten-digit byte length, and the file name.

`M12` uses the payload `packetType + sequence + force + FS + fileName + FS + dataLength + Base64Data`. Packet type is `0` for continuation and `1` for the final packet; sequence is six decimal digits. New host downloads use a four-digit data length and up to 8,192 Base64 characters per packet. Legacy three-digit packet lengths remain accepted. The PINPAD decodes Base64 incrementally as packets arrive so final-packet acknowledgement does not wait for whole-file decoding. Continuation packets may leave `fileName` empty. A successful final packet returns `F`.

The four-digit field has a theoretical ceiling of 9,999 characters. Use 8,192 as the production maximum at 115,200 bps; use smaller packets on slow physical RS232 links.

`M13` control `0 + FS + fileName` starts an upload and control `1` requests the next packet. Each response is `packetType + sequence + dataLength + Base64Data`, using the same six-digit sequence and three-digit data length.

`M12` status values are:

- `0`: continuation packet accepted
- `F`: final packet stored successfully
- `1`: invalid packet type
- `2`: invalid or out-of-order sequence
- `3`: invalid force flag
- `4`: file already exists and force is disabled
- `5`: invalid packet data length
- `6`: invalid Base64 or storage failure
- `7`: invalid packet structure
- `8`: table or file-size limit exceeded
- `9`: invalid MP3/MP4 signature
- `A`: empty decoded file
- `B`: missing file name
- `C`: unsupported file extension

`M13` uses packet types `0` and `1` for continuation and final data. Error packet types are `2` for an invalid name, `3` for a missing file, `5` when no upload is active, and `6` for a storage failure. `M14` returns `0` when playback starts, `1` for an invalid or unsupported name, and `2` when the file does not exist. `M15` requires exactly two decimal digits from `00` to `99`; it returns `0` when the Android media volume is updated, `1` for an invalid payload, and `2` when the volume cannot be changed.

`M16` accepts one or more FS-separated filenames and returns one FS-separated status per filename: `0` deleted, `1` invalid name or unsupported extension, `2` file not found, and `3` deletion failed.

`M17` uses `language + FS + Base64(UTF-8 text)`. Hosts should send the simple language values `es` or `en`; PinpadApp resolves the appropriate locale and installed offline voice. Regional aliases remain accepted for backward compatibility. After application-certificate provisioning starts the licensed Pinpad service, PinpadApp always requires the managed RHVoice engine even when another Android TTS engine is already installed. AWS resolves the `android.tts` capability to the model-compatible unified RHVoice APK, and xTMSAgent installs it. That one APK internally contains English/Slt and Spanish/Mateo; separate language and voice application packages are not required. PinpadApp then binds specifically to RHVoice and selects the offline Spanish `Mateo` voice or the bundled offline English voice. Other installed engines remain temporary fallbacks while RHVoice is being installed. Spanish explicitly selects `Mateo`; a missing Mateo voice returns status `2` instead of using an unrelated Spanish fallback. The text is limited to 1,000 characters. Status values are `0` accepted for speech, `1` invalid payload/language/text, `2` speech engine or requested language unavailable or still provisioning, and `3` playback could not be started.

JPEG command `J4` uses the same four-digit data length and 8,192-character host-download packet size, while continuing to accept legacy three-digit packets.

Only valid MP3 and MP4 signatures are accepted. File names are limited to 64 characters, the table to 50 files, MP3 files to 16 MB, and MP4 files to 64 MB.

## A10 Demo EMV configuration bridge

Transaction command `T90` lets the Global Connect Pinpad Media Manager send an
original A10 configuration text file directly to the PINPAD. Its payload is:

`type + FS + Base64(UTF-8 file name) + FS + Base64(file contents)`

Supported type values are `D` Data Formats, `T` Terminal Configuration, `K`
contact CA key, `A` contact application, `R` contactless CA key, and `L`
contactless application. The PINPAD validates and parses the file, persists the
result in its EMV configuration store, reloads the NEXGO kernel, and responds
with `T91`. Status `0` means applied; other statuses indicate invalid content or
an SDK rejection. Files are limited to 256 KiB.

This bridge complements the legacy `T01`-`T5H` packet commands. It exists to
preserve the original text-file workflow without copying the legacy desktop
application's packet splitting and unmanaged dependencies.

## Signature Capture Protocol

Signature capture uses two-character transaction commands:

| Command | Direction | Operation |
|---|---|---|
| `S1` | Host → PINPAD | Start an on-screen signature capture |
| `S2` | PINPAD → Host | Return the result and, when captured, the image packets |

The `S1` payload is `timeoutSeconds + FS + direction + FS + imageFormat`. Timeout is `005`–`300` seconds. Direction is `H` for a horizontal/wide signing area or `V` for a vertical/tall signing area. Image format is `P` for PNG or `J` for JPEG. Example: `060 + FS + H + FS + P`.

The PINPAD acknowledges `S1` immediately, displays the signature screen, and later sends one or more `S2` frames. Each `S2` payload is `result + packet + totalPackets + Base64Data`: result is one digit, packet and total are four decimal digits each, and Base64 data is limited to 1,024 characters per frame. Packet numbering starts at `0001`. The host ACKs every `S2`; the PINPAD sends the next packet after that ACK and sends EOT after the final ACK.

Result values are `1` captured, `2` cancelled, `3` timed out, and `4` error. Non-captured results contain packet `0000`, total `0000`, and no image data. The capture screen provides OK, Clear, and Cancel buttons; Cancel requires confirmation.

## Photo and QR Protocol

Camera and QR operations use three-character transaction commands:

| Command | Direction | Operation |
|---|---|---|
| `PH1` | Host → PINPAD | Start photo capture |
| `PH2` | PINPAD → Host | Return the result and accepted JPEG packets |
| `QR1` | Host → PINPAD | Generate and display a QR code |
| `QR2` | PINPAD → Host | Return the QR display result |
| `QR3` | Host → PINPAD | Start camera-based QR reading |
| `QR4` | PINPAD → Host | Return the scan result and decoded value |

`PH1` uses `timeoutSeconds + FS + camera + FS + jpegQuality`. Timeout is `005`–`300`, camera is `F` (front) or `B` (back), and JPEG quality is `10`–`100`. A model that lacks the requested camera automatically uses its other available camera. `PH2` uses the same `result + packet + totalPackets + Base64Data` packet and ACK/EOT handshake as `S2`, with 1,024 Base64 characters per packet. Result values are `1` captured, `2` cancelled, `3` timed out, and `4` error.

`QR1` uses `timeoutSeconds + FS + Base64(UTF-8 value)`. The value is limited to 2,048 UTF-8 bytes. `QR2` contains one status digit: `1` done, `2` cancelled, `3` timed out, or `4` error.

`QR3` uses `timeoutSeconds + FS + camera`. `QR4` contains the same status values; a successful scan is `1 + FS + Base64(UTF-8 decoded value)`. Camera operations require Android camera permission, which the PINPAD requests on first use.

## Keypad Diagnostics

Hardware key events are logged to Logcat with the tag `PinpadKeypad` so CT20P keypad mappings can be observed on the real device. Each line records action, Android key code/name, scan code, unicode value, repeat count, device id, meta state, event time, and down time.

Observed CT20P mappings:

- `0` to `9`: Android `KEYCODE_0` to `KEYCODE_9`
- Up arrow: `KEYCODE_DPAD_UP`
- Down arrow: `KEYCODE_DPAD_DOWN`
- CANCEL: `KEYCODE_BACK`
- CLEAR: `KEYCODE_DEL`
- ENTER: `KEYCODE_ENTER`

Setup can currently be opened with:

- `ENTER`, then `1` within 4 seconds

CANCEL is consumed by `MainActivity` so it does not close the app from the idle screen.

## Clear-key injection mode

Clear-key command `02` is disabled during normal PINPAD operation. On a CT20P, press
`CLEAR`, then `2` within four seconds (or hold `CLEAR` while pressing `2`) and enter
both configured seven-digit key-load passwords to authorize Clear-key Injection Mode.

While this mode is active:

- Only commands `02`, `04`, `06`, and `08` are accepted.
- Command `02` received outside the mode is discarded with a single `04` EOT byte;
  it does not open the password prompt.
- Other commands received inside the mode are also discarded with EOT only.
- Each accepted command restarts a one-minute inactivity timer.
- The mode closes on one minute of inactivity, CANCEL, leaving PinpadApp, or service
  shutdown.

## Operator Exit

The protected setup actions, including **Exit to Android Home**, use this default credential pair:

- Password 1: `22687075`
- Password 2: `27071287`

After three failed attempts, password entry is locked for 30 seconds.

Exit to Android Home releases kiosk mode and explicitly restores the NEXGO control bar, message bar, Home button, Recents button, and Android navigation bar before closing PinpadApp.

## Verification

Run tests from this directory:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
D:\Source\AndroidStudio\GLOBAL_CONNECT\PinpadApp\gradlew.bat `
  -p D:\Source\AndroidStudio\GLOBAL_CONNECT\PinpadApp `
  testDebugUnitTest
```
# Application licensing

Pinpad requires the `PINPAD_APP` license issued by Global Connect ONE. On Android 11 and newer, it requests a device-owner-managed EC identity from xTMSAgent. xTMSAgent grants the package access to the non-exportable KeyChain private key and retains the signed permanent license certificate, allowing Pinpad to recover its license after an APK uninstall/reinstall. Pinpad validates the restored certificate locally before starting USB/RS232 communications. When managed credentials are unavailable, it falls back to its legacy application-owned Android Keystore identity.

The Android application ID and Kotlin namespace are `one.globalconnect.pinpad`.

Set `licenseSigningPublicKeySpkiBase64`
in the build environment or user-level Gradle properties to the Base64 SPKI public key returned by the deployed KMS application-license signing key.
Builds without the matching trust anchor fail authorization closed at runtime.
