# Validation record

Historical validation record. See [current release validation](publication-validation.md) for STS 0.4.0.

## Phase 5 complete: STS 0.3.0, 2026-10-14

The user approved the second voice preset and authorized the final build. STS 0.3.0 includes the JEI-style text-focus shortcut guard, safe category-packet metadata, strong-spell playback at native completion, stronger reverb, the additional 20% slowdown, a half-second tail, 50-block SVC playback and vanilla Warden/sculk vibration events.

- **69 unit tests and 18 native server GameTests passed** on the final version. The server used NeoForge 21.1.256 / Iron 3.16.3 / SVC 2.6.24 and four development addons. Black Hole completed its native cast, transmitted the category through Minecraft's encoder/decoder and left the synthetic client connected.
- All **54 production class files** match the approved alpha.4 audition byte for byte. The final JAR is **132,751 bytes**, contains no development classes, recordings or third-party classes, and retains open dependency upper bounds. Artifact SHA-256 and test scope are saved in the release manifest (local-only record, excluded from Git).
- The release is installed as `<test-instance>/mods/sts-0.3.0.jar`, with exactly one STS version active. The old hotfix is preserved at `<test-instance>/sts-backups/sts-0.3.0-alpha.2.jar`. Installed and backup hashes were verified.
- All **210 original WAV hashes and sizes** are preserved. Temporary test worlds, reports and audition exports are removed after approval. Regression source and the Java preview generator remain.

The audio preset has human approval. Two-human-client multiplayer listening and additional-speaker recognition remain unverified; the complete test instance pack was not launched by the automated harness. Phase 5 implementation and release packaging are complete, with those checks recorded as further validation.

The sections below preserve the history and scope of earlier builds.

## Alpha.4 stronger reverb and slower audition: 2026-10-14

The second audition raises reverb wet gain from 0.55 to 1.80 and approximate RT60 from 0.45 to 0.90 seconds. Playback is 20% slower than the first audition; the combined pitch/speed resampling ratio is 0.672. The fixed half-second tail and final 50 ms fade are retained. Ten new clips were compared against dry recordings and previous previews, then approved for STS 0.3.0.

- **69 unit tests passed**, including a timing regression for the additional 20% slowdown and existing pitch, decay, silence, clipping, state-isolation and packet checks.
- **18 native server tests passed** on NeoForge 21.1.256 / Iron 3.16.3 / SVC 2.6.24 with four development addons. Real Black Hole completed and its category packet encoded/decoded without closing the synthetic client's connection. This is not a launch of the entire test instance or a human listening test.
- Maximum three-second source: decode/effects p50/p95 **4.102/6.369 ms**, at most **915,074 bytes** of primitive working arrays, **238,287 output samples** and **249 Opus frames**. No additional idle audio buffer is allocated. Object/native/thread overhead and recipient/network delivery are outside these measurements.
- All **210 original WAVs** retain their saved sizes and hashes. Ten new PCM16 48 kHz mono clips have bounded peaks, exactly half-second tails and zero final samples. The first audition is retained for comparison.

The second audition manifest (local-only record, excluded from Git) records the alpha.4 artifact, parameters and subsequent approval. Its production code was promoted unchanged into STS 0.3.0. Temporary audition exports were removed after approval.

## Alpha.3 reverb audition: 2026-10-14

The first audition replaced discrete echoes with dense damped reverb, retained pitch ratio 0.84 and loud normalization, and appended exactly 0.5 seconds after the pitched dry signal. A final 50 ms fade ended at zero. Reverb state was local to each render. Ten full-effects clips and dry comparisons were generated from one original take per spell using the shipping ADPCM round trip and DSP. Those WAVs preceded SVC's Opus codec, attenuation and volume controls. This preset was superseded by the stronger/slower second audition.

- **68 unit tests passed**, including diffuse reflection density/decay, pitch, the exact tail duration, smooth termination, clipping bounds, silence and state isolation. Existing category/packet wire regressions passed.
- **18 native server GameTests passed** on NeoForge 21.1.256 / Iron 3.16.3 / SVC 2.6.24 with four development addons, including real Black Hole completion and category transmission through Minecraft's encoder/decoder. The entire test instance was not launched.
- Maximum three-second clip: ADPCM packing p50/p95 0.135/0.153 ms; decode/effects p50/p95 3.391/3.672 ms; 772,214 bytes of primitive working arrays, excluding object, thread and native codec overhead. There is no additional idle recording buffer.
- All **210 original WAVs** retain their saved sizes and SHA-256 hashes. Ten output WAVs have the correct 48 kHz mono PCM16 format, bounded peaks, exactly half-second tails and zero final samples.

