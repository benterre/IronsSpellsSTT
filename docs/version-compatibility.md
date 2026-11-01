# Dependency versions and compatibility

Historical validation record. See [current release validation](publication-validation.md) for STS 0.4.0.

STS 0.3.0-alpha.2, Java 21, Minecraft 1.21.1 / NeoForge. The upper bounds for Iron and Simple Voice Chat remain open: future updates can load without an arbitrary compatibility cap. Future releases remain untested until available.

The alpha.2 packet hotfix passed all 18 native tests on NeoForge 21.1.256 with Iron 3.16.3 / SVC 2.6.24 (the test instance's core versions), and with the oldest Iron 3.4.4 / SVC 2.5.20 pair. All 43 actual SVC category serializers round-tripped the repaired shipping metadata and reproduced the old oversized-label failure. The factory uses only methods available in the oldest API; no new lower or upper dependency bound was introduced. Details: [validation](validation.md).

| Dependency | Accepted range | Notes |
|---|---|---|
| Iron's Spells 'n Spellbooks | `[1.21-3.4.4,1.21-3.8.0),(1.21-3.8.0,)` | Required; excludes one specifically broken historical build. |
| Simple Voice Chat | `[1.21-2.5.20,)` | Required for voice features; common server bootstrap remains safe without it. |
| Voice Chat API, when separately present | `[2.5,)` | Optional; no upper cap. |
| Minecraft | `[1.21.1,1.21.2)` | This mod targets 1.21.1. |

## Phase 5 verification

The final capture adapter passed its behavioral contract against all 43 downloaded actual SVC APIs, without the compile-time API masking release differences. Every positional playback, audio-player, encoder, volume-category, receiver-filter and server-lifecycle API used by Phase 5 also exists in those releases.

The current pair, Iron 3.16.3 / SVC 2.6.24, passed 15 native server GameTests with four addons. The same suite passed without SVC. The oldest supported pair, Iron 3.4.4 / SVC `neoforge-1.21.1-2.5.20`, passed all 15 tests with Curios 9.2.0. Actual native Opus encoding/decoding and paced SVC audio-player lifecycle were exercised at both boundaries. The Minecraft client probe passed focused-text and hovered-item shortcut checks plus Grimoire rendering on the current pair.

The server suite checks native progression/casting, replay after completion, cancellation, disabled/max-only modes, remembered maximum-level eligibility and real Warden/sculk vibrations. Receiver radius, caster inclusion and dimension exclusion use API-contract tests. Audible delivery to two human clients and additional speakers remain pending; see [Phase 5](phase5-voice-effects.md).

No full Phase 5 Iron-by-Voice-Chat Cartesian-product sweep is claimed. The larger Phase 4 sweep below is retained as a historical compatibility summary, not relabeled as new Phase 5 runs.

## Historical Phase 4 sweep

On 2026-10-14, publisher lists from [Iron Modrinth](https://modrinth.com/mod/irons-spells-n-spellbooks/versions), [Iron CurseForge](https://www.curseforge.com/minecraft/mc-mods/irons-spells-n-spellbooks/files/all), [SVC Modrinth](https://modrinth.com/plugin/simple-voice-chat/versions) and [SVC CurseForge](https://www.curseforge.com/minecraft/mc-mods/simple-voice-chat/files/all) yielded 54 distinct Iron jar filenames and 43 SVC jar filenames tagged for NeoForge 1.21.1. Both distinct 2.5.20 voice jars were tested.

The Iron axis used SVC 2.6.24; the voice axis used Iron 3.16.3. Pass required all twelve native Phase 4 GameTests plus clean completion. Results: **49/54 Iron builds and 43/43 SVC builds passed**. The final Iron axis used fixture SHA-256 `776905D8EF8F8E15B969E6B6C238111F27B2732AFDA3655C73E804FF5F130E58`. The original voice axis used `8F2C103153130EE0F8BBDF6BC71DB86ABF46780D9979A2868F883F882B88497F`; final production changes were additionally checked at the voice boundaries and oldest corner. Its 41 intermediate rows were not reruns of final Phase 4 code.

The historical suite tested inventory-only upgrades, persistent levels, prepared/unprepared prices, staff/handfree policies, native completion, scroll consumption, continuous pulses, cancellation, insufficient mana, forged requests and generic addon registry discovery. Its addon pack exposed 144 spells. Current Phase 5 configuration exposes 145 enabled entries; registry counts depend on native spell configuration.

Curios 9.2.0 was used where the old Iron jar referenced its deprecated helper, otherwise 9.5.1. Caelus 7.0.1 was available to older builds. Iron 3.15.5 needs Iron's Patreon Lib 1.0.1; 3.15.6 through 3.16.1 needs Iron's Lib 1.1.0; newer releases used 2.2.0. Other dependencies were GeckoLib 4.9.3 and Player Animator 2.0.4. This does not assert compatibility with every auxiliary-dependency combination.

Iron 3.15.1/.2 use `spellbook_0` in their native wheel but recognize only `spellbook` as a mana-consuming quick-cast source. STS normalizes that slot through native initiation, preserving equipped level, native cost and cooldown. Both releases passed the historical suite.

| Historical Iron failure | Observed reason | Accepted-version decision |
|---|---|---|
| 3.4.0 | Native casting completion loads client Minecraft on the dedicated server. | Below minimum. |
| 3.4.1–3.4.3 | Native equipped-book lookup failed with tested Curios 9.2.0 and 9.5.1 combinations. | Minimum is 3.4.4; this is not a claim about every historical Curios combination. |
| 3.8.0 | Native constructor loads client LocalPlayer on the dedicated server. | Exclude exactly 1.21-3.8.0; later/future versions remain open. |

Generated raw logs, CSV outputs, screenshots, test worlds and downloaded research caches were deleted after Phase 5, as requested. The tables below preserve the historical results. Test source, fetch/matrix scripts and pinned development dependencies remain to regenerate checks. The release jar contains no test classes, recordings or third-party dependencies.

## Historical per-release results

`Pass` below means twelve native Phase 4 tests passed, not a complete Phase 5 replay test for that release.
### Iron releases with Voice Chat 2.6.24

| Iron publisher version | Result | Curios | Iron library |
|---|---|---|---|
| 1.21-3.4.0 | Fail | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.4.1 | Fail | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.4.2 | Fail | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.4.3 | Fail | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.4.4 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.4.5 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.5.0 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.6.0 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.6.1 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.6.2 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.7.0 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.7.1 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.8.0 | Fail | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.8.2 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.8.3 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.8.4 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.8.5 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.8.6 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.8.7 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.8.8 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.8.9 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.8.10 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21-3.9.0 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.9.1 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.10.0 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.10.1 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.10.2 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.11.0 | Pass | 9.2.0+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.12.0 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.12.1 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.12.2 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.12.3 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.13.0 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.13.1 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.14.0 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.14.1 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.14.2 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.14.3 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.14.4 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.14.5 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.14.6 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.14.7 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.14.8 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.15.0 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.15.1 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.15.2 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.15.3 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.15.4 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.15.5 | Pass | 9.5.1+1.21.1 | patreon-1.21.1-1.0.1 |
| 1.21.1-3.15.6 | Pass | 9.5.1+1.21.1 | 1.21.1-1.1.0 |
| 1.21.1-3.16.0 | Pass | 9.5.1+1.21.1 | 1.21.1-1.1.0 |
| 1.21.1-3.16.1 | Pass | 9.5.1+1.21.1 | 1.21.1-1.1.0 |
| 1.21.1-3.16.2 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |
| 1.21.1-3.16.3 | Pass | 9.5.1+1.21.1 | 1.21.1-2.2.0 |

### Voice Chat releases with Iron 3.16.3

| Voice Chat publisher version | Server result | Actual API result |
|---|---|---|
| neoforge-1.21-2.5.20 | Pass | Pass |
| neoforge-1.21.1-2.5.20 | Pass | Pass |
| neoforge-1.21.1-2.5.21 | Pass | Pass |
| neoforge-1.21.1-2.5.22 | Pass | Pass |
| neoforge-1.21.1-2.5.23 | Pass | Pass |
| neoforge-1.21.1-2.5.24 | Pass | Pass |
| neoforge-1.21.1-2.5.25 | Pass | Pass |
| neoforge-1.21.1-2.5.26 | Pass | Pass |
| neoforge-1.21.1-2.5.27 | Pass | Pass |
| neoforge-1.21.1-2.5.28 | Pass | Pass |
| neoforge-1.21.1-2.5.29 | Pass | Pass |
| neoforge-1.21.1-2.5.30 | Pass | Pass |
| neoforge-1.21.1-2.5.31 | Pass | Pass |
| neoforge-1.21.1-2.5.32 | Pass | Pass |
| neoforge-1.21.1-2.5.33 | Pass | Pass |
| neoforge-1.21.1-2.5.34 | Pass | Pass |
| neoforge-1.21.1-2.5.35 | Pass | Pass |
| neoforge-1.21.1-2.5.36 | Pass | Pass |
| neoforge-1.21.1-2.6.0 | Pass | Pass |
| neoforge-1.21.1-2.6.1 | Pass | Pass |
| neoforge-1.21.1-2.6.2 | Pass | Pass |
| neoforge-1.21.1-2.6.3 | Pass | Pass |
| neoforge-1.21.1-2.6.4 | Pass | Pass |
| neoforge-1.21.1-2.6.5 | Pass | Pass |
| neoforge-1.21.1-2.6.6 | Pass | Pass |
| neoforge-1.21.1-2.6.7 | Pass | Pass |
| neoforge-1.21.1-2.6.8 | Pass | Pass |
| neoforge-1.21.1-2.6.9 | Pass | Pass |
| neoforge-1.21.1-2.6.10 | Pass | Pass |
| neoforge-1.21.1-2.6.11 | Pass | Pass |
| neoforge-1.21.1-2.6.12 | Pass | Pass |
| neoforge-1.21.1-2.6.13 | Pass | Pass |
| neoforge-1.21.1-2.6.14 | Pass | Pass |
| neoforge-1.21.1-2.6.15 | Pass | Pass |
| neoforge-1.21.1-2.6.16 | Pass | Pass |
| neoforge-1.21.1-2.6.17 | Pass | Pass |
| neoforge-1.21.1-2.6.18 | Pass | Pass |
| neoforge-1.21.1-2.6.19 | Pass | Pass |
| neoforge-1.21.1-2.6.20 | Pass | Pass |
| neoforge-1.21.1-2.6.21 | Pass | Pass |
| neoforge-1.21.1-2.6.22 | Pass | Pass |
| neoforge-1.21.1-2.6.23 | Pass | Pass |
| neoforge-1.21.1-2.6.24 | Pass | Pass |
