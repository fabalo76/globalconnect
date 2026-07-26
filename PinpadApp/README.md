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
- `transport/*` abstracts CT20P RS232 and USB CDC access through the NEXGO SDK.
- `protocol/PINPADFrameCodec` e~~~~ncodes and decodes raw PINPAD frames, including LRC validation.
- `protocol/PINPADStreamParser` accepts arbitrary byte chunks, discards garbage before a valid start byte, emits `ACK`/`NAK`/`EOT` controls, and drops incomplete partial frames after one second.
- `protocol/PINPADSessionController` converts valid frames into the Stage 1 command responses.
- `model/PinpadCommandModels` defines the internal JSON-style request/response shape used between the protocol/service layer and the pinpad command layer.

## SDK Libraries

The Nexgo SmartPOS SDK AAR is stored locally in `app/libs` and loaded by Gradle with `implementation fileTree(dir: 'libs', include: ['*.aar'])`.

## Transport Defaults

The first-run default is `SERIAL`, which uses USB CDC. The setup screen can change:

- Port mode: `SERIAL` (USB CDC) or `RS232`
- RS232 port number
- Baud rate
- Data bits
- Stop bits
- Parity

USB CDC currently keeps the default VID/PID values `0x6352` and `0x294A`.

## Host-Controlled Serial Speed

Administration command `13` changes the persisted serial baud rate and line mode after its response flow completes. The PINPAD sends the command response and final EOT using the current settings, then reopens the transport with the new settings.

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

`M17` uses `language + FS + Base64(UTF-8 text)`. Hosts should send the simple language values `es` or `en`; PinpadApp resolves the appropriate locale and installed offline voice. Regional aliases remain accepted for backward compatibility. After application-certificate provisioning starts the licensed Pinpad service, PinpadApp always requires the managed RHVoice engine even when another Android TTS engine is already installed. AWS resolves the TTS capabilities to the model-compatible RHVoice engine, language pack, and voice pack, and xTMSAgent installs them. PinpadApp then binds specifically to RHVoice and selects the offline Spanish `Mateo` voice or an offline English voice. Other installed engines remain temporary fallbacks while RHVoice is being installed. Spanish explicitly selects `Mateo`; a missing Mateo voice returns status `2` instead of using an unrelated Spanish fallback. The text is limited to 1,000 characters. Status values are `0` accepted for speech, `1` invalid payload/language/text, `2` speech engine or requested language unavailable or still provisioning, and `3` playback could not be started.

JPEG command `J4` uses the same four-digit data length and 8,192-character host-download packet size, while continuing to accept legacy three-digit packets.

Only valid MP3 and MP4 signatures are accepted. File names are limited to 64 characters, the table to 50 files, MP3 files to 16 MB, and MP4 files to 64 MB.

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

Pinpad requires the `PINPAD_APP` license issued by Global Connect ONE. It generates a non-exportable EC key in Android Keystore, requests registration through xTMSAgent, and validates the returned permanent certificate locally before starting USB/RS232 communications.

The Android application ID and Kotlin namespace are `one.globalconnect.pinpad`.

Set `licenseSigningPublicKeySpkiBase64` in the build environment or user-level Gradle properties to the Base64 SPKI public key returned by the deployed KMS application-license signing key. Builds without the matching trust anchor fail authorization closed at runtime.
