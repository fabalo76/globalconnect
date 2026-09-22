# xTMSAgent Launcher

Android launcher application for NEXGO SmartPOS devices. It serves as the device home screen and integrates with Global Connect ONE for MQTT telemetry, terminal tasks, parameter/configuration updates, the Global Connect Store, software downloads, transaction reporting, and Kinesis WebRTC remote control.

## Unified distribution

Version 2.1.2.87 applies supported profile settings and reports unsupported fields
as `skipped_<reason>`. Skips do not fail the task; an entirely unsupported profile
is a successful no-op with every field listed as skipped. Invalid profiles and
actual application errors still fail.

Version 2.1.2.86 recognizes CT20 and provisions device ownership using `702108171`.
CT20 v1.2.1 and N96 v1.3.5 gain firmware-verified profile controls for time,
location, Wi-Fi, Bluetooth and Ethernet; N96 also supports mobile data and airplane
mode. Network commands require the inspected PSS APK hash and command family.
Unsupported settings are skipped in 2.1.2.87. CT20/CT20P/N96 kiosk
controls now use the firmware's disable-flag convention and verify the flags.
See [device profile support](DEVICE_PROFILE_FIRMWARE_SUPPORT.md) for the command
matrix, validation evidence and rollout limits.

Version 2.1.2.85 enables N96 device-owner provisioning with Nexgo command
`902108171`, retaining the existing command-family checks and Android ownership
readback. Verified on an N96 running firmware v1.3.5: provisioning succeeded,
the agent became the default HOME application, tethering restrictions applied,
and remote-control accessibility remained connected. This does not change the
N6ProLite trial or enable unvalidated extended system controls.

xTMSAgent is built as one application (`one.globalconnect.xtmsagent.globalconnect`) without bank product flavors. This preserves in-place upgrades for the existing Global Connect distribution. Bank branding and launcher behavior are delivered by the launcher configuration in Global Connect ONE, including brand images, launcher colors, text size, system-password protection, app ordering, and navigation/control visibility.

The first build receives a global download credential through the `XTMS_DOWNLOAD_CREDENTIAL_ID` and `XTMS_DOWNLOAD_CREDENTIAL_SECRET` Gradle properties or environment variables. Do not commit these values. After an authenticated launcher-config download, the agent adopts and persists the newest active global credential supplied by the portal, allowing credentials to overlap during rotation.

Release builds require both values and fail before packaging if either is blank. Configure the active credential pair in your private user Gradle properties (`~/.gradle/gradle.properties`) or in the build environment, then run `./gradlew assembleRelease`. Debug builds remain available without credentials for local development, but cannot provision a fresh terminal without a valid download secret.

If a terminal displays **TMS configuration error: device secret is missing**, its launcher configuration and the APK bootstrap credential are both empty. Rebuild with the active global download credential and update the installed app using the same application ID and compatible signing key. An update can fill the empty configuration from the APK credential; retrying the connection alone cannot supply the missing secret. Older bank-specific packages require their matching distribution/update path; the unified APK does not update a different application ID in place.

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
| Global Connect Store | Device-token HTTPS catalog and signed app downloads, scoped by bank/group and filtered by device-model compatibility |
| Remote control | Kinesis Video Streams WebRTC, device connects as `MASTER` |

The older `tms/terminal/{TermID}/...`, broker-password, TCP/FTP, `easy`, `paramreq`, `verreq`, and binary `notify` flows are legacy compatibility concepts. New work should use the Global Connect ONE device topics and task model described in [MQTT_INTEGRATION.md](MQTT_INTEGRATION.md).

AWS IoT provisioning is device-scoped. A terminal can register its Thing/certificate and exchange MQTT on `tms/device/{serial}/...` as long as it is registered as a Global Connect ONE device with IoT enabled; it does not need to be assigned to a lane. For banks with Merchant Network enabled, lane context is used when resolving payment parameters and operator workflow. For banks without Merchant Network, parameter values and tree records are assigned directly to the device.

