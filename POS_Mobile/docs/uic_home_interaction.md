# UIC Home ↔ LATAM Payment Application Interaction

This document describes the inter-process communication (IPC) protocol between the **UIC Home** launcher application and the **LATAM Payment Application**. All communication uses Android Broadcast Intents.

---

## Overview

UIC Home is the central launcher and device management agent on Nexgo POS terminals. It:

- Connects to the TMS (Terminal Management System) server via MQTT
- Downloads terminal configuration (TMS_Database JSON) on behalf of payment apps
- Coordinates application updates received from the TMS server
- Discovers installed payment applications dynamically at runtime

The payment app is a passive consumer: it requests parameters at startup if none are cached, responds to parameter pushes, and negotiates install windows with UIC Home before allowing an APK update to proceed.

---

## Intent Actions Reference

| Action | Direction | Purpose |
|--------|-----------|---------|
| `com.uic.home.ACTION_REQUEST_PARAMS` | App → Home | Request current TMS parameters |
| `com.uic.home.ACTION_PARAMS_READY` | Home → App | Deliver TMS parameters via URI |
| `com.uic.home.ACTION_PARAMS_FAILED` | Home → App | Signal parameter download failure |
| `com.uic.home.ACTION_PRE_INSTALL_CHECK` | Home → App | Ask if app is idle and ready for update |
| `com.uic.uicpaymentapp.ACTION_PRE_INSTALL_RESPONSE` | App → Home | Consent or defer update |
| `com.uic.home.ACTION_PRE_INSTALL_DISMISS` | Home → App | Cancel a pending install task |

---

## 1. Parameter Request Flow

### Trigger

On startup, `UICApplication.onCreate()` checks whether a valid `TMS_Database` is already loaded. If `tmsDatabase.Terminal` is empty (first run or cache cleared), the app initiates a parameter request.

### Step 1 — App requests parameters

**Sender:** `UICApplication.requestParamsFromUicHome()`  
**Action:** `com.uic.home.ACTION_REQUEST_PARAMS`  
**Type:** Explicit broadcast — sent individually to each discovered UIC Home package

The app uses `packageManager.queryBroadcastReceivers()` to discover all installed variants of UIC Home (e.g., `com.uic.home.banpais`, `com.uic.home.banistmo`, `com.uic.home.uic`) and sends one explicit broadcast to each.

```kotlin
// Pseudo-code — UICApplication.requestParamsFromUicHome()
val receivers = packageManager.queryBroadcastReceivers(
    Intent("com.uic.home.ACTION_REQUEST_PARAMS"), 0
)
for (receiver in receivers) {
    val intent = Intent("com.uic.home.ACTION_REQUEST_PARAMS")
    intent.setClassName(receiver.activityInfo.packageName, receiver.activityInfo.name)
    sendBroadcast(intent)
}
```

### Step 2 — UIC Home delivers parameters

**Sender:** UIC Home  
**Receiver:** `TmsParamsReceiver` (registered in `AndroidManifest.xml`)  
**Action:** `com.uic.home.ACTION_PARAMS_READY`

| Extra | Type | Description |
|-------|------|-------------|
| `params_uri` | String | `content://` FileProvider URI pointing to decompressed TMS JSON |

UIC Home downloads the current TMS_Database JSON from the server, writes it to a shared FileProvider path, and broadcasts the URI.

### Step 3 — App processes parameters

`TmsParamsReceiver.onReceive()` handles the delivery:

1. Opens `params_uri` via `ContentResolver.openInputStream()`
2. Deserializes JSON into `TMS_Database`
3. Validates `StructVersion` matches the expected schema version
4. **If unsettled transactions exist:** stores JSON in SharedPreferences (`tms_pending_update_prefs`, key `pending_param_json`) for deferred application
5. **If terminal is idle:** calls `UICApplication.applyTmsUpdate(tmsDatabase)` immediately
6. Sets `paramsReadyFlow = true` → triggers UI transition in `MainActivity`

### Error path

**Action:** `com.uic.home.ACTION_PARAMS_FAILED`

| Extra | Type | Description |
|-------|------|-------------|
| `error_message` | String | Human-readable error description |

`TmsParamsReceiver` re-broadcasts `ACTION_PARAMS_DOWNLOAD_FAILED` locally. `MainActivity` displays an error screen with a retry option.

---

## 2. Parameter Push Flow (Server-Initiated)

When TMS configuration changes on the server, UIC Home receives an MQTT notification and proactively downloads the updated `TMS_Database` without waiting for a request from the payment app. It then delivers the new parameters using the same `ACTION_PARAMS_READY` broadcast described in Step 2 above.

The payment app handles this identically to a requested delivery. No action from the payment app is required to receive pushed updates.

```
TMS Server ──MQTT──► UIC Home ──ACTION_PARAMS_READY──► Payment App
```

