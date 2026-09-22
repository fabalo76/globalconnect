# Device profile firmware support — 2.1.2.87

Validated 2026-09-21 on CT20 (`5dacb5c7`, firmware v1.2.1) and N96 (`N96`,
firmware v1.3.5). Both terminals received an in-place debug APK update and Android
reports xTMSAgent as device owner. CT20 is a distinct recognized model with the
CT20 family screen and asset constraints, and owner command `702108171`.
N96 owner command remains `902108171`.

## Portal-to-terminal mapping

The existing schema version 1 gains five optional, strictly Boolean network
fields. Omitted fields remain unmanaged. The API, editor and terminal share
the accepted field set. The terminal checks every requested setting before
writing any; unsupported firmware, unavailable capabilities, missing ownership
or unsupported timeout values skip only the affected fields. Each skip is
reported as `skipped_<reason>`, while supported fields continue. Skips do not
fail the task: a wholly unsupported profile is a successful no-op with all
fields reported as skipped. Invalid payloads still fail validation. A runtime failure stops subsequent writes; already
verified writes are not rolled back. Each setting reports its own result.

| Profile setting | CT20 v1.2.1 command | N96 v1.3.5 command |
| --- | --- | --- |
| automaticTime | 702306151 | 902306151 |
| automaticTimeZone | 702306152 | 902306152 |
| timeZone | 702306153 | 902306153 |
| locationEnabled | 702310194 | 902310194 |
| wifiEnabled | 702310192 | 902310192 |
| bluetoothEnabled | 702310193 | 902310193 |
| ethernetEnabled | 702210181 | 902210181 |
| mobileDataEnabled | Unsupported | 902506171 |
| airplaneModeEnabled | Unsupported | 902506172 |
| automaticBrightness, brightnessPercent, screenTimeoutSeconds | Android device-owner API | Android device-owner API |
| mediaVolumePercent, alarmVolumePercent, notificationVolumePercent | Android AudioManager | Android AudioManager |

Firmware commands require the matching model, actual command base and exact
PSS APK SHA-256. CT20P is accepted only with the inspected CT20 PSS binary.
Unknown firmware retains existing Android policy fallbacks for time/location
but cannot use these network commands. Status telemetry includes the
`firmwareProfileSettings` list; it lists vendor-backed fields, not every Android
policy capability.

* CT20 base 70000000, PSS SHA-256:
  `8EEBD367738001E6F04974CCB7B78DBDDF7297DF74DE6CA029B7583147D2DA3A`
* N96 base 90000000, PSS SHA-256:
  `B8CB51C1CF3D722EA6753519DB28A3B9F528CA8FECFF4EF809DB11A996F49753`

Boolean payloads use one byte, 0 or 1. Time zones use raw UTF-8. The SDK call
order is command/input/other/output; the Binder order differs. Radio/location
setters are followed by a separate query because setter output may echo the
request. Time, time-zone and Ethernet readback uses the corresponding Android
setting/property. Readback waits up to ten seconds. Ethernet verifies the
administrative setting, not physical link or internet reachability.

N96 vendor brightness has a faulty result/getter and the timeout command uses
a resource-array index. Both remain on Android policy APIs. Supported timeout
values are restricted to paired vendor resource entries and values.

Airplane=true cannot coexist with Wi-Fi/Bluetooth/mobile-data=true. Leaving
airplane mode executes before other settings; entering it executes last.
Network changes can disconnect MQTT: existing persisted task results and ACK
retries report the outcome after reconnection. Profiles do not expose arbitrary
vendor command IDs, shell commands, credentials, or Settings passwords.

CT20/CT20P/N96 kiosk methods use true=disable. The agent verifies HOME, recents
and swipe disable properties instead of trusting the vendor's Boolean return.

## Validation and rollout

* Android: 117 unit tests passed and debug assembly succeeded for 2.1.2.87.
* API/core: 46 core tests and 17 device-profile endpoint integration tests
  passed; integration tests used an isolated local PostgreSQL database.
* Portal: production build passed. The real editor, with mocked authentication
  and profile storage, passed 393/768/1366/1920-pixel checks in en/es/zh-TW;
  radio conflict controls passed. This is not a cloud end-to-end test.
* Live on both: device ownership; changed brightness and timeout; changed and
  restored time zone; automatic time/time zone; location; same-state Wi-Fi and
  Bluetooth; kiosk lock/unlock with all four properties checked. Invalid
  Boolean payloads were rejected.
* Live CT20 in 2.1.2.86: Ethernet disabled state verified. The former whole-profile
  rejection behavior is superseded by the skip behavior in 2.1.2.87.
* Live N96: mobile-data enabled and airplane disabled state verified. N96
  Ethernet was not live-tested because its baseline setting was absent.
* Network-disconnecting transitions and reboot persistence were not exercised.
  Display/time-zone test changes were restored; both devices were initially
  and finally unlocked. No app data was cleared.
* Existing lint debt remains: 131 errors and 178 warnings, beginning in
  StartupRecoveryGuard. The touched profile API-level error was fixed.

2.1.2.87 was installed and tested on both terminals. Mixed profiles applied and
verified brightness while skipping an unsupported 17-second timeout; CT20 also
skipped N96-only mobile data. Entirely unsupported profiles completed as no-ops.
Invalid Boolean payloads still failed without changing brightness. All display
test settings were restored. Evidence:
`../tmp/ct20-n96-profile-validation/skip-results-v87.json`.

The shell-only debug receiver requires Android's DUMP permission and is absent
from release builds. It invokes the production profile/kiosk paths without
creating cloud tasks or sending cloud ACKs.

Local evidence: `../tmp/ct20-n96-profile-validation/`,
`C:/tmp/ct20-profile-ui/`, and `C:/tmp/ct20-profile-api-tests.log`.
The portal/API implementation has not been deployed. Deploy API and portal
together and roll out the appropriately signed release agent before using
network settings on other terminals. Older agents reject unknown fields.
