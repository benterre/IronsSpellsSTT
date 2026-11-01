# Real speech validation

Historical report: generated raw outputs were removed after Phase 5 at the user's request. Numerical summaries and regression sources remain; current behavior is described in [Phase 5](phase5-voice-effects.md) and [casting options](phase4-options.md).

These acoustic measurements describe STS 0.1.0-alpha.2. The recognizer's numerical rule remains unchanged in 0.2.0-alpha.1, whose equipment and remembered-spell policy is described in [the phase 4 guide](phase4-options.md). The user confirmed live casting works on 2026-10-14; other speakers and audible multiplayer delivery remain pending.

The release uses **positive spell enrollment only**. Negative recordings test generic recognition rules; they are not player training, rejection templates, or Grimoire content. Command recognition requires a spellbook equipped in Iron's native Curios spellbook slot. Removing it discards unfinished commands, and the server independently rejects no-book requests.

With seven enrollment takes per spell, the chosen rule correctly accepted **27/30 prepared development queries** and **25/30 untrimmed queries through runtime segmentation**, with zero wrong casts in both. The tested generic alternatives did not improve this tradeoff. Similar ordinary words still cause false casts while a book is equipped. These are recordings from one speaker; other voices and microphones remain untested.

## Recordings and preparation

The user supplied 100 recordings: ten takes each for ten spells. Original files remain unchanged in the incoming folder. All files were stereo PCM16 WAVs at 44.1 kHz, with durations from 0.894 to 2.015 seconds and a mean of 1.320 seconds.

The Java-only preparation tool downmixes stereo, uses windowed-sinc resampling to 16 kHz, and removes recording-edge silence. It preserves 80 ms before and 120 ms after the energy boundary. Prepared durations range from 0.340 to 0.980 seconds, with a mean of 0.551 seconds. No recording exceeded the three-second runtime limit. Derived audio stays in the ignored `recordings/prepared` folder; no private audio is included in the jar or Git.

Takes 01–07 supplied enrollment templates. Takes 08–10 supplied 30 evaluation utterances. No numerical cutoff search used these evaluation labels. However, the baseline results informed the architecture revision, so these recordings are development holdouts rather than an untouched final validation set.

## Baseline failure and correction

The initial implementation compared speech only with equipped spells. Although it accepted 21 of 30 correct requests without accepting a wrong spell when all ten spells competed, its unknown rejection was unsafe: an unequipped known word was often reassigned to another equipped spell.

| Check | Initial matcher | Revised matcher |
|---|---:|---:|
| Correct accepted spell | 21/30 | 27/30 |
| Wrong accepted spell | 0/30 | 0/30 |
| Uncertain recordings rejected | 9/30 | 3/30 |
| Known unequipped spell, nine other candidates | 13/30 false casts | 0/30 false casts |
| Single wrong equipped spell | 249/270 false casts | 0/270 false casts |

The revision searches compact descriptors of all trained incantations. The best eight acoustic candidates, together with equipped spells, receive full DTW comparison. Equipment eligibility is checked after the global winner is identified. A known unequipped incantation therefore cannot be relabeled as the nearest equipped spell.

Per-spell distance cutoffs come from training-only leave-one-out nearest distances. The fixed rule uses 1.6 times their 90th percentile, bounded to 0.004–0.08. The runner-up ratio remains 0.78; the minimum margin derives from the training median. These distances are not confidence probabilities. The remaining uncertain recordings were two lightning-bolt takes and one teleport take.

Aggregate evidence: baseline evaluation, revised evaluation.

## Generic tuning and negative evaluation

The supplied negative dataset contains 100 near-match recordings (20 phrases, five takes each) and ten longer gameplay/conversation clips. Near-match takes 01–03 and gameplay clips 01–05 were development data. Near-match 04–05 and gameplay 06–10 were reserved until the numeric rule was frozen. Original files were opened read-only; SHA-256 checks confirmed all 210 source WAVs remained unchanged, totaling 81,582,272 bytes.