---

## 3. Application Update Flow

When the TMS server publishes a new APK for the payment app, UIC Home downloads it and initiates a coordinated install. Because installing an APK terminates the running process, the payment app must confirm it has no open transactions or active operation before UIC Home proceeds.

### Step 1 — UIC Home requests consent

**Sender:** UIC Home  
**Receiver:** `TmsAppUpdateReceiver` (registered in `AndroidManifest.xml`)  
**Action:** `com.uic.home.ACTION_PRE_INSTALL_CHECK`

| Extra | Type | Description |
|-------|------|-------------|
| `pkg` | String | Package name of the APK to install (e.g., `com.uic.uicpaymentapp`) |
| `ver` | String | Human-readable version string (e.g., `"2.03"`) |
| `verCode` | Int | Version code (integer build number) |
| `senderPkg` | String | UIC Home package name — used to route the response |

### Step 2 — App evaluates readiness

`TmsAppUpdateReceiver.onReceive()` runs a coroutine on `Dispatchers.IO`:

1. Checks `PendingUpdateManager.isOperationInProgress` — true if a card transaction or settlement is active
2. Queries `transactionRepository.getOpenAndNeedTipTransactionNumber()` — count of unsettled or tip-pending transactions
3. Derives: `canProceed = !inProgress && unsettledCount == 0`

**If blocked:** calls `PendingUpdateManager.storePendingAppUpdate(pkg, ver, verCode)` to persist the pending install request in SharedPreferences.

### Step 3 — App responds

**Sender:** `TmsAppUpdateReceiver`  
**Receiver:** UIC Home (targeted via `senderPkg`)  
**Action:** `com.uic.uicpaymentapp.ACTION_PRE_INSTALL_RESPONSE`

| Extra | Type | Description |
|-------|------|-------------|
| `pkg` | String | Package name from the original request |
| `proceed` | Boolean | `true` = install now; `false` = terminal is busy, defer |

```kotlin
// Pseudo-code
val response = Intent("com.uic.uicpaymentapp.ACTION_PRE_INSTALL_RESPONSE").apply {
    setPackage(senderPkg)
    putExtra("pkg", pkg)
    putExtra("proceed", canProceed)
}
context.sendBroadcast(response)
```

### Step 4 — Deferred install resumption

While an update is deferred, `MainActivity` displays a periodic reminder banner. After a successful settlement batch closes:

`MainActivity` (EndOfDay completion handler):

1. Checks `PendingUpdateManager.hasPendingAppUpdate(context)`
2. If true: calls `PendingUpdateManager.notifyUicHomeCanProceed(context)`
   - Discovers all UIC Home instances via `queryBroadcastReceivers(ACTION_PRE_INSTALL_CHECK)`
   - Sends `ACTION_PRE_INSTALL_RESPONSE` with `proceed = true` to each
3. Clears the pending flag

### Step 5 — Install task cancellation

If UIC Home cancels the pending install (e.g., update superseded or user cancelled from Home UI):

**Action:** `com.uic.home.ACTION_PRE_INSTALL_DISMISS`

`TmsAppUpdateReceiver` clears the stored pending app update state, removes the reminder banner.

---

## 4. Sequence Diagrams

### 4.1 Startup Parameter Request

```
Payment App                     UIC Home                    TMS Server
    │                               │                             │
    │── ACTION_REQUEST_PARAMS ──►   │                             │
    │                               │── HTTP GET /tms_params ──►  │
    │                               │◄── TMS_Database JSON ───    │
    │                               │  (write to FileProvider)    │
    │◄── ACTION_PARAMS_READY ───    │                             │
    │    (params_uri)               │                             │
    │  [apply TMS_Database]         │                             │
```

### 4.2 Server-Pushed Parameter Update

```
Payment App                     UIC Home                    TMS Server
    │                               │                             │
    │                               │◄── MQTT: params changed ─   │
    │                               │── HTTP GET /tms_params ──►  │
    │                               │◄── TMS_Database JSON ───    │
    │◄── ACTION_PARAMS_READY ───    │                             │
    │    (params_uri)               │                             │
    │  [apply or defer]             │                             │
```

### 4.3 Application Update — Immediate

```
Payment App                     UIC Home                    TMS Server
    │                               │                             │
    │                               │◄── MQTT: new APK ────────   │
    │                               │── HTTP GET /apk ──────────► │
    │◄── ACTION_PRE_INSTALL_CHECK ─ │                             │
    │    (pkg, ver, verCode)        │                             │
    │  [check: idle + no open tx]   │                             │
    │── ACTION_PRE_INSTALL_RESPONSE►│                             │
    │    (proceed=true)             │                             │
    │                               │  [install APK → app restarts]
```

### 4.4 Application Update — Deferred Until Settlement

