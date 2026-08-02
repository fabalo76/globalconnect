# Global Connect RHVoice

This is a standalone Android Studio project for one fully offline RHVoice
Android TTS engine APK. It is derived from RHVoice 1.18.4 and contains exactly
two voices:

| Voice | Reported locale | Gender | Data revision | Network |
|---|---|---:|---:|---|
| `Slt` | `en-US` | female | 4.1 | not required |
| `Mateo` | `es-MX` | male | 4.14 | not required |

The APK also contains only the corresponding English and Spanish language data.
It has no Internet permission, downloader, analytics, advertising, Google TTS
dependency, or external language/voice package dependency.

## Android identity

- Application ID: `com.github.olga_yakovleva.rhvoice.android`
- Shared UID retained for upgrade compatibility:
  `com.github.olga_yakovleva.rhvoice`
- TTS service:
  `com.github.olga_yakovleva.rhvoice.android.RHVoiceService`
- Service action: `android.intent.action.TTS_SERVICE`
- Settings activity:
  `com.github.olga_yakovleva.rhvoice.android.MainActivity`
- Application icon: embedded Android `mipmap` resources at all standard
  densities; stores may read it directly from the APK
- Launcher activity: none
- Minimum Android API: 26
- Target Android API: 33
- Native ABIs: `armeabi-v7a`, `arm64-v8a`

Android discovers the service immediately after installation. The engine does
not appear in the application launcher because it is consumed through Android's
TTS API. Its status/settings activity remains available through Android's TTS
settings and the standard `INSTALL_TTS_DATA` action. Starting the service
validates or extracts the bundled data automatically; there is no voice-download
prompt or manual setup.

## Build

Prerequisites:

- Android Studio with JDK 17
- Android SDK Platform 35
- Android SDK Build Tools 35
- Android NDK `25.1.8937393`

Open this directory as the Android Studio project, or create `local.properties`
with the local Android SDK path and run:

```powershell
.\gradlew.bat clean :app:assembleDebug :app:assembleRelease :app:lintRelease
```

Outputs:

- `app\build\outputs\apk\debug\GlobalConnect-RHVoice-English-Spanish-Mateo-1.18.4-debug.apk`
- `app\build\outputs\apk\release\GlobalConnect-RHVoice-English-Spanish-Mateo-1.18.4-release-PreSigned.apk`

The release build intentionally uses the standard Android debug signing key.
It is a pre-signed input for the NEXGO production-signing portal, not the final
production-signed artifact. Debug and pre-signed release use the same
certificate so `adb install -r` can exercise the upgrade path.

## Bundled-data lifecycle

`BundledDataInstaller` generates and consumes a manifest containing each
resource's relative path, byte size, and SHA-256 digest. At runtime it:

1. takes a process lock and an OS file lock;
2. validates the manifest version and every installed file;
3. extracts into a same-filesystem staging directory;
4. verifies every extracted file and writes a synced ready marker;
5. atomically renames the completed directory into place;
6. restores the previous directory if replacement fails; and
7. removes older bundle and interrupted-staging directories only after the
   current version is valid.

The resulting data is private application storage under
`files/rhvoice-data/bundle-1.18.4-gc1`. A damaged or incomplete bundle is
recreated on the next engine initialization.

## Locale and voice behavior

- `es` and `es-MX` resolve to `Mateo`.
- `en` and `en-US` resolve to `Slt`.
- Both voices report `isNetworkConnectionRequired() == false`.
- Unsupported languages return `LANG_NOT_SUPPORTED`; there is no Russian or
  arbitrary-voice fallback.
- Discovery fails closed unless native RHVoice finds exactly `Slt` and `Mateo`.

## Global Connect audio behavior

The engine applies the existing Global Connect relative synthesis gain of
`1.25`. RHVoice's limiter remains active. PinpadApp retains its `0.85` speech
rate, which is 15% below the original `1.0` rate, and its per-utterance volume
of `1.0`. Android's TTS playback layer continues to honor per-utterance volume;
the engine does not replace or hard-code the caller's playback volume.

PinpadApp now requests only TMS capability `android.tts` and binds to this exact
engine package. It still explicitly selects `Mateo` for Spanish and an offline
English voice for English. The legacy packages below are no longer queried or
required:

- `com.github.olga_yakovleva.rhvoice.android.language.spanish`
- `com.github.olga_yakovleva.rhvoice.android.voice.mateo`
- any separate English language or voice APK

## Diagnostics

Useful logcat tags are `GlobalConnectRHVoice`, `RHVoiceBundledData`, and
`RHVoiceCore/*`. They report engine initialization, resource
validation/extraction, discovered voices, selected locale/voice, speech rate,
requested volume, gain, character count, and failures. Spoken transaction text
is never logged.

```powershell
adb logcat -s GlobalConnectRHVoice RHVoiceBundledData RHVoiceCore/*
```

## Provenance and licensing

The engine source is RHVoice tag 1.18.4, commit
`fa9dd196fd2dac3b0bf089a2d80fc8477c2380e3`. Exact data commits and the
component-by-component audit are in [docs/LICENSE_AUDIT.md](docs/LICENSE_AUDIT.md).
Required notices are shipped in `assets/licenses`, and upstream notices remain
beside the Spanish and voice data.

This project and the combined APK are distributed under GNU GPL version 2.
Anyone conveying the APK must also satisfy the GPL source-distribution
requirements: provide the complete corresponding source for the exact binary,
including these build scripts and modifications, retain notices, provide the
GPL text, and allow recipients to rebuild and modify it. A download of the APK
alone is not sufficient compliance.

See [docs/TEST_RESULTS.md](docs/TEST_RESULTS.md) for hardware results,
[docs/APK_REPORT.md](docs/APK_REPORT.md) for artifact inspection, and
[tms/catalog-entry.json](tms/catalog-entry.json) for the TMS replacement
metadata.