When the portal transfers a provisioned device to another bank, it sends a `deregister` command before revoking the current AWS IoT registration. xTMSAgent clears its local certificate and private key, disconnects, and retries credential provisioning until it can register under the destination bank.

The TMS Config entry always requires the administrator or rotating super password, including in debug builds. Its **Reset TMS Registration** action requires an additional confirmation, revokes the server-side registration first, clears the local certificate, and reconnects to provision a replacement certificate.

The exported application licensing service is a generic broker for offline application licenses. It verifies the caller UID, package, and installed APK signer, then relays registration over the authenticated device MQTT connection. On Android 11 and newer, a device-owner xTMSAgent generates each licensed application's identity in the managed Android KeyChain, grants the installed package access, and retains the signed license certificate in xTMSAgent no-backup private storage. Reinstalling the licensed APK therefore restores the same identity and certificate without exporting the private key or contacting the server again. Older Android versions fall back to an application-owned Android Keystore identity.

Licensed applications can also request an allow-listed managed capability through the same UID-verified service. For `android.tts`, xTMSAgent sends the terminal model, Android SDK, and ABI list to the authenticated AWS device endpoint. AWS resolves the bank catalog's model-compatible RHVoice version and dispatches a normal `ApplicationDownload` task. After the APK is installed, xTMSAgent sends an explicit completion signal to the requesting package so it can verify and initialize the newly available Android service.

---

## Log File

Version 2.1.2.65 changes the N6ProLite accessibility setup trial to the absolute
Android settings executable for user 0. Inspection of the exported PSS APK showed
that this selects its direct process-execution path instead of the native helper
that discarded the underlying result. Retry logging and automatic Downloads
exports are retained. Device-owner provisioning is unchanged. Accessibility
compatibility still needs terminal validation; see `N6PROLITE_PSS_FINDINGS_20260911.md`.

Version 2.1.2.64 adds **Config → Diagnostics → Export PSS APK to Downloads**.
It creates `Download/xTMSAgent/xtmsagent-pss-<timestamp>.zip`, containing the
installed `com.xgd.possystemservice` base APK, any split APKs, a manifest with
SHA-256 checksums, and a fresh diagnostics report. APK bytes are copied unchanged;
the archive contains no PSS private application data. Send the ZIP for offline
firmware analysis. Export errors are recorded in the existing diagnostics log.

Version 2.1.2.63 adds a bounded N96-derived owner-command trial (`902108171`) for
N6ProLite only when both the firmware property and SDK command base are 90000000.
Success is determined from Android's actual owner state, not the SDK return alone.
The N6ProLite path no longer attempts vendor root commands with empty credentials.

**Config → Diagnostics → Retry device setup** now persists before/after state,
command selection, SDK return values, timing, and local exception details, then
automatically saves the report to **Download/xTMSAgent**. The screen confirms the
saved filename. **Export to Downloads** remains available for another snapshot.
Each manual retry has an ID and begin/end markers. The vendor binder APIs expose
integer/boolean results, not shell stdout/stderr; the report labels that limitation.
A bounded, filtered logcat snapshot is also attempted with the app's existing
permissions. It may contain earlier messages or no vendor messages at all. Setup
events are written independently and survive normal app/process restarts even
when system logcat cannot be read. No device-wide log permission is requested.

Version 2.1.2.62 adds the N6ProLite model and tries device-owner provisioning through
the NEXGO system service's standard `dpm set-device-owner` command interfaces. It
does not assume that N6S numeric commands or system assets are compatible. Those
capabilities remain pending terminal validation; the real panel size is read on device.

For production-terminal testing without ADB: install the signed debug APK, open
**Config → Diagnostics**, use **Retry device setup**, then **Export to Downloads**.
The report is saved under `Download/xTMSAgent/xtmsagent-diagnostics-<timestamp>.txt`.
Exports refresh the snapshot and include persistent setup results, command-base
properties, SDK command base, physical screen dimensions, management state, and
recent process-exit reasons. Uncaught exception class/stack frames are retained
across restarts. This is a targeted diagnostic report, not a full system logcat.

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
- `GlobalConnectStoreActivity.kt` renders the bank/group-aware store.
- `store/GlobalConnectStoreClient.kt` resolves the catalog and verifies signed APK downloads.
- `store/StoreAppInstaller.kt` installs or updates selected store applications.
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

