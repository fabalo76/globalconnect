# xTMSAgent Launcher

Android launcher application for NEXGO SmartPOS devices. It serves as the device home screen and integrates with Global Connect ONE for MQTT telemetry, terminal tasks, parameter/configuration updates, software downloads, transaction reporting, and Kinesis WebRTC remote control.

---

## Integration Target

Global Connect ONE is the active cloud target.

| Concern | Global Connect ONE contract |
| --- | --- |
| MQTT broker | AWS IoT Core endpoint from the active environment configuration |
| Protocol | MQTT 3.1.1 over TLS 1.2+, port `8883` |
| Client ID | Device serial number, same value used in topic `{serial}` |
| Authentication | AWS IoT device certificate and private key |
| IoT identity scope | Registered device serial; lane assignment is not required for IoT registration |
| Device topics | `tms/device/{serial}/...` |
| Transactions | AWS IoT Basic Ingest topic `$aws/rules/tms_transaction_ingest_{env}/tms/device/{serial}/transaction` |
| Downloads | HTTPS signed URLs returned by Global Connect ONE device download endpoints |
| Remote control | Kinesis Video Streams WebRTC, device connects as `MASTER` |

The older `tms/terminal/{TermID}/...`, broker-password, TCP/FTP, `easy`, `paramreq`, `verreq`, and binary `notify` flows are legacy compatibility concepts. New work should use the Global Connect ONE device topics and task model described in [MQTT_INTEGRATION.md](MQTT_INTEGRATION.md).

AWS IoT provisioning is device-scoped. A terminal can register its Thing/certificate and exchange MQTT on `tms/device/{serial}/...` as long as it is registered as a Global Connect ONE device with IoT enabled; it does not need to be assigned to a lane. Lane context is only used when resolving payment parameters, downloads that depend on merchant/branch/lane configuration, and operator workflow.

The exported application licensing service is a generic broker for offline application licenses. It verifies the caller UID, package, and installed APK signer, then relays registration over the authenticated device MQTT connection. Licensed applications generate and retain their own Android Keystore private keys; xTMSAgent never receives application private keys.

Licensed applications can also request an allow-listed managed capability through the same UID-verified service. For `android.tts`, xTMSAgent sends the terminal model, Android SDK, and ABI list to the authenticated AWS device endpoint. AWS resolves the bank catalog's model-compatible RHVoice version and dispatches a normal `ApplicationDownload` task. After the APK is installed, xTMSAgent sends an explicit completion signal to the requesting package so it can verify and initialize the newly available Android service.

---

## Log File

**Path:** `/storage/emulated/0/Android/data/one.globalconnect.xtmsagent/files/Log.txt`  
**Purpose:** Records operations, errors, MQTT events, task progress, download outcomes, and remote-control session activity.

---

## Default Android Config Credentials

On a fresh installation, the password-protected **Android Config** option uses both of these values:

| Field | Default value |
| --- | --- |
| Password 1 | `22687075` |
| Password 2 | `27071287` |

Both passwords must be entered. Values already stored on the terminal, changed locally, or supplied by a TMS configuration override these defaults.

---

## Architecture Overview

```
xTMSAgent
  |
  | MQTT/TLS 8883 with device certificate
  v
AWS IoT Core
  |
  | Basic Ingest / IoT rules
  v
Global Connect ONE Lambdas, SQS, DynamoDB, Aurora, S3
```

Key runtime pieces:

- `mqtt/TmsMqttService.kt` owns the foreground MQTT service lifecycle.
- `mqtt/TmsMqttManager.kt` provisions/loads IoT credentials, connects to AWS IoT, subscribes to device topics, publishes telemetry, and dispatches tasks.
- `mqtt/TmsMqttClient.kt` defines the Global Connect ONE topic helpers and HiveMQ client builders.
- `mqtt/tls/AwsIotCertificateStore.kt` stores the device certificate/private key under app-private storage.
- `params/ParamManager.kt` requests and applies effective configuration.
- `mqtt/downloads/AwsDeviceDownloadManager.kt` handles signed download tasks.
- `transactions/TransactionReportManager.kt` publishes payment-app transaction reports to Basic Ingest.

When xTMSAgent is Device Owner, application tasks use Android `PackageInstaller`
sessions as the primary silent installer. Session state is persisted so an
xTMSAgent self-update can acknowledge the task after `MY_PACKAGE_REPLACED`
starts the new process. The NEXGO SDK remains a compatibility fallback when
Device Owner is unavailable or Android rejects the session. While a NEXGO
callback is pending, xTMSAgent also polls PackageManager; a lost callback is
accepted when Android confirms the requested or a newer version. Installer
selection, status codes, retries, and failures are written to the local
diagnostic log.

---

## MQTT Server Interaction

The launcher subscribes to:

| Topic | Purpose |
| --- | --- |
| `tms/device/{serial}/notify` | Compatibility notification channel; prefer `task` for new work |
| `tms/device/{serial}/cmd` | Remote control and direct command messages |
| `tms/device/{serial}/task` | Generic Global Connect task envelope |
| `tms/device/{serial}/config/response` | Effective configuration response |

The launcher publishes:

| Topic | Purpose |
| --- | --- |
| `tms/device/{serial}/heartbeat` | Compact liveness heartbeat |
| `tms/device/{serial}/status` | Full operational state report |
| `tms/device/{serial}/config/request` | Parameter/configuration request |
| `tms/device/{serial}/task/ack` | Task receipt, completion, or failure |
| `$aws/rules/tms_transaction_ingest_{env}/tms/device/{serial}/transaction` | Transaction or transaction batch report |

