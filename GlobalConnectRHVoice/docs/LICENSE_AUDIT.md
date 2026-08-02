# License audit

Audit date: 2026-07-29

## Components

| Component | Exact source | License evidence | Result |
|---|---|---|---|
| RHVoice engine | tag `1.18.4`, commit `fa9dd196fd2dac3b0bf089a2d80fc8477c2380e3` | upstream root `LICENSE.md`; source headers | GPL-2.0, with several files marked GPL-2.0-or-later |
| English language data | `RHVoice/English` tag `2.17`, commit `8a2ea34df72a190dae245460aeb20a867d64775c` | official RHVoice submodule, but the data repository has no standalone license file at this commit | licensing ambiguity disclosed below |
| Spanish language data | commit `54e69f2bb6b3bfa8b5427802cd16ed6a65ebdf06`, format 1 revision 41 | bundled upstream `LICENSE` | LGPL-2.1 |
| Slt English voice | tag `4.1`, commit `c17dded68322016aba2868d9c29f11bba14d275f` | upstream `README.md`; CMU ARCTIC license from the originating corpus | permissive CMU 2003 license; redistribution allowed with notice |
| Mateo Spanish voice | commit `b1b650da4dc93ffac894d03c4b3a20a043dc7f4c`, format 4 revision 14 | upstream `readme.md`; `rmcspeech_usage_notes.docx` | Unlicense; usage notes expressly permit personal and commercial use |
| HTS engine | RHVoice 1.18.4 source | copyright/license block in `HTS_engine.h` | three-clause BSD; RHVoice modifications also carry GPL terms |
| utf8-cpp | RHVoice 1.18.4 vendored source | copyright/license block in `utf8.h` | Boost Software License 1.0 |
| RapidXML | RHVoice 1.18.4 vendored source | `third-party/rapidxml/license.txt` | Boost-1.0 or MIT; MIT option selected |

## Voice redistribution conclusion

Both selected voices can legally be redistributed in the unified APK:

- Slt is under the permissive CMU ARCTIC license. Its copyright, conditions,
  disclaimer, and upstream voice attribution are included.
- Mateo is published under the Unlicense. The accompanying speaker document
  explicitly says it is not a license and permits personal and commercial use.
  That document and upstream attribution remain in the voice assets.

No non-commercial or no-derivatives voice is included.

## English language-data caveat

The official `RHVoice/English` data repository at tag 2.17 contains no
standalone `LICENSE`, `COPYING`, or README license grant, and GitHub reports no
detected repository license. It is an official submodule selected and
distributed by the GPL-licensed RHVoice project, but the submodule's standalone
copyright terms are not explicit.

This does not affect the clearly permissive Slt voice license, and the build
uses the exact English data selected by RHVoice 1.18.4. For distribution outside
Global Connect's controlled deployment, obtain written confirmation from the
RHVoice maintainers that the English language-data files are covered by the
project's GPL-2.0 distribution terms. This is a documented legal-review caveat,
not a technical or voice-redistribution failure.

## GPL obligations

The combined engine and Global Connect modifications form a GPL-covered
program. A distributor conveying an APK must accompany it with complete
corresponding source, or a GPL-compliant written offer where applicable. The
source must match the binary and include the Java/C/C++ source, Gradle and NDK
build files, generated-data recipe, modifications, and scripts needed to
rebuild. Recipients must receive the GPL text, copyright notices, and the right
to study, modify, and redistribute the source under the GPL.

The complete project in this directory is the corresponding-source delivery
for the supplied pre-production APKs. NEXGO production signing must not be used
as a technical restriction that prevents recipients from exercising GPL rights
to rebuild or modify software on devices they control.

## Shipped notices

Full notices are in `app/src/main/assets/licenses` and therefore inside both
APKs. The original Spanish `LICENSE`, Slt readmes, Mateo readme, attribution,
and usage notes also remain adjacent to the extracted runtime data.
