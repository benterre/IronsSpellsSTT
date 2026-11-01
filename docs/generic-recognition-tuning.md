# Generic recognition tuning on development recordings

Historical report: generated raw outputs were removed after Phase 5 at the user's request. Numerical summaries and regression sources remain; current behavior is described in [Phase 5](phase5-voice-effects.md) and [casting options](phase4-options.md).

Negative recordings are evaluation data only. No negative examples are enrolled, imported, embedded in the mod or added to the Grimoire. The personal model uses positive spell recordings and derives score bounds from those recordings. All parameters here are generic; none names a spell, word or speaker.

The experiments used positive takes 01–07 for enrollment, positive 08–10 as development queries, and near-match takes 01–03 for evaluation. Reserved near-match 04–05 and gameplay 06–10 were not decoded. The 30 positive queries have already informed development choices, so they are not an untouched final test set.

| Variant | Delta weight | Warp penalty | Duration ratio | Cutoff multiplier / runner ratio | Positive correct | Wrong casts | Near-match false casts |
|---|---:|---:|---|---|---:|---:|---:|
| Baseline | 1 | 0 | 0.55–1.8 | 1.6 / 0.78 | 27/30 | 0 | 30/60 |
| Dynamics ×2 | 2 | 0 | 0.55–1.8 | 1.6 / 0.78 | 25/30 | 0 | 33/60 |
| Dynamics ×4 | 4 | 0 | 0.55–1.8 | 1.6 / 0.78 | 25/30 | 0 | 35/60 |
| Dynamics + elastic penalty | 4 | 0.003 | 0.65–1.55 | 1.6 / 0.78 | 25/30 | 1 | 35/60 |
| Elastic alignment | 1 | 0.003 | 0.65–1.55 | 1.6 / 0.78 | 26/30 | 1 | 34/60 |
| Larger warp penalty | 1 | 0.01 | 0.55–1.8 | 1.6 / 0.78 | 26/30 | 0 | 35/60 |
| Stricter cutoff, looser runner ratio | 1 | 0 | 0.55–1.8 | 1.35 / 0.82 | 26/30 | 1 | 25/60 |
| Intermediate cutoff, looser runner ratio | 1 | 0 | 0.55–1.8 | 1.4 / 0.82 | 26/30 | 1 | 27/60 |
| Mean of two nearest positive examples | 1 | 0 | 0.55–1.8 | 1.6 / 0.78 | 27/30 | 0 | 31/60 |

The two-neighbor variant also uses the mean of two nearest examples when deriving training-only leave-one-out calibration. All other variants use the nearest example. Cutoff and margin floors remain unchanged. Every query competes with all ten registered positive spell classes in this isolated test; production additionally uses compact global shortlisting and server ownership checks.

Baseline wins the isolated intended-cast-first comparison: no tested variant improved correct spell requests while maintaining zero wrong positive casts and reducing near-match false casts. Stronger dynamics and penalties did not justify release. The original 48 kHz/VAD streaming comparison also rejected two-neighbor consensus: baseline accepted 25 correct positive requests with zero wrong casts; consensus accepted 25 correct requests and one wrong cast. Known-unequipped checks were 0/30 and 0/270 for baseline, versus 1/30 and 1/270 for consensus. Consensus reduced development near-match false casts only from 19/39 to 18/39 emitted utterances, while gameplay remained 2/24 for both.

**The numerical rule is frozen at baseline:** delta weight 1, zero warp penalty, duration ratio 0.55–1.8, one nearest enrollment example, positive-only cutoff multiplier 1.6 and runner-up ratio 0.78. Reusing already-extracted query features and restoring the baseline DTW fast path improve CPU/allocation costs without changing score rules. Reserved validation follows this freeze; its results cannot be used to retune the model and then be called untouched validation.

Streaming development evidence: baseline summary, consensus summary.

The residual baseline 30/60 near-match false casts are a material limitation. Global recognition prevents a known unequipped spell from being relabeled as an equipped one, but it does not establish reliable rejection of acoustically similar ordinary words. Distinct personal incantations may behave differently; that has not been measured across other speakers. No universal accuracy claim follows from this one-speaker dataset.

Numerical evidence: first four profiles, alignment penalties, calibration tradeoffs, two-neighbor consensus. Reported CPU wall totals in those files are short experiment timings with compilation/warmup differences, not comparative microbenchmarks or machine CPU utilization. Runtime memory remains feature-only; each variant uses the same stored MFCCs and compact descriptors.
