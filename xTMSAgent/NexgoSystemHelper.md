# NEXGO SystemServiceHelper — Reverse-Engineering Findings

Extracted from `nexgoSystemService_sdk_7.1_20201207.jar` (bundled inside
`nexgo-smartpos-sdk-v3.08.010_20250528.aar`) and corroborated against
`XTMS_5.6.0_20250926_release_N96_signed.apk` from the N96 firmware package.

---

## Entry Point

```kotlin
val helper = SystemServiceHelper.getInstance()
helper.init(context)
// or with a ready-callback:
helper.init(context, object : OnPlatformInitListener {
    override fun onPlatformInitResult(resultCode: Int) { /* service bound, safe to call getters */ }
})
```

`init()` binds to `com.xgd.smartpos.service.SYSTEM_APIMANAGER`
(`SystemInterfaceService`). All `get*()` accessors return **null** until the
bind completes — this is the root cause of the `ISystemUIOperate` NPE in
`ApplyDeviceBars()`.

---

## Interfaces

### `ISystemUIOperate` — `helper.getSystemUIManager()`

Controls the on-screen system chrome.

| Method | Signature | Notes |
|--------|-----------|-------|
| `enableControlBar` | `(boolean) → boolean` | Show/hide the bottom control bar |
| `enableMessageBar` | `(boolean) → boolean` | **Show/hide notification shade** — `false` blocks USB mode switching via drag-down |
| `enableHome` | `(boolean) → boolean` | Enable/disable the Home button |
| `enableRecv` | `(boolean) → boolean` | Enable/disable the Recents button |
| `enablePowerKey` | `(boolean) → boolean` | Enable/disable the Power key |

> **USB file-transfer block (no device-owner required):**
> `helper.getSystemUIManager().enableMessageBar(false)` prevents the operator
> from pulling down the notification shade where the USB mode selector lives.

---

### `ISystemManager` — `helper.getSystemManager()`

Privileged system operations.

| Method | Signature | Notes |
|--------|-----------|-------|
| `setLauncher` | `(String pkg, boolean) → void` | **Register any app as default launcher without device-owner or ADB** |
| `setSelfStartingApp` | `(String, String, String) → void` | Persist app auto-start across reboots |
| `executeCmd` | `(String cmd) → boolean` | Run a shell command via system service |
| `executeRootCMD` | `(String, String, String, String) → boolean` | Run a root shell command — can run `dpm set-device-owner` |
| `blockFunctionKeys` | `(int keyId, boolean block) → boolean` | Block hardware function keys |
| `setAppEnabled` | `(String pkg, int state) → void` | Enable or disable an installed app |
| `disableAppCommunication` | `(List<String> pkgs) → void` | Block inter-app communication for given packages |
| `enableNetPkgName` | `(String pkg, int type) → int` | Grant network access per-package |
| `disableNetPkgName` | `(String pkg, int type) → int` | Revoke network access per-package |
| `SetUsbCdcEnable` | `(boolean) → void` | Enable/disable USB CDC (serial) |
| `SetDataEnable` | `(boolean) → void` | Enable/disable mobile data |
| `setAirPlaneModeOn` | `(boolean) → void` | Toggle airplane mode |
| `switchDataNetwork` | `(int type) → int` | Switch mobile data network type |
| `setSysTime` | `(String time) → boolean` | Set system clock |
| `reboot` | `() → void` | Reboot device |
| `recovery` | `() → void` | Boot to recovery |
| `reset` | `(int type) → boolean` | Factory reset |
| `updateSystem` | `(String path, int) → void` | OTA firmware update |
| `updateFirmware` | `(int, String) → String` | Firmware update variant |
| `takeScreenshot` | `(String path) → String` | Capture screen to file |
| `getStoragePath` | `() → String` | Get device storage path |

---

### `IAppManager` — `helper.getAppManager()`

Silent app install/uninstall (the method XTMS uses — bypasses user prompts).

| Method | Signature | Notes |
|--------|-----------|-------|
| `installApp` | `(String apkPath, IAppInstallObserver, String) → void` | Silent install; result via observer |
| `installAppReboot` | `(String apkPath, IAppInstallObserver, boolean reboot) → void` | Install then optionally reboot |
| `uninstallApp` | `(String pkg, IAppDeleteObserver) → void` | Silent uninstall |
| `getUsageStats` | `(String pkg) → List<UsageStats>` | App usage statistics |
| `getAppUseInfo` | `(long from, long to) → List<UsageInfo>` | App usage by time range |

> This is what the XTMS agent uses instead of `PackageInstaller`. Likely
> explains why the NEXGO SDK `platform.installApp()` has fewer restrictions
> than Android's standard installer.

---

### `INetwork` — `helper.getNetworkManager()`

