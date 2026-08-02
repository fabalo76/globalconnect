# Changes from RHVoice 1.18.4

- Replaced the multi-APK Android packaging with one application module while
  preserving the original engine application ID and shared UID.
- Compiled only the RHVoice core, HTS engine, JNI bridge, built-in English
  implementation, and generic data-only language implementation.
- Disabled the Android package client, downloadable packages, network
  dependencies, and native implementations for Russian and every unused
  language.
- Bundled only English 2.17, Spanish revision 41, Slt 4.1, and Mateo 4.14 data.
- Added a versioned, checksummed, atomic private-storage installer.
- Replaced permissive fallback selection with strict English/Slt and
  Spanish/Mateo mapping.
- Exposed only two offline Android `Voice` objects and English/Spanish
  availability.
- Applied the Global Connect output gain `1.25`.
- Added privacy-safe Android diagnostics and on-device synthesis tests.
- Limited Gradle/NDK packaging to `armeabi-v7a` and `arm64-v8a`, API 26+,
  English/Spanish UI resources, and debug-key signing for both initial
  artifacts.

Files for unused native language implementations remain in the corresponding
source tree for GPL source completeness and traceability, but `Android.mk`
explicitly filters them out. They are not compiled into either native library
and no data, dictionaries, models, or UI translations for them are packaged.
