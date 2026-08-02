# APK and ABI report

Report date: 2026-07-29

## Final artifacts

| Variant | Bytes | MiB | SHA-256 |
|---|---:|---:|---|
| `GlobalConnect-RHVoice-English-Spanish-Mateo-1.18.4-debug.apk` | 13,685,285 | 13.051 | `38054f2af8ebabd9e5132100d9693f0c746a0be2f39065515117cbc39af07337` |
| `GlobalConnect-RHVoice-English-Spanish-Mateo-1.18.4-release-PreSigned.apk` | 11,845,280 | 11.297 | `46df650ea611e4d81dcfcaf49141379d479c962143eb384c2ff5aa6334194a6f` |

The legacy three-debug-APK input bundle is 34,903,873 bytes (33.287 MiB).
The pruned release APK is 66.1% smaller while also adding the English Slt
voice.

## Manifest

- Package: `com.github.olga_yakovleva.rhvoice.android`
- Version name/code: `1.18.4` / `1180401`
- Compile/minimum/target API: 35 / 26 / 33
- Shared UID: `com.github.olga_yakovleva.rhvoice`
- Service:
  `com.github.olga_yakovleva.rhvoice.android.RHVoiceService`
- Service action: `android.intent.action.TTS_SERVICE`
- Embedded application icon densities: 160, 240, 320, 480, and 640 dpi
- Launcher activity: none
- TTS settings/install-data activity remains exported
- Packaged UI locales: default English and `es`
- Declared Android permissions: none
- Native ABIs: `armeabi-v7a`, `arm64-v8a`

`aapt dump permissions` reports only the package header. In particular, there
is no `android.permission.INTERNET`.

## APK contents

- 116 ZIP entries total
- 97 checksummed bundled-data files
- language roots derived from entries: `English`, `Spanish`
- voice roots derived from entries: `slt`, `Mateo`
- native libraries:
  - `lib/armeabi-v7a/libRHVoice_jni.so`
  - `lib/armeabi-v7a/libc++_shared.so`
  - `lib/arm64-v8a/libRHVoice_jni.so`
  - `lib/arm64-v8a/libc++_shared.so`
- eight license/notice assets

An entry-name scan found no Russian readme, `.github` metadata,
`htsvoice_parser.py`, downloader, Google, x86, x86_64, MIPS, or other voice
data. An `llvm-strings` scan of both JNI libraries found none of the excluded
native language names. The ELF headers report:

- `armeabi-v7a`: ELF32, machine ARM
- `arm64-v8a`: ELF64, machine AArch64

## Signing

Both variants verify under Android APK Signature Scheme v2 with one signer:

- Subject: `C=US, O=Android, CN=Android Debug`
- Certificate SHA-256:
  `70dcd59ae328312f2154ef64210d95c437351e847975fb215243217cd6739d50`
- Key: RSA 2048-bit

The release is deliberately only the NEXGO portal input. After portal signing,
the production APK will have a different byte size, SHA-256, and signer;
update the TMS catalog with those production values before deployment.

## Build verification

Clean baseline command:

```powershell
.\gradlew.bat --no-daemon clean :app:assembleDebug :app:assembleRelease :app:lintRelease
```

Result: `BUILD SUCCESSFUL` in 7m39s. Release lint completed and wrote
`app/build/reports/lint-results-release.html`. The NDK emitted warnings already
present in upstream RHVoice/HTS source; there were no compiler or linker
errors.

The icon/no-launcher follow-up was rebuilt with:

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleRelease :app:lintRelease
```

Result: `BUILD SUCCESSFUL` in 55s. `aapt` reports all five application-icon
densities and no `launchable-activity`. The exact rebuilt release APK passed the
complete offline device instrumentation suite after installation.