The first audition manifest (local-only record, excluded from Git) records that historical build. The alpha.2 release manifest (local-only record, excluded from Git) remains a record of the previously installed hotfix. Temporary audition exports are removed after final approval, while originals and regression source remain.

## Alpha.2 disconnect fix and final packet audit: 2026-10-14

the test instance's client/server log identified the failing packet as `voicechat:add_category`: STS's 19-character category name exceeded SVC's 16-character wire limit. STS 0.3.0-alpha.2 uses **Spell voices**, preserves category ID `sts_spells`, and validates metadata before asynchronous registration. The original failure is reproduced by both the real SVC serializer and Minecraft's full clientbound encoder, then the corrected packet round-trips successfully. See [packet audit and bounds](phase5-voice-effects.md).

- **65 offline tests passed**, including original-failure reproduction, actual SVC add-category serialization/deserialization, maximum valid snapshot and voice-request sizes, and invalid outbound metadata rejection.
- **18 native tests passed** on the test instance's exact core versions: Minecraft 1.21.1, NeoForge 21.1.256, Iron 3.16.3 and SVC 2.6.24, with the four-addon development pack. This verifies those versions; the entire test modpack was not launched by the harness.
- The same 18-test suite passed on Iron 3.4.4 / SVC 2.5.20 with Curios 9.2.0 and NeoForge 21.1.256. The oldest SVC API and legacy category serializer remain supported.
- The native Black Hole test completes the actual maximum-level spell, uses the shipping SVC backend, and checks real category transmission through a synthetic compatible client's full Minecraft encoder/decoder. It observes exactly one category packet, completed playback setup and an open game connection. The fixture marks only its synthetic UUID voice-compatible; it does not prove UDP delivery to a human listener.
- All **43 SVC releases** passed the shipping category factory and their actual add-category packet serializers, as well as existing capture/API contracts. Compile-time API classes and newer serializers are prevented from masking older releases.
- Warden fixture setup now explicitly initializes its native dig cooldown and keeps neighboring chunks ticking, so a freshly constructed test entity remains awake for the hearing assertion.

Protocol 3 and dependency upper bounds were unchanged by the hotfix. Outbound snapshot validation logs invalid local metadata instead of handing it to Netty; persisted progression is preserved. Client requests are constructed on the guarded voice worker before entering the render thread. The hotfix retained its earlier audio processing and stored voice formats. Artifact integrity and cleanup status are recorded in the historical manifest (local-only record, excluded from Git).

## Phase 5 update: 2026-10-14

STS 0.3.0-alpha.1 adds guarded JEI-style hovered-item shortcuts and configured strong-spell replay after successful native completion. Original SVC speech remains unchanged. The new defaults enable replay at maximum level for Black Hole, Pocket Dimension, Blight and Fortify. See [configuration, signal processing and CPU/RAM audit](phase5-voice-effects.md).

- **59 offline tests passed**, including ADPCM quality/bounds, effects/pitch/echo/normalization, payload bounds and round trips, policy isolation, spherical recipient filtering, caster inclusion, dimensions and resource cleanup.
- **15 native GameTests passed** with current Iron/SVC and four addons, with that addon pack without SVC, and with the oldest supported Iron 3.4.4 / SVC 2.5.20 pair. They cover native progression/casting, successful replay only after completion, cancellation, disabled/max-only modes, remembered-level eligibility, and actual Warden/sculk reactions. The hearing fixture keeps its distant test chunks ticking and clears prior vibration state before emitting the event.
- Native SVC Opus encode/decode and paced audio-player delivery to a synthetic channel passed at both version boundaries, including stopped callback and codec closure. This verifies real codec/playback behavior; it does not establish human-audible network delivery.
- All **43 actual SVC release APIs** passed the final capture adapter contract and resolved the server playback methods used by Phase 5. Dependency upper bounds remain open.
- The final Minecraft client probe passed private creative-style and registered text focus, typed `g`, hovered-item/empty/background cases, and guarded pre/post event checks. Empty and ten-take Grimoire pages rendered at GUI scale 3.
- The three-second Java audio audit measured client packing p50/p95 **0.138/0.274 ms**, server effects **3.256/4.950 ms**, and 744,230 bytes of transient primitive DSP arrays. Native SVC frames measured **0.0493/0.0657 ms**. Native memory, object overhead, thread stacks and recipient/network cost are excluded from those bounds.

Additional voices and a two-human-client listening check remain pending. The final release manifest records its artifact hash and original-recording integrity. Generated test data, raw outputs, research caches and test worlds were deleted as requested; original user recordings, regression source and these summary reports remain. Older sections below describe their explicitly named historical releases.

