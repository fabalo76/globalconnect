# Test results

Test date: 2026-07-29

Primary device: NEXGO CT20P

Serial: `b98d6e61`

Android: 11 / API 30

Firmware fingerprint:
`alps/full_aiv8175p2_bsp/aiv8175p2_bsp:11/RP1A.200720.011/mp5V469:user/release-keys`

Device ABI list: `armeabi-v7a,armeabi`

## Build and static verification

| Test | Result |
|---|---|
| Clean debug + release build | PASS |
| Release lint | PASS |
| PinpadApp debug compile | PASS |
| PinpadApp debug unit tests | PASS |
| Release signature verification | PASS, APK Signature Scheme v2 |
| Package/service identity | PASS |
| Embedded application icon at five densities | PASS |
| No launcher activity; TTS settings activity retained | PASS |
| Permission audit | PASS, no declared permissions and no Internet |
| Asset audit | PASS, only English/Spanish and Slt/Mateo |
| Native ABI audit | PASS, ELF32 ARM + ELF64 AArch64 |
| Excluded language native-string scan | PASS |

## Clean installation and offline engine test

All existing RHVoice core, Spanish-language, and Mateo packages were explicitly
uninstalled. The final debug artifact was installed with no other RHVoice
package present. Airplane mode was enabled before the engine test.

Cold startup extracted and SHA-256 validated all 97 bundled files. Android
package service discovery returned:

`com.github.olga_yakovleva.rhvoice.android/.RHVoiceService`

The release instrumentation test verified:

- exact engine package discovery;
- exact voice set `{Slt, Mateo}`;
- `Slt` reports `en-US`, offline;
- `Mateo` reports `es-MX`, offline;
- `en`, `en-US`, `es`, and `es-MX` are available;
- `setLanguage(en)` and `setLanguage(en-US)` select `Slt`;
- `setLanguage(es)` and `setLanguage(es-MX)` select `Mateo`;
- Russian returns `LANG_NOT_SUPPORTED`;
- real English and Spanish WAV synthesis; and
- caller rate and volume parameters reach the engine.

After adding the embedded application icon and removing the launcher intent,
the exact rebuilt release APK repeated this complete suite successfully.
`aapt` and on-device package resolution confirmed that the APK exposes its
application icon, has no `MAIN/LAUNCHER` activity, and still registers
`RHVoiceService` for `android.intent.action.TTS_SERVICE`.

Audio measurements from the exact pre-signed release APK:

| Voice/rate | PCM samples | Peak (16-bit) | Clipped samples | Duration ratio |
|---|---:|---:|---:|---:|
| Mateo / 1.0 | 48,150 | 19,993 | 0% | n/a |
| Slt / 1.0 | 98,040 | 22,555 | 0% | 1.000 |
| Slt / 0.85 | 115,197 | 22,642 | 0% | 1.175 |

The `0.85` caller rate therefore produced 17.5% more samples than `1.0` for
the same sentence, and the `1.25` Global Connect engine gain did not clip any
measured sample. This validates the requested slower rate and digital headroom.
Absolute acoustic loudness/SPL still depends on the terminal's media-volume
setting, speaker, enclosure, and environment and was not measured with a
calibrated sound meter.

## PinpadApp M17

A CT20P instrumentation test sent real encoded transaction frames through
PinpadApp's `M17` parser and `PinpadDeviceCommands`, not directly to RHVoice:

- `M17(en, Base64(UTF-8 text))` returned status `0`, selected `Slt`, and
  completed English synthesis.
- `M17(es-MX, Base64(UTF-8 text))` returned status `0`, selected `Mateo`, and
  completed Spanish synthesis.

Pinpad diagnostics confirmed:

- exact engine `com.github.olga_yakovleva.rhvoice.android`;
- `requiredEngineInstalled=true`;
- `bundleProvisioned=true`;
- discovered voices `[Mateo, Slt]`;
- `speechRate=0.85`, status success;
- requested per-utterance volume `1.0`;
- engine gain `1.25`; and
- completed synthesis for both voices.

The diagnostics contain locale, voice, character count, and status only. They
do not contain the spoken text.

## Upgrade and migration

The exact debug artifact was installed first. An instrumentation fixture
created app-owned legacy `bundle-1.17.2` and interrupted
`bundle-1.18.4-gc1.staging-interrupted` directories with the same Android
ownership and SELinux context as real app data.

`adb install -r` then upgraded in place to the exact pre-signed release APK.
On cold startup the release:

1. extracted and validated the current 97-file bundle;
2. removed `bundle-1.17.2`;
3. removed `bundle-1.18.4-gc1.staging-interrupted`; and
4. initialized both voices successfully.

The subsequent release instrumentation test asserted both obsolete paths were
gone and repeated the complete offline voice/audio suite.

## Reboot persistence

The CT20P was rebooted while airplane mode remained enabled. After
`sys.boot_completed=1`, the exact release APK:

- retained its private bundle;
- revalidated all 97 files;
- initialized `[mateo, slt]`;
- exposed the two offline Android voices; and
- passed the complete locale, synthesis, rate, and clipping test again.

## Final device state

Instrumentation helper packages were removed. Airplane mode was restored to
off. The CT20P was left with exactly one RHVoice package:

`com.github.olga_yakovleva.rhvoice.android`

Installed version: `1.18.4` (`1180401`), primary ABI `armeabi-v7a`.

## Remaining limitations

- No N82 was connected, so installation and playback on N82 remain pending.
  NEXGO specifies Android 10 and a Cortex-A53 CPU for N82; the APK contains both
  32-bit ARM and 64-bit ARM libraries to cover model firmware variants.
- The NEXGO portal production-signing step was not performed. Repeat the smoke,
  reboot, and M17 tests on the portal-signed bytes and update TMS hashes.
- Acoustic output was verified for completed playback and zero digital
  clipping, but not measured with a calibrated SPL meter in a retail
  environment.
- The official English language-data submodule has no standalone license file;
  see `LICENSE_AUDIT.md` before external distribution.
