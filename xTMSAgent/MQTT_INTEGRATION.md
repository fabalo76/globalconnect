# MQTT Integration - Global Connect ONE

This document is the client-side integration guide for xTMSAgent and any standalone C++ terminal agent that connects to Global Connect ONE. The authoritative server contract lives in `D:\Source\repos_2022\UIC_Connect_One\docs\API_DESIGN.md`, section "MQTT TOPICS".

Global Connect ONE uses AWS IoT Core for device connectivity:

```
Android or C++ terminal client
  -> AWS IoT Core MQTT/TLS
  -> IoT rules and Basic Ingest
  -> Global Connect ONE Lambda/SQS/DynamoDB/Aurora/S3
```

The legacy `tms/terminal/{TermID}/...` topic family, certificate-derived MQTT password, TCP/FTP housekeeping, `easy`, `paramreq`, `verreq`, and binary `notify` packet are not the target integration model for Global Connect ONE.

---

## Connection Contract

| Setting | Value |
| --- | --- |
| Protocol | MQTT 3.1.1 over TLS 1.2+ |
| Port | `8883` |
| Broker | AWS IoT Core endpoint, for example `<prefix>-ats.iot.us-east-1.amazonaws.com` |
| Client ID | Device serial number |
| Session | Persistent preferred: `cleanSession=false` |
| Keepalive | 240 seconds unless deployment policy overrides it |
| Authentication | AWS IoT device certificate and private key |
| Trust anchor | Amazon Root CA 1 |
| Payload encoding | UTF-8 JSON unless explicitly stated otherwise |

The serial number is the identity boundary. It must match:

- MQTT client ID.
- AWS IoT Thing name.
- `{serial}` segment in every device topic.
- Terminal/device record in Global Connect ONE inventory.

Lane is not part of the AWS IoT identity boundary. Do not use lane id for IoT certificate provisioning, MQTT client id, topic routing, heartbeat/status, task delivery, task ACKs, or transaction topics. Lane context is only used after the device is known, when Global Connect ONE resolves payment parameters, merchant/branch/lane configuration, and operator workflows.

---

## Provisioning

1. Register the device in Global Connect ONE so it has a terminal/device record and IoT registration enabled.
2. Create or retrieve the AWS IoT Thing, certificate, private key, and policy for the device serial.
3. Store certificate material in app-private storage or the device secure keystore.
4. Connect to AWS IoT with mutual TLS.
5. Subscribe to device-directed topics.
6. Publish a heartbeat and full status report after connecting.
7. Request effective configuration for each installed payment application.

The device may complete steps 1-6 before it is assigned to a lane. Parameter/configuration responses can still depend on lane assignment, so a device without a lane may connect and report status but may not receive lane-specific payment parameters.

Android implementation notes:

- `mqtt/tls/AwsIotCertificateStore.kt` loads or provisions certificate material.
- `mqtt/TmsMqttClient.kt` builds the HiveMQ client with `keyManagerFactory`.
- `mqtt/TmsMqttManager.kt` owns reconnect, subscription, telemetry, task dispatch, and ACK publishing.

C++ implementation notes:

- Prefer AWS IoT Device SDK v2 for C++ when available.
- Eclipse Paho C/C++ with OpenSSL mutual TLS is acceptable if the client already standardizes on Paho.
- Store the private key with file permissions limited to the agent user, or use a hardware/OS secure key store when available.
- Do not embed bank API keys, MQTT credential secrets, or private keys into source code or logs.

---

## Device HTTPS Authentication

Device-only HTTPS endpoints under `/v1/devices/{serial}/downloads/*`, `/v1/devices/{serial}/versions`, and `/v1/devices/{serial}/iot-credentials` do not use lane id or the bank onboarding API key. They use a per-device authorization token supplied as either:

```http
Authorization: Device <device-token>
```

or:

```http
X-Device-Token: <device-token>
```

The token must be provisioned into the device image or secure storage by the bank/device provisioning process. Treat it as a secret:

- Do not log it.
- Do not include it in task ACKs or status reports.
- Rotate it by rebuilding/reprovisioning the personalized terminal agent when bank device credentials rotate.

---

## Topic Reference

All device topics use `{serial}` = the device serial and MQTT client ID.

### Subscribed by Client