## Phase 4 update: 2026-10-14

STS 0.2.0-alpha.1 adds server-owned remembered levels, the three options in `config/sts/options.toml`, unprepared casts at level 1 or maximum known level for 150% mana, staff casting and optional equipment-free casting. The user reported successful live casting with the previous release; third-party voices and audible multiplayer delivery remain pending.

- 52 offline tests pass, including all policy combinations, surcharge rounding, session isolation and state-packet encode/decode.
- Twelve native server GameTests pass on the current Iron release, with Simple Voice Chat and four addons, and with that addon pack without voice chat. These cover inventory-only upgrades, remembered levels after item removal, replacement/death copying, prepared normal costs, unprepared level/cost/source, scroll preservation, continuous pulses, native completion, cancellation, mid-cast mana loss and unknown request rejection.
- All 43 actual Simple Voice Chat release APIs pass registration, unchanged PCM, prior effects, cancellation, mute, disable, disconnect and initialization contracts.
- The final Iron sweep passed all twelve native tests on 49 of 54 publisher-listed builds. Five specifically failing historical builds are excluded; later and future versions remain accepted. All 43 Voice Chat releases passed the server axis, with final code and metadata additionally checked at both voice version boundaries and the oldest Iron/Voice Chat corner. The report records the different fixture scopes.
- Final addon-pack checks without Voice Chat still pass all twelve tests. The release jar has 33 production classes, no dependencies, development classes or recordings; all 210 source WAV files remain unchanged.
- The actual phase 4 client Grimoire rendered empty and ten-take pages at scale 3, with the maximum-level label visible; screenshots were inspected.

Full policy and migration details: [phase 4 guide](phase4-options.md). Version-by-version runtime results are recorded in the [compatibility report](version-compatibility.md). The sections below preserve the previous alpha's acoustic and memory measurements; phase 4 changes eligibility and pricing, not its acoustic score rule.

Started 2026-10-13; updated 2026-10-14. This is an implementation and development-test alpha. Personal spell recordings and developer negative recordings have been tested. Audible multiplayer delivery remains an open acceptance check. Negative recordings never become in-game training or Grimoire content.

## Environment and artifacts

- Minecraft 1.21.1; NeoForge 21.1.252; ModDevGradle 2.0.148; Gradle 9.2.1.
- Launcher/game/test JDK: Oracle Java 21.0.12, Windows 11 amd64.
- CPU: Intel Core Ultra 9 285H; 16 processors available to the JVM.
- Iron's Spellbooks 1.21.1-3.16.3, Iron's Lib 1.21.1-2.2.0, Curios 9.5.1+1.21.1, GeckoLib 4.9.3, Player Animator 2.0.4+1.21.1.
- Simple Voice Chat 1.21.1-2.6.24; API 2.6.24.
- Test addons: GTBC's Spellbooks 3.0.0-1.21.1 with SpellLib 2.2.0-1.21.1, Spells Gone Wrong 1.21.1-2.0.1 with JinxedLib 1.0.4, Unstable Spellbooks 0.6.0, Wind's Spellbooks 1.0.1.
- Official dependency origins and hashes are pinned in `dependency-manifest.json`; the bootstrap script verifies them. Third-party jars are not bundled in STS.

## Checks completed