For full payload details see [MQTT_INTEGRATION.md](MQTT_INTEGRATION.md) and the platform repository contract under `docs/API_DESIGN.md`.

---

## Remote Control

Remote control is aligned with the Global Connect ONE AWS design:

- Global Connect ONE sends `remote_start` on `tms/device/{serial}/cmd` with `provider = "kinesis-webrtc"`.
- The payload contains the Kinesis signaling channel ARN/name, `MASTER` WSS endpoint, ICE servers, and short-lived STS credentials.
- xTMSAgent starts a foreground `mediaProjection` service, signs the Kinesis WSS URL with SigV4, and connects as the WebRTC `MASTER`.
- The transparent MediaProjection permission activity runs in an isolated task. When permission handling finishes, Android returns to the application that was visible before remote control started instead of revealing the xTMSAgent launcher.
- The portal connects as `VIEWER`; screen video flows through WebRTC and pointer/key events return on the `globalconnect-control` data channel.
- No EC2, ECS, ALB, NAT Gateway, or custom relay is required by the Android client.

Key classes:

- `remote/RemoteControlConfig.kt` parses the Global Connect ONE payload.
- `remote/KinesisSignalingClient.kt` handles Kinesis SDP/ICE signaling.
- `remote/KinesisWebRtcRemoteClient.kt` owns screen capture, peer connection, and control data channel.
- `remote/RemoteInputHandler.kt` injects touch/key events through the accessibility service.

The Kinesis signaling WebSocket sends a 20-second keepalive. Short signaling
interruptions reconnect with bounded exponential backoff without immediately
destroying the active WebRTC peer; an exhausted recovery still closes the
session normally.

---

## C++/Native Integration Notes

The active Global Connect ONE protocol boundary is MQTT/JSON plus HTTPS downloads. Native C/C++ payment or device agents should integrate at that boundary instead of reusing the legacy TCP/FTP housekeeping protocol.

- Use AWS IoT Core mutual TLS with the device certificate and private key.
- Use the device serial as MQTT client ID and topic `{serial}`.
- Do not use lane id for IoT certificate provisioning, MQTT client id, topic routing, heartbeat/status, task delivery, task ACKs, or transaction topics.
- Publish transactions only with masked PAN values; never send full PAN, CVV, PIN, or key material.
- Use QoS 1 for transactions and task acknowledgments, and persist unsent transactions locally until PUBACK.
- Keep native code focused on payment/device primitives; route Global Connect ONE task/config/download orchestration through the Android MQTT service unless the native agent is a standalone client.

Standalone C++ clients should follow the same topic and payload contract in [MQTT_INTEGRATION.md](MQTT_INTEGRATION.md). Recommended libraries are AWS IoT Device SDK v2 for C++ or Eclipse Paho with OpenSSL mutual TLS.

---

## Build

```bash
# Debug APK
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug
```

### Updating a debug device-owner installation

Android protects the device-owner package from `am force-stop` and normal uninstall.
For a CT20P running the `globalconnectDebug` flavor, update it in place without
Android Studio's force-stop step:

```powershell
.\tools\install-globalconnect-debug.ps1
```

Debug APKs declare `android:testOnly="true"` and include a debug-only owner
release receiver. To remove xTMSAgent from a development device:

```powershell
.\tools\uninstall-globalconnect-debug.ps1
```

Release builds do not declare `testOnly` and remain protected while they are the
device owner.

### Recovering or updating a production device owner

A device-owner application does not need to be removed before it is updated.
Install or push the replacement APK over the existing package. The replacement
must use the same application ID and signing certificate as the installed APK,
and normally must have a higher version code.

- If Global Connect ONE IoT is connected, push the new xTMSAgent version from
  the application catalog. As Device Owner, xTMSAgent uses Android
  `PackageInstaller` to replace the running package and resumes the task
  acknowledgment from the replacement process.
- If ADB was already enabled and authorized, `adb install -r <apk>` still works
  when MTP file transfer is disabled. ADB and MTP are separate USB functions.
- From xTMSAgent 2.1.2.48 onward, **Config > USB Config** can enable or disable
  MTP/PTP after the Android Config passwords are entered. Debug builds allow
  file transfer by default; release builds disable it by default.
- If neither IoT updating nor authorized ADB is available, use Android's factory
  reset flow. The analyzed Nexgo CT20P and N82 firmware use the factory-reset
  password `334455`. N82 reads `ro.xgd.custom.pwd`; a customer-specific firmware
  may override that property.

Factory reset erases xTMSAgent, its Device Owner state, certificates, and local
configuration. Use it only when an in-place, same-signer update is unavailable.

### Production signing

Release APKs are pre-signed with the Android debug key when no production
keystore is configured because the Nexgo signing portal does not accept an
unsigned APK. The APK returned by Nexgo must be signed with the same Nexgo
production certificate for every update of an installed production package.

If a production keystore is available locally, configure it outside the
repository through user-level Gradle properties
(`%USERPROFILE%/.gradle/gradle.properties`) or environment variables:

```properties
XTMS_RELEASE_STORE_FILE=C:/secure/path/xtms-release.jks
XTMS_RELEASE_STORE_PASSWORD=provided-outside-source-control
XTMS_RELEASE_KEY_ALIAS=provided-outside-source-control
XTMS_RELEASE_KEY_PASSWORD=provided-outside-source-control
```

Then build the required client flavor, for example:

```powershell
.\gradlew.bat assembleGlobalconnectRelease
```

When these four values are absent, Gradle produces a release APK pre-signed
with the debug key for submission to the Nexgo signing portal. Keep the same
final production key for every upgrade of a deployed application ID.

**compileSdk:** 36  
**minSdk:** 29
