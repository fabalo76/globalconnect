# NEXGO firmware investigation — 2026-09-11

## Supplied directory

### Updated N6 folder (subsequent user replacement)

The user replaced `N6 Firmware` after the initial inspection below. It now
contains genuine N6/N6S firmware, not the duplicate UN20 package. Its OTA metadata
identifies `pre-device=N6`, Android 10/API 29, security patch 2022-06-15. The
system properties identify `ro.product.system.model=N6`, `ro.xgd.type=N6S`,
firmware v0.4.6, built 2024-11-18.

SHA-256 comparison confirms that the replacement's system/vendor/product
compressed images, system transfer list, and bundled XTMS 5.5.1 APK match the
previously examined `C:\tmp\Nexgo_Firmware\Nexgo_N6SFirmware` package. Existing
extracted N6S sources were therefore reused for command inspection.

Replacement system compressed-image SHA-256:
`676D48550050DEDD4BC0C81BC7505308575C6CA3B023A10ECE3C7B90B2F9FB04`

`C:\tmp\NexgoN6-analysis\pss-jadx\sources\com\xgd\smartpos\systemservice\GeneralMethodImp.java:109`
defines owner command `802108171`; `SystemInterfaceService.java:103` dispatches
it and `GeneralMethodImp.java:869` implements the two-field package/receiver
payload and direct device-policy calls. This is the 80-million family, not the
N6ProLite terminal's 90-million family. The replacement does not supply the
Android 13 N6ProLite PSS implementation. The N96 `902108171` handler remains
the closest examined candidate for the N6ProLite owner test.

### Initial directory inspection (before replacement)

Inspected `C:\tmp\Nexgo Firmware\` offline. No terminal operations or firmware
installation were performed. Extracted files are in `C:\tmp\NexgoUN20-analysis`.

Both `N6 Firmware` and `UN20 Firmware` contain 112 files. Relative paths, sizes,
and SHA-256 values match for every file. These are two copies of the same package.
The N6 directory name is misleading: the system image identifies as UN20.

| Field | Extracted value |
|---|---|
| Model | `ro.product.system.model=UN20`, `ro.xgd.type=UN20` |
| Firmware | `v1.5.2` |
| Build | 2025-10-29 |
| Android | 11 / API 30 |
| Security patch | 2024-12-18 |
| OTA pre-device | `aiv8175p2_bsp` |
| PSS | `com.xgd.possystemservice`, `v1.4.4`, code 5 |
| Bundled update XTMS | `com.nexgo.xtms`, 5.6.0, code 560 |
| Owner command | `702108171` (70-million family) |

System compressed-image SHA-256:
`4B12270F0CDC11DD9BE4052A42EA3691D47E44DEA9057FF3B99EA03960090618`

Extracted PSS APK SHA-256:
`9223FF445BFD5A28AA65DBB2677156C17EE6C99C7F7F1E084AEB99AE42920158`

The PSS decompilation completed successfully. Factory XTMS decompilation produced
six errors, so absence of a method in its reconstructed source is not conclusive.

## Direct device-owner command

UN20 `GeneralMethodImp.java:141` defines `CMD_SET_DEVICEOWNER=702108171`.
`SystemInterfaceService.java:101` dispatches that command to
`GeneralMethodImp.setDeviceOwner`, implemented at line 383.

The handler parses a two-field byte payload:

`[2, packageByteLength, packageBytes..., receiverByteLength, receiverBytes...]`

It obtains Android's device-policy service, calls `setActiveAdmin`, then
`setDeviceOwner`, and sets the user provisioning state. This is a direct privileged
API path, separate from running the `dpm` command. A zero return is insufficient:
some failure paths log an error and still return zero. Always verify Android's
actual device-owner state after the call.

## Previously examined N96 and N6S evidence recovered

Older analysis exists outside the repository:

- `C:\tmp\NexgoN6-analysis\N6S_LIVE_AND_FIRMWARE_FINDINGS.md` documents N6S
  firmware v0.4.6, Android 10, and command base 80000000. N6S firmware was
  examined; the earlier conversation's SDK-only characterization was incomplete.
- `C:\tmp\NexgoN96-analysis\N96_LIVE_AND_FIRMWARE_FINDINGS.md` documents a live
  N96 v1.2.0 and supplied N96 v1.3.5, both Android 11, with command base 90000000.

Re-inspection confirms that BOTH N96 PSS implementations define and dispatch
`CMD_SET_DEVICEOWNER=902108171`. In the supplied firmware source this is at
`firmware\pss-jadx\sources\com\xgd\smartpos\systemservice\GeneralMethodImp.java:150`
and `SystemInterfaceService.java:93`; its payload implementation begins at
`GeneralMethodImp.java:664`. The live PSS definition is at
`live\pss-jadx\sources\com\xgd\smartpos\systemservice\GeneralMethodImp.java:89`.

This establishes an implemented N96 command, not a successful N6ProLite runtime
test. The existing xTMSAgent 2.1.2.62 provisioning code does not use it.

## Why shell acceptance can be misleading

UN20 `SystemManager.java:484` and N96 firmware `SystemManager.java:469` implement
`executeCmd` as follows:

- Ordinary commands call `Runtime.exec(String)` and return true without waiting
  for or checking process completion.
- Strings starting with `pm clear` or `settings put` are sent to a native service;
  the Java wrapper then returns true without verifying the requested state.
- `executeRootCMD` checks an allowed-command list and vendor authentication
  material. It is not an unrestricted root-shell interface. Empty authentication
  parameters, as used in the current app's fallback, are not a supported way to
  invoke that interface on these examined versions.

This explains how the examined firmware can report command acceptance without
applying changes. It is consistent with the N6ProLite export, but its PSS binary
has not yet been inspected. Additionally, `Runtime.exec(String)` is not shell
parsing: shell-style quoting in the current `dpm` fallback is questionable on
this path. Prefer the direct vendor owner command and verify the resulting state.

UN20's boot receiver also has a firmware-configured accessibility setup path in
`LoaderReceiver.java:79`, gated by customer properties and a first-run flag. This
does not establish a generic accessibility API available to xTMSAgent, and no
properties were modified in this investigation.

## N6ProLite implications

The terminal export confirms N6ProLite v1.1.1, Android 13, 720x1440, command base
90000000, and PSS SHA-256
`49CE4C88A486E01582B1819C63A583E0AFB4678D65CAD0D0BAE97550E43F4696`.
That is a different PSS from the supplied UN20 package and both examined N96 PSS
versions. No N6ProLite firmware is present in the requested directory.

The next compatibility build can make a bounded attempt at the established N96
owner command `902108171`, gated to the observed N6ProLite model and 90000000
command base, using the existing payload encoder and actual Android verification.
It should log the command result plus admin/owner state. This remains a candidate
until verified on N6ProLite; do not mark all 90-million capabilities supported.

For exact analysis without ADB, an app-side export of the installed PSS APK to
Downloads would let us inspect this terminal's real implementation and check its
hash against the diagnostics. No new APK was built as part of this investigation.