| Check | Result / scope |
|---|---|
| Offline Java tests | 46 passed: recognition/VAD, calibrated global shortlist, feature-only memory, identical precomputed-query results, waveform previews, WAV preparation/split selection, long-file/PTT replay, library/signature/acoustic caches and SVC callbacks. No production service connection required. |
| Dedicated GameTest server with SVC and four addons | Five required tests passed in 1.733 s after startup. Registry: 144 spells: Iron 110, GTBC 17, Spells Gone Wrong 2, Unstable 8, Wind 7. All enabled addon spells discovered through generic containers. |
| Dedicated GameTest server without SVC | Five required tests passed in 1.922 s with the same addon pack, no voicechat mod or API present. Common bootstrap loads safely; speech remains client-only. |
| Casting lifecycle | Native equipment, learning, spectator, mana, cooldown, level/source, duplicate request, cancellation, and exact scroll consumption checks passed using synthetic server players. Twenty repeated no-book requests, held scrolls/imbued sources, held/inventory books and book removal cannot bypass the required Curios spellbook. |
| SVC runtime | STS plugin initialized and registered; native UDP voice server started; dedicated game server shut down cleanly. This does not prove delivery to a listening client. |
| SVC audio contract | Four synthetic API tests verified original frames remain unchanged, other prior pipeline effects remain observable, and cancellation/mute/disable/disconnect prevent recognition. |
| Actual Minecraft client GUI | Final alpha.2 empty Grimoire and populated ten-waveform page rendered at GUI scale 3 / 1280×800. Screenshots inspected; labels, rows and positive-only controls fit. Probe shut down automatically. Fixtures are excluded from the release jar. |
| Updated synthetic speech benchmark | Eight eligible spells / 80 one-second feature-only templates: p50 4.430 ms, p95 6.618 ms. Primitive features 0.778 MiB, retained PCM zero. This excludes global disk shortlist work. |
| Actual speech development holdouts | Prepared: 27/30 correct, zero wrong, three rejected. Untrimmed runtime VAD: 25/30 correct, zero wrong, five rejected. Known unequipped speech: zero false casts in 30 nine-candidate trials and 270 single-wrong-spell pairs. |
| Frozen reserved negatives | Ten-take bank: 6/40 near-match files cast (6/19 emitted utterances; 21 files emitted none). Five gameplay clips / 120.23 s emitted ten utterances and zero casts; two clips emitted none. Positive ten-take self-query sanity is 30/30, not independent accuracy. |
| Actual disk-backed speech path | Final alpha.2 warm inactive shortlist: p50 5.143 ms, p95 6.674 ms. Ten equipped cached spells: p50 0.792 ms, p95 1.239 ms. First acoustic cache creation for 70 enrollment takes took 71.281 ms. |
| Release packaging / source audio | Jar contains no development classes, third-party classes or recordings. All 210 source WAV hashes and sizes unchanged (81,582,272 bytes). Release hash is saved in the manifest. |

Matching timings exclude the speaker's utterance duration, roughly 220 ms VAD endpoint silence or 250–300 ms PTT idle finish, network delay and server tick/cast time. Benchmarks measure processing on this machine, not game FPS or process RSS. The numeric rule was frozen before reserved negatives were first decoded. Results come from one speaker; near-match false casts remain, and the gameplay sample is short. See the [real speech validation report](real-speech-validation.md) for methodology and remaining limits.

Storage is lazy and per spell. Gameplay retains only full acoustic features for active casting sources, with zero recorded PCM in cached templates. Eight spells with ten one-second takes each use about 0.78 MiB of primitive features, excluding object overhead and working memory; at three seconds that is about 2.36 MiB. A compact global search index retains 208 floats per take (8,320 bytes per ten-take spell); this allows known unequipped incantations to reject casting requests instead of being forced into equipped labels. Up to eight shortlisted competitor files are read temporarily for full matching. The Grimoire reads one page on the worker and keeps ten 128-float previews (5,120 bytes), releasing them when closed. Disk reads/enrollment/deletion temporarily load one spell's PCM and release it after use. There is no aggregate trained-spell cap.

## Retained reports

Raw logs, screenshots, evaluator outputs, JUnit HTML and temporary audition audio were removed after validation and approval. Results remain in this record, [the tuning report](generic-recognition-tuning.md), [voice effects](phase5-voice-effects.md) and the release manifest (local-only record, excluded from Git). Regression sources and Gradle tasks regenerate test outputs and previews.

The first GameTest fixture lacked negotiated mod channels; the official NeoForge `configureMockConnection` test hook fixed that development-only fixture. GTBC's initial addon pack lacked SpellLib; installing its declared mandatory dependency fixed pack loading. One client probe initially waited only for the title screen and encountered Minecraft's first-run accessibility screen; supporting that screen fixed the probe. These were corrected before recording successful results.

## Remaining acceptance checks

1. The ten spell datasets and negative/gameplay recordings were supplied; see [real speech results](real-speech-validation.md). Other voices and microphones remain untested. Each player enrolls their own positive incantations; cutoff calibration uses those examples without requiring addon-specific negatives.
2. Join a real dedicated server with two real SVC clients. Confirm a trained utterance both reaches the listener and causes the intended native cast. Check packet loss, PTT release, mute, reconnect, GUI suppression, world changes and different microphones.
3. Observe false activations during ordinary gameplay, especially with only one trained equipped spell. The distance cutoff remains necessary when no runner-up exists. Synthetic unknown-noise rejection is insufficient to establish conversation safety.

Apex Arcana's published release is Forge 1.20.1, not NeoForge 1.21.1, so it was excluded from this pack: [publisher release](https://www.curseforge.com/minecraft/mc-mods/apex-arcana-irons-spells-n-spellbooks-addon). Its future compatible Iron registry spells would use the same discovery path.
