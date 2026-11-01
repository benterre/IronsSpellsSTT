# Publication validation

Audit: **2026-11-01**. Release version: **0.4.0**. The functional client/server matrix was run during 0.4.0 preparation; the final publication rebuild changes metadata, documentation and icon packaging, with no gameplay-code changes. This report records validation performed before repository publication.

## What was checked

- The NeoForge 1.21.1 unit suite has **69 tests**. Its dedicated-server suite has **19 native tests**, including the real Black Hole category-packet regression, bounded transport, spellbook/unprepared casting, upgrades, cancellation, continuous pulses, instant casts, replay completion and sculk/Warden hearing. Four addon mods are included in this baseline.
- The current Forge 1.19.2/1.20.1 and legacy NeoForge 1.20.1 server suites each have **16 native tests**. The oldest enum-based engine uses **6 native tests** for registry/equipment, level persistence, normal/150% mana, instant completion, cancellation, cooldowns, actual server ticks, native Opus/player/category codecs and optional replay.
- Real Minecraft client probes open the Grimoire, render empty and ten-take pages, and exercise hovered-item shortcuts, private/registered search fields, typed `g`, wrong keys and empty slots. The demonstration waveforms are synthetic. The probes do not enter a world or collect enrollment recordings.
- Actual native client/server boundary runs include Iron **1.0.3, 1.2.1, 2.0.0, 2.1.0, 3.0.0, 3.1.3, 3.4.0, 3.4.4 and 3.16.3** across their applicable Minecraft versions. The oldest 1.19.2 Iron release was paired with **SVC 2.3.2**. Current baselines use SVC 2.4.32/2.6.24/2.6.27 as appropriate. See the runtime report for individual combinations.
- Every original recording was checked against the saved input-integrity manifest: **210 WAV files, 81,582,272 bytes, unchanged**. Audio and that private manifest are excluded from Git.
- Each collected JAR is checked for author **benterre**, **MIT** metadata/license, AI disclosure, the mod icon and its metadata reference, the correct Java bytecode/resource-pack target, and absence of test classes, audio and embedded dependency JARs. Legacy artifacts use the reobfuscated output.

## Portable evidence

- [Official artifact catalog and checksums](compatibility/catalog.csv).
- [158 Iron method-surface checks](compatibility/iron-api.tsv).
- [470 SVC API checks](compatibility/voice-api.tsv): 438 pass; 32 predate the required initialization callback.
- [Client/server runtime matrix](compatibility/runtime.csv).
- [Six built artifact identities and SHA-256 checksums](compatibility/artifacts.json).
- [Compatibility and build guide](compatibility.md).

The API reports deliberately label their scope. They are not 628 separate game launches. Family and API-boundary tests do not establish compatibility with every possible combination of third-party mods, JVMs, microphones or operating systems.

## Remaining manual validation

The maintainer has already tested voice casting and approved the audio preset in the earlier 1.21.1 build. Recognition with additional speakers, and two-human multiplayer listening over a real SVC UDP connection on every port, remain pending. Automated channel/Opus/packet tests do not substitute for those checks. Voice effects remain **WIP and off by default**.

The Warden fixture uses vanilla vibration timing and an artificial player. One minimum-SVC run failed its fixed-tick anger assertion; a repeated run passed. The cause was not isolated, and a successful rerun does not establish that this fixture is deterministic. The sculk, packet/codec and casting checks passed in both runs.

Generated worlds, logs, caches and audition outputs are local-only. Keep regression source and original recordings. AI development and prompt disclosures are consolidated in [AI_PROMPTS.md](../AI_PROMPTS.md).
