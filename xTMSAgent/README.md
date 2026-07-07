# xTMSAgent Launcher

Android launcher application for NEXGO SmartPOS devices. It serves as the device home screen and integrates with Global Connect ONE for MQTT telemetry, terminal tasks, parameter/configuration updates, software downloads, transaction reporting, and Kinesis WebRTC remote control.

---

## Integration Target

Global Connect ONE is the active cloud target.

| Concern | Global Connect ONE contract |
| --- | --- |
| MQTT broker | AWS IoT Core endpoint from `/uicconnectone/{env}/iot/endpoint` |
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

---

## Log File

**Path:** `/storage/emulated/0/Android/data/one.globalconnect.xtmsagent/files/Log.txt`  
**Purpose:** Records operations, errors, MQTT events, task progress, download outcomes, and remote-control session activity.

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

For full payload details see [MQTT_INTEGRATION.md](MQTT_INTEGRATION.md) and the platform contract in `D:\Source\repos_2022\UIC_Connect_One\docs\API_DESIGN.md`.

---

## Remote Control

Remote control is aligned with the Global Connect ONE AWS design:

- Global Connect ONE sends `remote_start` on `tms/device/{serial}/cmd` with `provider = "kinesis-webrtc"`.
- The payload contains the Kinesis signaling channel ARN/name, `MASTER` WSS endpoint, ICE servers, and short-lived STS credentials.
- xTMSAgent starts a foreground `mediaProjection` service, signs the Kinesis WSS URL with SigV4, and connects as the WebRTC `MASTER`.
- The portal connects as `VIEWER`; screen video flows through WebRTC and pointer/key events return on the `uic-control` data channel.
- No EC2, ECS, ALB, NAT Gateway, or custom relay is required by the Android client.

Key classes:

- `remote/RemoteControlConfig.kt` parses the Global Connect ONE payload.
- `remote/KinesisSignalingClient.kt` handles Kinesis SDP/ICE signaling.
- `remote/KinesisWebRtcRemoteClient.kt` owns screen capture, peer connection, and control data channel.
- `remote/RemoteInputHandler.kt` injects touch/key events through the accessibility service.

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

**compileSdk:** 36  
**minSdk:** 29
