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

The first-run default is `AUTO`, which starts both transports and replies on whichever transport last received data. The setup screen can change:

- Port mode: `AUTO`, `RS232`, or `USB_CDC`
- RS232 port number
- Baud rate
- Data bits
- Stop bits
- Parity

USB CDC currently keeps the default VID/PID values `0x6352` and `0x294A`.

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

## Verification

Run tests from this directory:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
D:\Source\AndroidStudio\NEXGO_REPOS\POS_Mobile\gradlew.bat -p D:\Source\AndroidStudio\NEXGO_REPOS\PinpadApp testDebugUnitTest
```