## Global Connect Store

The launcher adds a **Global Connect Store** entry only when the active launcher profile contains `enableGlobalConnectStore: true`.

1. `GET /v1/devices/{serial}/store` resolves the device catalog with `Authorization: Device <device-token>`.
2. Devices without a group use the bank-general store; grouped devices use their group store.
3. The service returns only active application versions compatible with the inventory device model.
4. If the bank disables device groups, catalog resolution falls back to the bank-general store even when the device still has a group ID.
5. `POST /v1/devices/{serial}/store/apps/{versionId}/download` authorizes the selected version and returns a signed URL.
6. xTMSAgent verifies size and SHA-256 before invoking the installer.

Store catalog and download endpoints use the same device token as the other HTTPS device endpoints; they do not use the bank onboarding API key.

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

Release **2.1.2.73 normal** includes automatic startup recovery. The guard records
attempts in attachBaseContext before providers, counts uncaught startup exceptions
and matching Android CRASH/CRASH_NATIVE/ANR exit records, and latches recovery after
three failures within ten minutes. Android exit records are matched to the prior
PID, package process, attempt time and build; a failure seen by both the exception
handler and Android is counted once. Ordinary restarts, updates, low-memory kills,
and unexplained incomplete attempts are not counted as crashes. A responsive
60-second normal startup clears the failure history; entering the launcher
rearms this stability timer. The guard does not kill a hung process: ANRs/native
crashes are detected from Android records on the next process start. Android may
require the user to reopen the app if it suppresses automatic relaunch.

HOME/launcher entry now uses a minimal StartupActivity, which routes to recovery
before loading MainActivity when the guard is latched. WorkManager initialization
is deferred until normal bootstrap. Automatic recovery skips SDK/credential/TMS
startup, suspends normal background components and MainActivity, preserves state
and logs, and retains device ownership. Recovery remains latched across updates
and reboot until the operator confirms Retry normal startup. That permits one
normal initialization attempt; another startup failure re-enters recovery, while
a stable start clears the retry guard. Component states are restored for the
retry, and the existing explicit device-owner-removal opt-out is preserved.
The dedicated `-PxtmsRecovery=true` build remains available and has no HOME entry.

Release **2.1.2.72 recovery** removes all HOME intent registration from the
recovery APK, including the normal MainActivity declaration inherited from the
main manifest. Recovery remains accessible from its standard launcher icon.
Startup clears this package's normal preferred-activity defaults; recovery logs
now include actual device-admin state, resolved HOME component, and available
HOME candidates. The 11:33 removal log and 11:36 restart confirm deviceOwner=false
on release 71; ordinary Home selection is distinct from device ownership. The
last immediate post-removal admin state was still true, so it is checked again.

Release **2.1.2.71 recovery** adds an operator-confirmed Remove device owner
action. It first persists an automatic-enrollment opt-out, then attempts to clear
the agent's persistent Home preference and its own user restrictions, calls
Android clearDeviceOwnerApp for this package, and verifies actual owner state.
Remaining active-admin removal is requested after ownership is gone. The outcome
and cleanup errors are recorded in log_today.txt; returning/restarting the recovery
screen shows live owner state. This does not reset the device or erase app data.
The normal provisioner honors the saved opt-out on subsequent normal releases;
automatic enrollment must be explicitly restored before provisioning again.