| Topic | QoS | Payload | Purpose |
| --- | --- | --- | --- |
| `tms/device/{serial}/cmd` | 1 | JSON | Remote control and direct device commands |
| `tms/device/{serial}/task` | 1 | JSON | Generic operational task envelope |
| `tms/device/{serial}/config/response` | 1 | JSON | Effective configuration response |
| `tms/device/{serial}/notify` | 1 | JSON or legacy-compatible payload | Compatibility notification channel; prefer `task` |

### Published by Client

| Topic | QoS | Payload | Purpose |
| --- | --- | --- | --- |
| `tms/device/{serial}/heartbeat` | 0 or 1 | JSON | Compact liveness update |
| `tms/device/{serial}/status` | 1 | JSON | Full operational state update |
| `tms/device/{serial}/config/request` | 1 | JSON | Request latest effective configuration |
| `tms/device/{serial}/task/ack` | 1 | JSON | Task receipt, completion, or failure |
| `$aws/rules/tms_transaction_ingest_{env}/tms/device/{serial}/transaction` | 1 | JSON | Single transaction or transaction batch through Basic Ingest |

Use the Basic Ingest prefix only for transaction publishes. Do not subscribe to `$aws/rules/...` topics.

---

## Heartbeat

Publish to `tms/device/{serial}/heartbeat` every 60 seconds or on the deployment-specific interval.

```json
{
  "serial": "TM001234",
  "firmwareVersion": "3.2.1",
  "appVersions": {
    "payment_app": "2.1.0"
  },
  "uptimeSeconds": 86400,
  "ts": "2026-06-14T16:00:00Z"
}
```

Heartbeat data updates latest operational state. It must not include cardholder data, secrets, PIN data, or key material.

---

## Status Report

Publish to `tms/device/{serial}/status` after connect, when requested by `RefreshStatus`, after software/config changes, and periodically for operational state.

```json
{
  "serial": "TM001234",
  "status": "online",
  "firmwareVersion": "3.2.1",
  "launcherVersion": "1.4.2",
  "battery": {
    "percent": 62,
    "charging": false
  },
  "network": {
    "type": "wifi",
    "primaryIp": "192.168.1.100",
    "signal": 87
  },
  "geo": {
    "lat": 14.0723,
    "lng": -87.2020,
    "accuracyMeters": 25
  },
  "peripherals": {
    "printer": "ok",
    "cardReader": "ok",
    "pinpad": "ok"
  },
  "installedApps": [
    {
      "packageName": "com.uic.payment",
      "versionName": "2.1.0",
      "versionCode": 210
    }
  ],
  "blocked": false,
  "ts": "2026-06-14T16:01:00Z"
}
```

Compact legacy keys such as `ver`, `bat`, `lat`, `lng`, `pip`, and `net` are still tolerated by the Android code and server processor, but new clients should prefer descriptive JSON fields.

---

## Configuration Request/Response

Publish to `tms/device/{serial}/config/request` on boot, after a `RefreshConfig` or `ParametersDownload` task, after software install, or when the payment app explicitly asks for parameters.

```json
{
  "serial": "TM001234",
  "appName": "payment_app",
  "currentConfigHash": "sha256:abcdef1234567890",
  "installedVersion": "2.1.0",
  "ts": "2026-06-14T16:02:00Z"
}
```

If the device is current, Global Connect ONE responds on `tms/device/{serial}/config/response`:

```json
{
  "serial": "TM001234",
  "appName": "payment_app",
  "upToDate": true
}
```

If configuration changed:

```json
{
  "serial": "TM001234",
  "appName": "payment_app",
  "configHash": "sha256:fedcba0987654321",
  "config": {
    "timeout_seconds": 45,
    "currency": "USD"
  },
  "catalogData": {},
  "treeData": {}
}
```

Clients should persist the last applied `configHash` per `appName` and avoid reapplying identical configuration.

---

## Task Envelope

Global Connect ONE sends tasks on `tms/device/{serial}/task`.

```json
{
  "TaskId": "ee000000-0000-4000-8000-000000000001",
  "TaskType": "ApplicationDownload",
  "Payload": {
    "deviceId": "f4c275f5-4a39-4ed7-9382-fa0934985dd4",
    "serialNumber": "TM001234",
    "appName": "payment_app",
    "version": "2.2.0",
    "downloadEndpoint": "/v1/devices/TM001234/downloads/app",
    "scheduledDownloadAt": "2026-06-14T17:00:00Z",
    "effectiveAt": "2026-06-14T18:00:00Z"
  },
  "DispatchedAt": "2026-06-14T16:03:00Z"
}
```