```
Payment App                     UIC Home
    │                               │
    │◄── ACTION_PRE_INSTALL_CHECK ─ │
    │    (pkg, ver, verCode)        │
    │  [check: operation in progress│
    │   or unsettled transactions]  │
    │── ACTION_PRE_INSTALL_RESPONSE►│
    │    (proceed=false)            │
    │  [store pending, show banner] │
    │                               │
    │  ... settlement completes ... │
    │                               │
    │── ACTION_PRE_INSTALL_RESPONSE►│  (discover all UIC Home instances)
    │    (proceed=true)             │
    │  [clear pending, hide banner] │
    │                               │  [install APK → app restarts]
```

---

## 5. TMS_Database Structure

The parameter payload is a single JSON object of type `TMS_Database`:

```json
{
  "ProfileName": "LATAM_PRODUCTION",
  "StructVersion": "2.0.0",
  "Terminal": [ { ... } ],
  "Acquirer": [ { ... } ],
  "Issuer": [ { ... } ],
  "AIDtab": [ { ... } ],
  "CAKeyGrp": [ { ... } ],
  "BINList": [ { ... } ],
  "CardRange": [ { ... } ],
  "Menus": [ { ... } ],
  "CustomTransactions": [ { ... } ],
  "ConfigFiles": [ { ... } ],
  "...": "..."
}
```

`StructVersion` must match the schema version compiled into the payment app. Mismatched versions cause the payload to be discarded and an error to be displayed.

### Key Terminal Parameters

| Field | Type | Description |
|-------|------|-------------|
| `TermID` | String | Terminal identifier |
| `MerchantTitle1–3` | String | Merchant name lines for receipts |
| `AdminServerIPTab` | String | Host IP table reference |
| `EnableSale` / `IsRefund` | Boolean | Feature enablement flags |
| `ApplyTax` / `ApplyTax2` | Boolean | Tax computation toggles |
| `MaxTip` / `TipMaxAdjusts` | String/Int | Tip rules |
| `IsSettle` | Boolean | Settlement enabled |
| `AutoSettleStartHour` / `AutoSettleEndHour` | Int | Auto-settle window |
| `Menu01–10` | String | Custom menu slot references |
| `CAKeyGrp` | String | CA public key group reference |
| `Receiptformat` | String | Receipt template identifier |
| `PrintCustomerCopy` | Boolean | Print customer copy flag |

Full field list: `app/src/main/java/com/uic/tms/profile/TMS_Terminal.kt`

---

## 6. Persistence

| Data | Storage | Key / Path |
|------|---------|------------|
| Active TMS_Database | File | `context.filesDir/tms_params.json` |
| Deferred param update | SharedPreferences | `tms_pending_update_prefs` → `pending_param_json` |
| Deferred app update | SharedPreferences | `tms_pending_update_prefs` → `app_update_pkg`, `app_update_ver`, `app_update_ver_code` |

---

## 7. AndroidManifest Declarations

```xml
<!-- Receivers -->
<receiver android:name=".tms.TmsParamsReceiver" android:exported="true">
    <intent-filter>
        <action android:name="com.uic.home.ACTION_PARAMS_READY" />
        <action android:name="com.uic.home.ACTION_PARAMS_FAILED" />
    </intent-filter>
</receiver>

<receiver android:name=".tms.TmsAppUpdateReceiver" android:exported="true">
    <intent-filter>
        <action android:name="com.uic.home.ACTION_PRE_INSTALL_CHECK" />
        <action android:name="com.uic.home.ACTION_PRE_INSTALL_DISMISS" />
    </intent-filter>
</receiver>

<!-- Package visibility (Android 11+) -->
<queries>
    <intent>
        <action android:name="com.uic.home.ACTION_REQUEST_PARAMS" />
    </intent>
    <intent>
        <action android:name="com.uic.home.ACTION_PRE_INSTALL_CHECK" />
    </intent>
</queries>
```

---

## 8. Key Source Files

| File | Role |
|------|------|
| `UICApplication.kt` | Startup: loads cached params, triggers `requestParamsFromUicHome()` |
| `TmsParamsReceiver.kt` | Receives `ACTION_PARAMS_READY` / `ACTION_PARAMS_FAILED` |
| `TmsAppUpdateReceiver.kt` | Receives `ACTION_PRE_INSTALL_CHECK` / `ACTION_PRE_INSTALL_DISMISS` |
| `PendingUpdateManager.kt` | SharedPreferences state for deferred updates; broadcasts resumption |
| `TmsParamsViewModel.kt` | State machine for the parameter-waiting screen |
| `MainActivity.kt` | EndOfDay handler that triggers deferred update resumption |
| `TMS_Database.kt` | Root deserialization class for the TMS JSON payload |
| `TMS_Terminal.kt` | 160+ terminal configuration fields |
