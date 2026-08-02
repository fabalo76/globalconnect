# Global Connect RHVoice build

The managed RHVoice application is now a single offline APK built from:

`D:\Source\AndroidStudio\GLOBAL_CONNECT\GlobalConnectRHVoice`

It preserves the engine package
`com.github.olga_yakovleva.rhvoice.android` and bundles English/Slt plus
Spanish/Mateo internally. PinpadApp must request only the `android.tts`
application capability. Voice readiness is verified through Android's
`TextToSpeech.voices` API; the legacy Spanish language and Mateo application
packages are no longer required.

The unified engine applies a 1.25 relative synthesis gain. RHVoice's limiter
controls peaks above unity. PinpadApp retains its `0.85` speech-rate setting and
per-utterance volume `1.0`.

Build both artifacts from the unified project:

```powershell
cd D:\Source\AndroidStudio\GLOBAL_CONNECT\GlobalConnectRHVoice
.\gradlew.bat :app:assembleDebug :app:assembleRelease
```
