# TMS catalog migration

`catalog-entry.json` is the complete logical dependency replacement for the
legacy three-application bundle. Adapt its field names to the deployed AWS
catalog schema without changing the semantics:

1. upload the NEXGO production-signed unified APK;
2. replace the pre-signed artifact's size, SHA-256, and certificate values;
3. publish it as the sole provider of `android.tts` for CT20P/N82;
4. make the legacy Spanish-language and Mateo-voice applications non-required;
5. upgrade the core package in place;
6. validate the TTS service and both offline voices; then
7. uninstall the two obsolete packages.

PinpadApp no longer requests `android.tts.language.es` or
`android.tts.voice.es.mateo`. xTMSAgent may temporarily keep those capability
names allow-listed for rollback compatibility, but new catalog resolution must
return only this unified application for `android.tts`.

The pre-signed release hash in this directory must never be used as the
production hash after NEXGO signing because portal signing changes the APK
bytes and signer.
