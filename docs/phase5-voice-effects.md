# Phase 5: strong-spell voices and inventory shortcuts

**Publication update, 2026-11-01:** processed-voice replay is **work in progress and disabled by default** (`broadcast_strong_spells=false`). This guide describes the optional implementation and its earlier validation; the publication build keeps ordinary voice casting independent of replay.

STS 0.3.0, Minecraft 1.21.1 / NeoForge, Java 21. Phase 5 is complete following the user's approval of the stronger reverb and slower second audition. The release is installed in the test instance, with its previous alpha.2 JAR backed up. Network protocol 3 and open upper dependency bounds are preserved.

## Approved voice preset

The requested change replaces three discrete echo taps with a damped Schroeder reverberator: eight parallel feedback comb filters and four serial allpass diffusers. Following feedback on the first audition, the current preset raises wet gain from 0.55 to 1.80 (about 3.27 times) and approximate RT60 from 0.45 to 0.90 seconds. The voice plays 20% slower than that first audition. Resampling also deepens the pitch, giving an effective ratio of 0.672 relative to the source. Exactly 0.5 seconds follow the slowed dry signal, with a smooth fade to zero over the final 50 ms. Loudness normalization remains part of the full effect chain.

One original take from each of ten spells was processed through the shipping IMA ADPCM round trip and effects. The user listened and approved the second preset on 2026-10-14. Those local WAVs preceded SVC Opus encoding, positional attenuation and listener volume controls. All 210 original WAV hashes and sizes were verified unchanged. Temporary audition exports are removed after approval; `gradlew.bat voiceEffectsPreview` regenerates them. The approved audition record (local-only record, excluded from Git) retains the parameters and verification summary.

The final STS 0.3.0 build passed 69 unit tests and 18 native server GameTests on NeoForge 21.1.256 / Iron 3.16.3 / SVC 2.6.24 with the four-addon development pack. This includes the real Black Hole completion/category-packet regression. All 54 production class files match the approved alpha.4 audition byte for byte. The pack's core versions match the test instance; the complete test instance was not launched by the harness. The JAR contains no recordings, regression classes or third-party classes.

## Alpha.2 packet fix and audit

the test instance's 2026-10-14 log showed `voicechat:add_category` failing at SVC's `VolumeCategoryImpl.toBytes`: the display name **Strong spell voices** had 19 characters, exceeding the serializer's 16-character limit. It was registered lazily at the first successful strong cast, so ordinary spells worked and Black Hole disconnected the player after its charge. The previous proxy-only playback test did not exercise this serializer.

The category is now **Spell voices** (12 characters). Its ID remains `sts_spells`, preserving volume preferences. The factory checks the category ID, display name, optional description and icon shape before registering anything with SVC; asynchronous Netty failures cannot be caught by a try/catch around the earlier registration call. Translation keys are not set, and the factory uses only methods present in the oldest supported SVC API.

| Audited wire path | Bound / behavior |
|---|---|
| SVC category | ID matches `[a-z_]{1,16}`; name ≤16 characters; description ≤32,767; optional icon exactly 16×16. Current metadata is 10 / 12 / 50 characters with no icon. |
| Normal cast request | Spell ID ≤256 characters; validated before the render-thread/network handoff. |
| Voice cast request | Same ID bound, at most 48,000 samples / 24,003 ADPCM bytes; largest valid complete Minecraft serverbound envelope stays below 32,767 bytes. |
| Broadcast-policy snapshot | At most 256 IDs, each ≤256 characters; constructor enforces outbound bounds as well as the decoder. |
| Spell-level snapshot | Existing 8,192-entry ceiling, positive levels, IDs ≤256 characters, and a 1,000,000-byte payload budget below Minecraft's clientbound limit. Invalid local metadata is logged and not enqueued; persisted knowledge and the last valid client snapshot remain intact. |

Regressions reproduce the old failure in the actual SVC serializer and Minecraft's complete registered clientbound codec, then round-trip the shipping metadata successfully. The same shipping category factory and each release's native category packet serializer passed all 43 downloaded SVC versions. A dedicated native Black Hole test completes the real cast and starts real SVC playback, with a synthetic compatible client whose outbound custom payloads are encoded/decoded through Minecraft; its connection remains open. Full STS clientbound snapshots and a maximum-size serverbound audio request use the registered codecs too. These checks do not establish what a human listener heard.

The alpha.2 summary and tested platform/dependency combinations are recorded in [validation](validation.md) and its historical release manifest (local-only record, excluded from Git). No audio processing algorithm, voice recording format, casting cost, or dependency upper bound changed in that hotfix. The newer audition builds changed the replay sound and associated metadata/tests; the final release promotes the approved code without production bytecode changes. Current release integrity (local-only record, excluded from Git) records the installed JAR and backup.