| Method | Signature | Notes |
|--------|-----------|-------|
| `setDataEnabled` | `(boolean) → void` | Mobile data on/off |
| `setDataRoamingEnabled` | `(boolean) → void` | Data roaming on/off |
| `setNetworkSelectionModeAutomatic` | `() → void` | Auto network selection |
| `selectNetworkManually` | `(String, String, String, String) → void` | Manual network selection |
| `scanOperator` | `() → void` | Trigger operator scan |

---

### `IDeviceManager` — (not exposed via `SystemServiceHelper` directly)

Accessed via `ICloudService.getManager(int)`.

| Method | Notes |
|--------|-------|
| `getDeviceInfo()` | Returns `Bundle` with device info |
| `getHardWireVersion()` | Hardware version string |
| `getRomVersion()` | ROM version string |
| `getCertInfo()` | Certificate bundle |
| `getEmmcId()` / `getCpuId()` | Hardware identifiers |
| `mountUSB()` / `unmountUSB()` | USB storage mount control |
| `regisiterTouchListener(ITouchObserver)` | Global touch event listener |

---

### `ISystemTms` — TMS-facing interface

| Method | Notes |
|--------|-------|
| `updateSystem(String, int)` | TMS-triggered OTA |
| `getSystemVersion()` | Firmware version string |
| `getSn()` | Device serial number |

---

### `ICloudService` — Root service binder

The actual bound service. All sub-managers are obtained through it.

| Method | Notes |
|--------|-------|
| `getManager(int type)` | Returns `IBinder` for APP_MANAGER(0), SYSTEM_MANAGER(1), SYSTEMUI_OPRERATE(2), SYSTEMNETWORK_OPRERATE(3) |
| `getServiceSdkVersion()` | SDK version of the running system service |
| `generalMethod(int, byte[], byte[], byte[])` | Undocumented command dispatcher |
| `generalMethodWithCallback(...)` | Same with async callback |

---

## `executeGeneralMethod` — Undocumented Commands

```kotlin
helper.executeGeneralMethod(cmdId, inParam1, inParam2, outResult)
// or
helper.executeGeneralMethodWithCallback(cmdId, inParam1, inParam2, outResult, callback)
```

Known constant: `SystemServiceHelper.getCMDBASE()` returns the base offset for
command IDs. `CMD_SLEEPORWAKEUP` and `CMD_RECHARGE_CAP` are named constants
observed in the SDK. All other command IDs are undocumented — contact NEXGO
for the full command table.

---

## Action Items for xTMSAgent

### Fix `ApplyDeviceBars()` NPE
Use `init(context, listener)` and defer bar calls until `onInited`:

```kotlin
SystemServiceHelper.getInstance().init(this, object : OnPlatformInitListener {
    override fun onInited(resultCode: Int) {
        ApplyDeviceBars()   // safe — service is bound
    }
})
```

### Block USB File Transfer (no device-owner)
```kotlin
SystemServiceHelper.getInstance().getSystemUIManager()
    ?.enableMessageBar(false)
```
Disables notification shade pull-down → operator cannot open USB mode selector.

### Register as Default Launcher (no ADB)
```kotlin
SystemServiceHelper.getInstance().getSystemManager()
    ?.setLauncher(packageName, true)
```

### Manage the Factory Nexgo TMS

The factory TMS package is `com.nexgo.xtms`. xTMSAgent manages it without
uninstalling or clearing its data:

1. As Device Owner, call `DevicePolicyManager.setApplicationHidden()` to
   persist the administrator policy.
2. Call `ISystemManager.setAppEnabled()` and `executeCmd("am force-stop …")`
   so the N82 stops the persistent XTMS and MQTT services immediately.
3. Reconcile the saved administrator preference after every xTMSAgent start.

N82 validation showed that the hidden state survives reboot, prevents
`XTMSService` and its Paho MQTT service from starting, and does not stop
`com.xgd.possystemservice`. Re-enabling the package and starting
`com.nexgo.xtms/.XTMSService` restores the factory service. Because XTMS is a
firmware `PERSISTENT` app, the UI also offers a restart after disabling it in
case a firmware revision does not terminate an already-running process.

### Promote to Device Owner via Root (if available)
```kotlin
SystemServiceHelper.getInstance().getSystemManager()
    ?.executeRootCMD(
        "dpm set-device-owner $packageName/.TmsDeviceAdminReceiver",
        "", "", ""
    )
```

---

## Notes

- All interfaces extend `android.os.IInterface` (AIDL-generated).
- The service is a **system-privileged** service — only apps with the right
  SELinux context or signature can bind. XTMS works because it ships as part
  of the NEXGO firmware. xTMSAgent may need to be pre-installed as a system app
  (path 2 from the provisioning discussion) for full access.
- `ISystemUIOperate` methods that currently NPE in xTMSAgent (`enableControlBar`,
  `showNavigationBar`) do so because the service connection hasn't completed by
  the time `onCreate` calls `ApplyDeviceBars()`.
