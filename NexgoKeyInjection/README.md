# Global Connect Nexgo Key Injection

Global Connect Nexgo Key Injection is an Android application for loading cryptographic keys into supported Nexgo SmartPOS terminals. It uses the Futurex Direct Key Injection/LKI serial protocol and the Nexgo SmartPOS SDK.

## Project origins

- The Android, Nexgo PED, UART, and Futurex protocol implementation is based on `D:\Source\AndroidStudio\nexgo-futurex-lki`.
- The application namespace is `one.globalconnect.keyinjection`.

## Supported workflow

- Two-person password login and password changes.
- Nexgo internal serial, USB CDC, FTDI, and PL2303 transport selection.
- Futurex STX/ETX packet framing, XOR LRC validation, ACK/NAK flow, and NAK retries.
- Serial number read and write commands (`03` and `04`).
- DUKPT and master-key injection commands (`00` and `01`).
- Key-under-KTK injection command (`02`) for clear, preloaded-KTK, and supplied-clear-KTK modes.
- Clear PIN and MAC key loading through a PED-only parent-master bridge: when the selected slot has no master key, the app creates a random TDES parent master in that slot, uses `encryptByMKey`, loads the encrypted result with `writeWKey`, and verifies the destination KCV. The generated parent remains inside the PED because Nexgo working keys are stored beneath their parent master index.
- Nexgo PED key status, key erase, and receipt printing.

The protocol reference and captured examples are in the `Doc` directory. `Futurex-Direct_Key_Injection_API.pdf` is the authoritative protocol reference; the text files document the Nexgo-specific command usage and field mappings.

## Build and verification

From this directory on Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

The Gradle output directory contains the current build artifacts:

- `GlobalConnect-Nexgo-KeyInjection-1.14-debug-signed.apk` is installable for device testing and uses the Android debug key.
- `GlobalConnect-Nexgo-KeyInjection-1.14-release-unsigned.apk` has release password behavior, shrinking, and optimization enabled; sign it with the production Global Connect certificate before installation or distribution.

## Device validation

Unit tests validate message parsing, KCV generation, and response bodies. Final acceptance still requires a supported Nexgo terminal and an authorized Futurex SKI/LKI station to validate UART selection, PED slot mappings, encrypted key loading, KCV responses, and device key-erasure behavior.

## Security notes

- Release builds always require both passwords; debug builds can bypass them through `DISABLE_PASSWORDS` in `app/build.gradle.kts`.
- Passwords are stored with `EncryptedSharedPreferences`.
- Debug builds trace RX/TX framing, metadata, payload length, and LRC. Key and KTK payloads in commands `00`, `01`, and `02` are always redacted. Release builds do not emit packet traces.
- Clear PIN/MAC bridging is serialized and uses the selected supported index from `1`–`10`. Generated parent material and intermediate ciphertext are zeroized in application memory. The generated parent remains non-exportable inside the PED while the working key exists; a failed load rolls it back when it did not pre-exist.
- Android backup is disabled for this security-sensitive application.

## Logging

The debug APK prints redacted RX/TX packet traces under the `SerialPacketMgr` Logcat tag. The `Logger` utility can also write diagnostics to `logcat.txt` in the app external-files directory. Retrieve it with:

```powershell
adb pull /sdcard/Android/data/one.globalconnect.keyinjection/files/logcat.txt .
```

## Native dependencies

The Nexgo SDK AAR is stored in `app/libs`. Native libraries are packaged from `app/src/main/jniLibs` for `armeabi-v7a` and `arm64-v8a` using legacy JNI packaging, as required by the SDK.