## Inventory shortcut

G opens the Grimoire during gameplay or when hovering a nonempty inventory slot. STS follows [JEI's 1.21.1 keyboard focus strategy](https://github.com/mezz/JustEnoughItems/blob/1.21.1/Gui/src/main/java/mezz/jei/gui/input/ClientInputHandler.java): an early hovered-item shortcut is allowed only when no active text field is focused. Otherwise the screen handles the key first, and the fallback also refuses focused typing. Private `EditBox` fields are inspected, covering creative search boxes that are not the screen's focused child. Field metadata is cached per screen class and consulted only for the matching shortcut. Other screens, empty slots and the inventory background do not trigger the shortcut. JEI installation is not required.

## Options

Minecraft adds these entries to the existing `config/sts/options.toml`:

```toml
broadcast_strong_spells = false
broadcast_on_max_level_only = true
spells_to_broadcast = [
  "irons_spellbooks:black_hole",
  "irons_spellbooks:pocket_dimension",
  "irons_spellbooks:blight",
  "irons_spellbooks:fortify"
]
```

The server's settings control multiplayer. NeoForge config reload propagates changes; restarting also applies them. Unknown and disabled spell IDs are ignored. Namespaced addon IDs can be added to the same list. The maximum-only option checks the actual level of the completed cast, including changes made through Iron's cast event. Remembering a maximum-level spell is sufficient only if the casting options actually permit casting it at that level. A level-one unprepared cast does not prepare a maximum-only replay.

These defaults were selected by querying the running Iron 3.16.3 registry: LONG casts with at least 150 mana at maximum level and at least 40 ticks of base charge time. This is a configurable selection criterion, not a restriction on what can be put in the list.

| Default spell | Maximum level | Mana at maximum | Base charge |
|---|---:|---:|---:|
| Black Hole | 6 | 800 | 5.0 s |
| Pocket Dimension | 1 | 300 | 2.0 s |
| Blight | 8 | 200 | 2.5 s |
| Fortify | 10 | 170 | 3.0 s |

The native audit also found addon candidates `gametechbcs_spellbooks:call_forth_the_dead_king` (15,000 mana, 15 s) and `wind_spellbooks:tornado` (400 mana, 3 s). They can be added when those addons are installed. Spell configuration, equipment and cast-speed modifiers can change costs or charge duration.

## Audio and completion

The microphone and normal speech remain in Simple Voice Chat's pipeline. STS observes its lowest-priority PCM callback without changing or cancelling it. It reuses the recognizer's completed 16 kHz utterance; it never starts a second recorder or maintains an additional idle recording buffer.

Only an accepted, configured incantation eligible under the synchronized policy is packed into IMA ADPCM and uploaded with its cast request. The Minecraft upload is one bounded phrase, at most 24,003 audio bytes for three seconds; ongoing voice traffic stays in SVC. Ordinary casts still send only the spell ID. The server determines casting permission, mana, level and timing independently.

A single lazily created Java worker decodes the phrase and prepares its effects while Iron charges the spell. The initial pitch ratio of 0.84 is multiplied by the additional speed ratio of 0.80: the effective resampling ratio is 0.672, approximately seven semitones lower than the source. The voice is 20% slower than the first audition and lasts about 1.49 times the original duration before its tail. Diffuse reverb follows the voiced signal and decays through an appended half-second tail. RMS normalization measures the voiced section once, keeping the quiet tail from boosting the entire clip; a soft limiter prevents hard clipping. Silence produces no replay. The result is mono 48 kHz PCM for SVC's native Opus audio player. Delay buffers are local to each render, so players' clips cannot share reverb state.

Playback begins after the server observes Iron's successful cast event and finishes that server tick, when the spell effect has appeared. Cancelled, failed, unfunded or abandoned casts do not replay. A configured continuous spell replays once at its first successful pulse. The locational SVC channel uses a separate UUID, so the caster is included, and a 50-block spherical filter excludes distant players and other dimensions. SVC connection, deafening and volume settings still apply; the added category is **Spell voices**.

At most eight pending or playing replays are admitted globally, one per caster. When capacity is full, the spell still casts normally. Pending work expires, logout discards that player's replay, and disabling broadcasts stops active playback and shuts down the effect worker. The worker's thread also times out after ten idle seconds. Clips are never saved on the server or added to the Grimoire.

## Warden and sculk

Starting a replay emits one vanilla `GameEvent.EXPLODE` vibration attributed to the caster. This emits a game event only; it creates no explosion or damage. The Warden and sculk sensors hear it through their normal vibration systems, including their 16-block range, travel time, cooldown and wool occlusion. The player-audio radius is 50 blocks; vanilla mob-hearing range is unchanged. No vibration is emitted when playback fails or the feature is disabled.

## CPU and RAM audit

Measured on the project's Intel Core Ultra 9 285H / Windows amd64 / Oracle Java 21.0.12. The alpha.4 Java DSP measurements use a warmed-up synthetic three-second utterance and 300 measured runs; the production DSP bytecode is identical in STS 0.3.0. Native Opus measurements use SVC 2.6.24's actual encoder, 100 warm-up frames and 300 measured mono 960-sample frames. These are local timings, not guarantees for other CPUs or every recording.

| Operation | Median | 95th percentile | Memory / traffic |
|---|---:|---:|---|
| Client phrase packing | 0.137 ms | 0.274 ms | At most 24,003 audio bytes uploaded once |
| Server decode, pitch, reverb, normalization | 4.102 ms | 6.369 ms | At most 915,074 bytes (0.873 MiB) of primitive DSP arrays |
| Native SVC Opus encoding, each 20 ms frame | 0.0485 ms | 0.0577 ms | Synthetic frame averaged 128.3 encoded bytes |
| Worst-case processed phrase | — | — | 238,287 output samples; 476,574 PCM bytes; at most 249 Opus frames |
| Disabled, or no castable configured spell | No packing / DSP / playback | — | No additional audio buffer, clip upload or codec allocation |

At the measured median, encoding a maximum-length replay totals about 12 ms spread across about five seconds of playback, in addition to the one-shot DSP work. Recipient filtering and UDP delivery add work that scales with nearby connected players and is not included in these timings. The DSP byte bound excludes object headers, payload copies, native Opus allocations and JVM thread stacks. Eight maximum-sized processed clips total about 3.64 MiB of PCM; the single worker limits concurrent DSP scratch space. No process-RSS delta is claimed. The reverb delay arrays total 24,784 bytes per active render; there is no new idle audio buffer.

**Broadcasts are off by default while this feature is work in progress.** Reusing existing recognition audio and processing only accepted casts keeps the optional cost small. Leaving the option off skips clip production, uploading, processing, playback and vibration while retaining ordinary spell casting.

## Verification and remaining listening check

Offline tests cover shifted pitch, dense decaying reflections, the exact half-second tail, smooth termination, normalization, silence, per-render state isolation, ADPCM quality and bounds, packet size/round trips, policy/session isolation, 50-block inclusion/exclusion including the caster, dimensions and codec cleanup. Native server GameTests cover tick-driven replay after completion, cancellation, disabled mode, maximum-only and remembered-level eligibility, plus a real Warden and sculk sensor. SVC's native encoder/decoder and paced audio player are exercised on the server.

The client probe uses real Minecraft text widgets and the registered pre/post event listeners to verify focused private and registered searches, typed `g`, valid hovered items and empty/background cases. It also renders the empty and ten-take Grimoire pages. This automated fixture reproduces creative search's private-field focus pattern; it does not substitute for a human creative-inventory check.

The playback API signatures were checked against all 43 downloaded SVC release APIs. Native boundary runs are summarized in [validation](validation.md). No upper version cap was added. The previous full version sweep remains historical Phase 4 evidence; it has not been presented as a complete Phase 5 Cartesian-product sweep.

A listening check with two connected human SVC clients remains additional validation: cast a listed maximum-level spell, listen as caster and nearby player, compare inside/outside 50 blocks, cancel a charge, and turn broadcasts off. The user approved the local reverb preset, while automated tests establish the native codec/playback and packet behavior. Human multiplayer delivery and other speakers have not been verified. Original recordings and regression source are retained; temporary test worlds, reports and audition exports are removed after approval.

Reproduce the audition with `gradlew.bat build broadcastBenchmark voiceEffectsPreview` and native playback with `gradlew.bat "-Pneo_version=21.1.256" -PgameTests -Paddons runGameTestServer`. Remaining regression entry points include `gradlew.bat -PgameTests -Paddons -PnoVoiceChat runGameTestServer` and `gradlew.bat -PclientProbe -PprobeScale=3 runClientProbe`. Run `tools/prepare-compatibility.ps1 -Group both` to recreate the release cache from the retained pinned regression dependency inputs; these inputs do not restrict future runtime versions. Compatibility fixtures are built with `-PgameTests gameTestFixture prepareCompatibility`; then the existing matrix script accepts `-Fixture sts-integration-phase5.jar -RequiredTests 18`.
