# Global Connect RHVoice build

The managed RHVoice bundle is based on upstream commit
`ce3c2312cdd303558ae3aead9036d47d1f24b36a`.

Apply `global-connect-output-gain.patch` from the RHVoice repository root, then
build the development engine:

```powershell
git apply --unidiff-zero D:\Source\AndroidStudio\GLOBAL_CONNECT\PinpadApp\provisioning\rhvoice\global-connect-output-gain.patch
cd src\android
.\gradlew.bat :RHVoice-core:assembleDevDebug
```

The patch applies a 1.25 relative synthesis gain. RHVoice's limiter controls
peaks above unity. The engine, language, and voice APKs use a shared Android UID
and must all be signed with the same certificate.