Nine generic profiles compared dynamic-feature weighting, time-warp penalties, duration bounds, distance/runner-up calibration and two-neighbor consensus. Each profile used positive enrollment only. The selection prioritized correct intended casts and avoiding wrong positive casts, then fewer false casts. The [complete tuning table](generic-recognition-tuning.md) records every profile. The baseline remains frozen: unit delta weight, no warp penalty, duration ratio 0.55–1.8, nearest positive example, training-only cutoff multiplier 1.6 and runner-up ratio 0.78. No spell-name dictionary, per-word exception or speaker-specific coefficient was added.

| Development check, seven enrollment takes | Frozen rule | Two-neighbor alternative |
|---|---:|---:|
| Prepared positive queries | 27 correct / 0 wrong / 3 rejected | 27 correct / 0 wrong / 3 rejected |
| Prepared near-match negatives | 30/60 false casts | 31/60 false casts |
| Untrimmed positive queries through runtime VAD | 25 correct / 0 wrong / 5 rejected | 25 correct / 1 wrong / 4 rejected |
| Streaming near-match negatives | 19/39 emitted utterances; 19/60 files | 18/39 emitted utterances; 18/60 files |
| Streaming gameplay, 90.31 s | 2/24 utterances; 1/5 files | 2/24 utterances; 2/5 files |
| Known unequipped positive queries | 0/30 false casts | 1/30 false casts |
| One wrong equipped spell | 0/270 false casts | 1/270 false casts |

Twenty-one development near-match files produced no VAD utterance. They count as no command in file-level totals, not as successful acoustic rejection of an emitted query. Long gameplay files were replayed as mono 48 kHz, 960-sample/20 ms frames through the actual runtime segmenter, with a simulated 250 ms PTT-idle finish. The harness runs a virtual cadence without sleeping, so it does not measure microphone, SVC noise processing, network or game tick latency.

The consensus variant incorrectly accepted the short segmented `teleport_10.wav` query as Fireball, so its small reduction in near-match false casts did not justify release. Detailed evidence: streaming baseline, streaming consensus, isolated near matches.

The user prioritizes reliable intended casts while the spellbook is equipped and accepts some false casts in that state. No-book command audio exits before frame copying or DSP; explicit Grimoire enrollment remains possible. Negative recordings are never required from players or addon authors. Each new player derives their templates and score bounds from their own positive recordings. This design accommodates arbitrary addon incantations, but this one-speaker experiment cannot establish cross-speaker accuracy.

## Reserved validation after freezing the rule

The reserved negative recordings were first decoded after selecting and freezing the rule above. They did not change any numeric parameter. Two banks show the evaluation setting with seven enrollment takes and the normal ten-take enrollment setting.

| Streaming check | Seven-take bank | Ten-take bank |
|---|---:|---:|
| Original positive takes 08–10 | 25/30 correct, 0 wrong, 5 rejected; development holdouts | 30/30 correct, 0 wrong; enrolled self-queries |
| Reserved near-match clips | 8/40 files caused a cast | 6/40 files caused a cast |
| Near-match emitted utterances | 8/19 false casts | 6/19 false casts |
| Near-match files with no emitted utterance | 21/40 | 21/40 |
| Reserved gameplay, five files / 120.23 s | 0/10 emitted utterances cast | 0/10 emitted utterances cast |
| Gameplay files with no emitted utterance | 2/5 | 2/5 |
| Known unequipped positive queries | 0/30 false casts | 0/30 false casts |
| One wrong equipped spell | 0/270 false casts | 0/270 false casts |

The ten-take positive result is a format/segmentation sanity check, **not independent recognition accuracy**, because those same positive takes are in its bank. Negative recordings remain independent of enrollment in both banks. The gameplay sample is short and two clips produced no usable utterance; zero casts does not establish a zero false-activation rate during ordinary play.

The ten-take bank also produced 15/39 false casts on the 60 development near-match files and 2/24 on the five development gameplay files (90.31 s). That separate run uses the same frozen rule: ten-take development evidence.

Detailed file, utterance and eligibility tables: seven-take validation, ten-take validation. Reserved near matches still demonstrate substantial acoustic confusion when command listening is enabled. The equipped-book gate prevents this recognizer from processing normal speech with no book equipped; it does not improve acoustic discrimination while the book is equipped.