Release **2.1.2.70** adds `/sdcard/xTMSAgent/log_today.txt` for diagnostic setup,
startup, and caught/uncaught exception events in both normal and recovery startup.
The fixed current-day file is mirrored alongside `log_YYYY-MM-DD.txt` using the
device's local date; on the first entry of a new day the current file starts that
day's contents and earlier dated files remain. Private daily files retain entries
when public storage cannot be written; granting All files access and returning
publishes the current day's backlog. The recovery screen includes the permission
shortcut and reports actual publication failures. Recovery exports also add a
bounded historical evidence excerpt to the daily log; the full export remains
available in Downloads. This is application diagnostics, not device-wide logcat.
The 70 artifact delivered during crash investigation is the recovery build;
normal startup remains disabled there until the cause is established.

Release **2.1.2.69 recovery** is built with `-PxtmsRecovery=true`. Its release
manifest substitutes an independent RecoveryApplication and native Android
RecoveryActivity, disables the normal launcher, TMS/remote services and receivers,
and removes AndroidX automatic initialization. It preserves the admin receiver
identity and existing data. Recovery startup clears this package's persistent
HOME preference. The recovery export reads saved diagnostics and crash events
without running the vendor inspector, and adds Android's own app process-exit
metadata and available bounded traces. No normal TMS operation is available in
this temporary build. Recovery cancels this application's scheduled jobs and
suspends WorkManager component overrides, preserving their earlier values. A
subsequent normal release restores those component states during application
startup. Other component disables are manifest-only.

Release **2.1.2.68** is an N6ProLite recovery rollout following a reported startup
crash loop and missing App info overflow menu. It clears this app's device-owner
persistent HOME preference before SDK startup on N6ProLite and suppresses
reapplying it, including after boot/update. Operators can choose a launcher through
Android's Home app settings; device ownership is retained. Other models retain
their existing HOME policy. The reported crash's root cause is not yet established.
The release guards the early serial-number SDK call, contains remote-setup errors,
defers setup dialogs until resumed, and retains exception messages/causes.
App info guidance now offers top-level Android Settings as an alternative to the
direct shortcut. Neither menu visibility nor firmware UI behavior is assumed.

Release **2.1.2.67** guides manual remote-control setup. On Android 13+ when
accessibility is disabled and restricted-settings approval cannot be confirmed,
the prompt opens this app's App info page and explains the overflow-menu approval.
Returning offers accessibility settings; the operator can revisit App info or
defer setup. Already-enabled accessibility skips the prompt. Settings navigation
and actual accessibility state on return are retained in diagnostics. Opening or
returning from App info is never counted as approval. Android does not expose a
public intent to directly open the Allow restricted settings confirmation dialog.

Release **2.1.2.66** adds an Android 13 N6ProLite device-owner trial for approving
Restricted Settings before accessibility provisioning. Retry device setup logs
the approval state, hidden-method availability, exceptions, and verified result;
its existing automatic Downloads export includes these events. Already-approved
devices are detected without rewriting the approval. The trial still needs
validation on a restricted production installation; accessibility activation and
an actual Portal remote-control session must be verified separately.

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


## Local firmware installation

Configuration → Local Install (existing password protection) now offers Application
(APK) and Firmware (ZIP). Firmware support accepts signed Android OTA ZIPs with
`META-INF/com/android/metadata` and a payload or update-binary. It checks
`pre-device` against `Build.DEVICE`, optional incremental base version/fingerprint,
and verifies the package against the terminal’s trusted OTA certificates using
`RecoverySystem.verifyPackage`. Unsigned ZIPs, mismatched models, APKs, missing
metadata and unsupported vendor archive formats are rejected without dispatch.

After validation, the operator confirms the target model/version and possible
restart. At least 30% battery is required. The agent invokes Nexgo
`Platform.updateFirmware(path)`; it does not extract or execute ZIP contents.
The SDK has no completion callback and its implementation can swallow service
exceptions, so the UI only reports that the update was requested. The system
updater is responsible for installation, anti-rollback policy and reboot.

Firmware is staged in the app’s external files `local-firmware` directory with a
4 GiB input limit and storage reserve. Canceled/invalid selections are removed;
submitted files are retained for asynchronous updater access across reboot.
This change affects local installation only; remote firmware task handling is
unchanged. No firmware was flashed as part of development validation.
