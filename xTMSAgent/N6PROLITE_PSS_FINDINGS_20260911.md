# N6ProLite PSS analysis — exported terminal APK

Source: `E:\xTMSAgent\xtmsagent-pss-20260911-225949-846.zip`.
Extracted/decompiled under `C:\tmp\NexgoN6ProLite-analysis`.

The base APK is 23,361,158 bytes with SHA-256
`49CE4C88A486E01582B1819C63A583E0AFB4678D65CAD0D0BAE97550E43F4696`.
This matches both the archive manifest and terminal diagnostics. Package:
`com.xgd.possystemservice`, versionName 13, versionCode 33. Terminal firmware is
N6ProLite v1.1.1, Android 13, command base 90000000, screen 720x1440.

## Confirmed owner support

`GeneralMethodImp.java:110` computes owner command as
`((210817 + CMD_BASE) * 10) + 1`, giving 902108171.
`SystemInterfaceService.java:149` dispatches it. The handler at
`GeneralMethodImp.java:745` uses the Android 13 device-policy signature and
registers the admin before assigning ownership. The exported diagnostics show
this succeeding again in 2.1.2.64: SDK result 0, actual admin and owner true.
The log begins with admin/owner false, so it does not establish that ownership
persisted from the previous installation; reinstall/reset history is unknown.

## Accessibility command routing

`SystemManager.java:481` implements `executeCmd` with two paths:

1. Strings beginning `settings put` or `pm clear` are forwarded to
   `CppService.shellcmd` (native service `xgddata`). Its integer result is ignored,
   and the wrapper returns true unless a Java exception occurs.
2. Other strings are passed to `Runtime.exec(String)`. The wrapper returns true
   without waiting for the process or reading its exit status/stdout/stderr.

`IDataService.java:19` confirms that `shellcmd` returns an integer. The actual
native service implementation is not in this APK, so its error is not known.
Thus the previous accepted=true result did not prove that secure settings changed.

The APK manifest declares shared identity `android.uid.system` and requests
`android.permission.WRITE_SECURE_SETTINGS`. This supports a bounded trial via
the PSS process's direct command-execution route, but does not prove that SELinux,
the settings executable, or restricted-settings policy will permit the operation.

No dedicated accessibility setter was found in the exposed general-command
dispatcher or typed system-manager interface. The bundled settingslib
`AccessibilityUtils` contains an internal helper, but it is not an exported PSS
API and has no discovered caller. It cannot be treated as an available SDK method.

JADX reported five decompilation errors. Relevant command-dispatch and executeCmd
methods were recovered; unrelated failed methods include card statistics, a touch
callback, certificate information, and advertising. No absolute claim is made
about all native or omitted implementation paths.

## Release 2.1.2.65 trial

For N6ProLite only, use fixed commands starting
`/system/bin/settings --user 0 put secure ...` through the existing PSS interface.
This does not match the native-forwarding prefix, so the examined PSS takes its
Runtime.exec path. Arguments are restricted to valid component-list characters;
no arbitrary portal command facility is added. Other models retain their current
command construction. Global accessibility and service-list state are both checked
before reporting configuration success; connection state remains in diagnostics.

The Android Restricted Settings dialog remains a separate observation. Manual
approval, if offered by the terminal's App info menu, is still a valid diagnostic
step. This release does not modify that approval policy. The new command route
requires testing on the terminal; it is not yet a verified accessibility fix.

## Manual approval result and release 2.1.2.66 trial

The 232535 and 232539 diagnostics from September 11 confirm deviceOwner=true,
accessibilityEnabled=true, accessibilityConnected=true and global accessibility=1
after the operator selected Allow restricted settings and then enabled the service.
This verifies service connection, not a complete Portal remote-control session or
automatic accessibility provisioning.

Android 13 AOSP Owners.pushToAppOpsLocked registers device-owner UIDs as well as
profile-owner UIDs. AppOpsService.enforceManageAppOpsModes permits those UIDs to
change modes within their own user. Settings implements the manual approval by
setting android:access_restricted_settings to MODE_ALLOWED. The AppOpsManager
setter remains a hidden API: the OEM runtime may block lookup or invocation.

Release 66 attempts that existing setter through reflection for N6ProLite/API 33
device owners only, for the agent's own package and UID. It does not relax hidden
API enforcement. It changes only MODE_IGNORED, preserves existing approvals, and
verifies the resulting mode before logging approved_by_device_owner. Lookup,
invocation errors (including their causes), and before/after state are recorded
in the existing persistent/exportable retry log. Diagnostics now include
management.restrictedSettingsState. Other firmware/model combinations do not
receive this trial. Approval remains separate from enabling accessibility, which
continues through the existing NEXGO route and state verification.

On an already approved device, methodAvailable only verifies method lookup;
already_allowed is not proof that the app can grant approval. Complete validation
requires a still-restricted installation; do not uninstall the working device
owner just to manufacture that state.

Source references:
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/devicepolicy/java/com/android/server/devicepolicy/Owners.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/appop/AppOpsService.java
- https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android13-release/src/com/android/settings/applications/appinfo/AppInfoDashboardFragment.java

## Release 66 clean-install result (2026-09-12)

The 090358 and 090400 diagnostics after the operator's factory reset show the
owner command successfully provisioning the agent (SDK result 0, confirmed owner).
Accessibility remains disabled and disconnected. The PSS export manifest at
090403 reports the same base APK SHA-256 as the previous export.

Restricted-settings method lookup succeeds, but the pre-write checkOperation
throws SecurityException: `verifyIncomingOp: uid 10091 does not have
android.permission.MANAGE_APPOPS.` No approval setter invocation took place.

Correction to the earlier feasibility assessment: Android 13 AppOpsService
setMode calls both enforceManageAppOpsModes (which has the owner exception)
and verifyIncomingOp (which separately requires MANAGE_APPOPS for restricted
operations). The owner exception alone is therefore insufficient. The observed
read denial matches that source. Skipping the failed read is not a supported
solution; the standard setter has the same additional permission gate.

Manual approval and activation remain the verified path on this firmware.
Unattended approval would require a supported privileged NEXGO integration or
firmware provisioning change; no such dedicated interface has been verified.
Do not describe methodAvailable=true as successful invocation or authorization.