Clients should accept both Pascal-case (`TaskId`, `TaskType`, `Payload`) and legacy lower-camel (`taskId`, `taskType`, `payload`) field names while deployments are being upgraded.

Supported task types:

- `RefreshConfig`
- `ParametersDownload`
- `ApplicationDownload`
- `FirmwareDownload`
- `UpdateFirmware`
- `LauncherConfigDownload`
- `DisplayMessage`
- `RebootDevice`
- `RefreshStatus`
- `BlockDevice`
- `UnblockDevice`
- `BlockLane` and `UnblockLane` only when the task explicitly targets lane/payment context

Clients must ACK every recognized task on `tms/device/{serial}/task/ack`.

```json
{
  "taskId": "ee000000-0000-4000-8000-000000000001",
  "taskRecordId": "ee000000-0000-4000-8000-000000000001",
  "status": "completed",
  "success": true,
  "errorMessage": null,
  "result": {
    "installedVersion": "2.2.0"
  },
  "acknowledgedAt": "2026-06-14T16:04:00Z",
  "ts": "2026-06-14T16:04:00Z"
}
```

Status values:

- `acked`: task accepted and processing has started.
- `completed`: task finished successfully.
- `failed`: task failed; include `errorCode` and a scrubbed `message`.

Do not put secrets, full PAN, PIN data, CVV, track data, or key material in task ACK results.

---

## Downloads

Download tasks should use the signed HTTPS URL or device download endpoint supplied by the task payload. The client must:

1. Download to a temporary file.
2. Verify expected byte size when provided.
3. Verify SHA-256 when provided.
4. Install or apply the artifact.
5. Publish `task/ack`.
6. Publish a full status report with updated installed-app/version data.

Android implementation:

- `mqtt/downloads/AwsDeviceDownloadManager.kt` executes application and firmware download tasks.
- `launcher/LauncherConfigManager.kt` applies launcher configuration downloads.

C++ clients:

- Use a TLS-validating HTTP client.
- Keep download URLs out of logs because signed URLs can grant temporary access.
- Use atomic rename after verification so interrupted downloads do not appear complete.

---

## Transactions

Publish transactions to:

`$aws/rules/tms_transaction_ingest_{env}/tms/device/{serial}/transaction`

Single transaction:

```json
{
  "transactionId": "c72fe4db-760d-46ef-b8d8-b8f6f25f1b8a",
  "serialNumber": "TM001234",
  "acquirerCode": "ACQ01",
  "issuerCode": "ISS01",
  "transactionType": "sale",
  "amount": 125.00,
  "currencyCode": "USD",
  "approvalCode": "ABC123",
  "responseCode": "00",
  "isApproved": true,
  "rrn": "250715123456",
  "maskedPan": "411111******1111",
  "transactionAt": "2026-06-14T15:59:33Z"
}
```

Batch:

```json
{
  "batchId": "ec662546-1c87-405f-a2c2-bdd50f3af843",
  "serialNumber": "TM001234",
  "transactions": [
    {
      "transactionId": "c72fe4db-760d-46ef-b8d8-b8f6f25f1b8a",
      "transactionType": "sale",
      "amount": 125.00,
      "currencyCode": "USD",
      "responseCode": "00",
      "isApproved": true,
      "maskedPan": "411111******1111",
      "transactionAt": "2026-06-14T15:59:33Z"
    }
  ]
}
```

Transaction rules:

- QoS 1 is required.
- `transactionId` should be generated by the terminal and treated as the idempotency key.
- Persist unsent transactions locally until MQTT PUBACK.
- PAN must be masked only, for example `411111******1111`.
- Never publish CVV, PIN, full PAN, track data, session keys, PIN keys, or DUKPT key material.

---

## Remote Control Command

Global Connect ONE sends remote-control commands to `tms/device/{serial}/cmd`.

