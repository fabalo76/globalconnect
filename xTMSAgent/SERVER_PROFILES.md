# AWS and Demo server selection

Implementation log: 2026-10-03.

The same xTMSAgent APK offers AWS and Demo in **Configuration → TMS configuration → Select TMS server**. The existing settings password is required; this entry does not allow the debug password bypass. `TmsConfigActivity` remains an internal, non-exported activity.

Choose a server, then Save. The app asks for confirmation and restarts after saving. Cancel or leaving without Save does not change the active server. Timeout settings remain editable. Returning to AWS restores the connection settings captured when leaving AWS, including its MQTT port.

| Setting | AWS | Demo |
| --- | --- | --- |
| API | Existing configured AWS instance | `https://demo.globalconnect.one:443` |
| MQTT | Existing AWS broker and port | `demo.globalconnect.one:443` |
| API fallback | Existing AWS fallback | None |
| Client certificate | Existing AWS registration | Separate Demo registration |

The VPS now routes HTTPS and native MQTT over TLS on public port 443. Certificate provisioning, heartbeat processing and MQTT configuration request/reply have passed transport tests with a disposable test client. The complete standalone backend still has remaining features.

## Provisioning

Provide separate Demo bootstrap credentials through the private user Gradle configuration or environment variables `XTMS_DEMO_DOWNLOAD_CREDENTIAL_ID` and `XTMS_DEMO_DOWNLOAD_CREDENTIAL_SECRET`. Obtain these from the standalone server setup. Do not commit their values. AWS continues using `XTMS_DOWNLOAD_CREDENTIAL_ID` and `XTMS_DOWNLOAD_CREDENTIAL_SECRET`.

Selecting a server without its own provisioning credentials is blocked. A Demo configuration with an empty secret never inherits the AWS bootstrap secret.

Saved connection profiles use Android encrypted preferences. Certificates and private keys, terminal block/unlock state, queued task acknowledgements, housekeeping state, Easy task records and staging, APK task staging, launcher configuration markers, application license state and managed application identity aliases have separate Demo storage. The AWS storage names remain unchanged to preserve existing deployed registrations and pending records.

Switching is blocked during running background work, pending APK/profile work, unfinished housekeeping/Easy tasks, pending application installations and parameter downloads. Switching does not deregister either server or delete the other server's saved state. Payment application reports originate outside xTMSAgent; use this selector on a test terminal only after confirming there are no pending payment reports in that application.

## Verification

The debug APK builds successfully. All 123 Android unit tests pass, including checks that Demo has no AWS fallback or bootstrap credential inheritance and cannot reuse the AWS certificate/task storage names. The working diff has no whitespace errors.

Release version `2.1.2.92` (code `92`) is installed on N96 `N960W900629`, with separate Demo bootstrap credentials supplied through the private build environment. Its signing fingerprint matches the existing installation; application data was preserved. The user has been asked to select Demo through the protected settings. Physical onboarding, configuration application and the AWS → Demo → AWS device test remain pending that action.