To reproduce the frozen reserved test without altering originals:

```powershell
.\gradlew.bat speechStreamEvaluation -PstreamSplit=validation -PstreamEnrollment=7 -PstreamOutput=build/speech-stream/validation-7
.\gradlew.bat speechStreamEvaluation -PstreamSplit=validation -PstreamEnrollment=10 -PstreamOutput=build/speech-stream/validation-10
```

## Runtime-shaped evaluation

The final alpha.2 evaluator used actual per-spell binary files, compact signature sidecars and cached full-feature sidecars. It retained only equipped full-feature templates and loaded inactive shortlist features from disk for each phrase. Raw PCM is needed only when building a missing feature cache or enrolling a take. The evaluator asserted that every retained template contained zero PCM. This reproduced the 27/30 correct result and both zero-false-cast results above. The client and evaluator now reuse the query features extracted for global search rather than extracting them again for full matching; an equality test verifies identical results.

| Path | Samples | Median | 95th percentile | Mean |
|---|---:|---:|---:|---:|
| Ten equipped spells, warm active feature cache | 30 | 0.792 ms | 1.239 ms | 0.937 ms |
| One equipped spell, inactive shortlist feature-sidecar reads | 270 | 5.143 ms | 6.674 ms | 5.300 ms |

On these 70 enrollment templates, full-feature primitives totaled **271,544 bytes**, global descriptors **58,240 bytes**, and retained raw PCM **zero**. Warm feature-cache matching read no raw PCM. First-time acoustic cache construction took **71.281 ms across all ten spell files**, excluding separate signature calibration; this construction transiently read at most 153,600 PCM bytes for one spell. Object headers, temporary extraction arrays and the offline harness's evaluation data are excluded; these are explicit storage counts, not process RSS. The global index stores 208 floats per take, or 8,320 bytes per spell at ten takes, plus identifiers and calibration metadata. Ten GUI waveform previews occupy about 5 KiB.

This run used Java 21.0.12 on Windows 11 amd64, Intel Core Ultra 9 285H. It includes the local disk-loaded shortlist path, but excludes microphone endpoint delay, Simple Voice Chat delivery and Minecraft server/network casting latency. The dataset was small and one-speaker; a large library may behave differently. The bounded priority queue keeps global shortlist ranking storage proportional to eight candidates rather than sorting an array of every trained spell.

Raw final evidence: alpha.2 build, tests and benchmarks. The previous feature-cache evaluation and earlier raw-PCM shortlist evaluation are preserved; re-extracting inactive features on every phrase had cost 39.323 ms median and 62.835 ms p95, which prompted the disk feature cache. The final synthetic benchmark measured eight one-second spells / 80 templates at 4.430 ms median / 6.618 ms p95, with 0.778 MiB of full-feature primitives. The frozen reserved streaming run with ten takes measured 1.060 ms median / 2.335 ms p95 matching and 0.017 ms median / 0.027 ms p95 per input frame/finish operation; endpoint waiting is excluded.

## Remaining live validation

These offline measurements do not establish a false-activation rate per hour of normal play. Similar or identical pronunciations are inherently difficult to separate acoustically, and distinct invented incantations have not been tested across other speakers. There is no basis for claiming that tuning on one speaker generalizes to every voice.

The user confirmed working live microphone casting with the previous release on 2026-10-14. Additional speakers, simultaneous audible multiplayer voice delivery and measured end-to-end latency remain pending. Server GameTests and voice API contract tests cover their separate documented scopes.

The alpha.2 Gradle run passed **46 tests with zero failures**. The suite verifies feature-only templates, immutable descriptors, deterministic bounded shortlist ranking, calibrated global matching, identical PCM/precomputed-feature results, WAV preparation and split selection, long-file segmentation, simulated PTT endpoints, library isolation, signature/full-feature cache invalidation and voice lifecycle behavior. Phase 4 adds six policy and packet tests; its current count is recorded in the validation report. Private audio is evaluated by the separate offline Java tools and is not required by the unit suite.