```json
{
  "cmd": "remote_start",
  "provider": "kinesis-webrtc",
  "sessionId": "ff000000-0000-4000-8000-000000000001",
  "region": "us-east-1",
  "channelName": "uic-remote-dev-01",
  "channelArn": "arn:aws:kinesisvideo:us-east-1:123456789012:channel/uic-remote-dev-01/1234567890",
  "role": "master",
  "clientId": "device-TM001234",
  "viewerClientId": "viewer-ff000000000040008000000000000001",
  "endpoints": {
    "WSS": "wss://v-abc.kinesisvideo.us-east-1.amazonaws.com",
    "HTTPS": "https://v-abc.kinesisvideo.us-east-1.amazonaws.com"
  },
  "iceServers": [],
  "credentials": {
    "accessKeyId": "ASI...",
    "secretAccessKey": "...",
    "sessionToken": "...",
    "expiration": "2026-06-14T18:15:00Z"
  },
  "expiresAt": "2026-06-14T18:15:00Z",
  "timeout": 120,
  "ts": "2026-06-14T16:05:00Z"
}
```

Client behavior:

- Validate `provider == "kinesis-webrtc"`.
- Treat STS credentials as memory-only session credentials.
- Connect as Kinesis WebRTC `MASTER`.
- Open/accept the `uic-control` data channel for pointer/key events.
- Stop when `remote_stop` arrives, when `expiresAt` passes, or when the session fails.

Android classes:

- `remote/RemoteControlConfig.kt`
- `remote/KinesisSignalingClient.kt`
- `remote/KinesisWebRtcRemoteClient.kt`
- `remote/RemoteInputHandler.kt`

---

## Android Payment App Broadcasts

xTMSAgent can act as the local bridge between a payment app and Global Connect ONE.

Recommended local contract:

- Payment app asks xTMSAgent for parameters.
- xTMSAgent publishes `config/request`.
- xTMSAgent receives `config/response`, persists it, and notifies the payment app.
- Payment app sends transaction JSON to xTMSAgent.
- xTMSAgent publishes the transaction to Basic Ingest and reports local ACK/failure back to the payment app.

This keeps AWS IoT credentials in the launcher/agent instead of duplicating them across every payment application.

---

## Standalone C++ Client Checklist

A C++ terminal agent that does not run inside xTMSAgent must implement the same responsibilities:

1. Load serial number, IoT endpoint, certificate, private key, and root CA.
2. Connect with mutual TLS and client ID equal to serial number.
3. Subscribe to `cmd`, `task`, `config/response`, and optionally `notify`.
4. Publish heartbeat after connect and on interval.
5. Publish full status after connect and after every material state change.
6. Request configuration on boot and after config/download tasks.
7. Execute tasks idempotently and ACK with `acked`, `completed`, or `failed`.
8. Publish transactions through Basic Ingest with QoS 1 and local retry.
9. Scrub logs and payloads for PCI-sensitive data.
10. Rotate/reload certificates without requiring a firmware rebuild.

Recommended C++ libraries:

- AWS IoT Device SDK v2 for C++ for MQTT mutual TLS and reconnect behavior.
- AWS CRT HTTP or libcurl/OpenSSL for signed HTTPS downloads.
- nlohmann/json, RapidJSON, or json-c for JSON parsing.
- Platform secure storage or TPM/TEE-backed key storage where available.

---

## Security Requirements

- TLS 1.2+ only; plaintext MQTT port `1883` is forbidden.
- Device private keys must never be logged or included in crash dumps.
- Signed download URLs must be treated as secrets until expiration.
- Full PAN, CVV, PIN, track data, and key material must never be sent to Global Connect ONE.
- All transaction PAN fields must be masked before leaving the payment app/native layer.
- Unknown fields from server payloads should be ignored unless explicitly supported.
- Server-owned fields such as task status timestamps should be generated by the client only where the contract requires client-side reporting.
- Failed task messages must be scrubbed before logging or publishing.

---

## Local Test Flow

1. Build/install xTMSAgent.
2. Provision the device certificate and IoT Thing for the registered device serial; do not require lane assignment for this step.
3. Configure the IoT endpoint and environment.
4. Start the launcher and confirm AWS IoT connection.
5. Confirm subscriptions to `cmd`, `task`, and `config/response`.
6. Confirm heartbeat/status messages reach Global Connect ONE.
7. Send a `RefreshStatus` task from the portal and verify `task/ack`.
8. Send a `RefreshConfig` task and verify `config/request` then `config/response`.
9. Publish a masked test transaction and verify it appears in the platform.
10. Start/stop a remote-control session and verify cleanup.
