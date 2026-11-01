# Simple Voice Chat integration

Experimental processed-voice replay is **WIP and off by default** in publication builds. Ordinary template recognition and native casting do not require enabling it.

STS 0.3.0-alpha.2 uses Simple Voice Chat's public API. Its hotfix checks category metadata against actual wire limits before registration. The development pair is Iron 3.16.3 / SVC 2.6.24; the supported ranges remain open to future versions. SVC is required for microphone capture, enrollment and voice commands. Common server code still loads safely without it. See [versions](version-compatibility.md) and [voice effects](phase5-voice-effects.md).

## Normal speech

SVC owns microphone capture, noise processing, activation/push-to-talk, Opus encoding and proximity/group delivery. STS observes `ClientSoundEvent` at the lowest priority, accepting signed mono 48 kHz PCM in 960-sample frames. It never cancels the event or replaces its audio. Earlier SVC effects remain visible to recognition. STS opens no microphone or voice socket.

A bounded client worker segments and recognizes speech locally. Mute, disable, cancellation, disconnect, screens and loss of the permitted casting context discard unfinished commands. A short frame-idle timeout handles releases that emit no empty audio frame. Explicit Grimoire enrollment can continue while its screen is open. Recordings remain in local per-spell disk files; the Grimoire reads only the viewed page's waveforms.

Ordinary requests send only a spell ID. The server determines equipment, remembered level, native learning, mana, cooldown and casting behavior using Iron's lifecycle. Its casting and broadcast policies are synchronized to clients. Equipment follows [options.toml](phase4-options.md): a book or implement by default, or neither when explicitly allowed.

## Strong-spell replay

Only eligible recognized strong casts add one bounded phrase clip to their Minecraft request, at most 24,003 audio bytes. This reuses the recognizer's utterance; there is no second continuous recorder or idle replay buffer. No server audio files are written.

The server prepares lower pitch, stronger diffuse reverb with a half-second tail and loud normalization on one lazy Java worker. The approved STS 0.3.0 preset also plays the voice 20% slower than the first audition, using resampling that further deepens the pitch. On successful Iron cast completion, SVC's positional channel and native Opus audio player replay the result to connected listeners within a 50-block sphere, including the caster. Other dimensions are excluded. Volume and deafening remain under SVC's control; the category is **Spell voices**. Normal speech remains unchanged. The release is installed in the test instance.

Disabling `broadcast_strong_spells` skips clip production/uploads, DSP, codecs, playback and vibrations. Maximum-only policy and spell eligibility exclude irrelevant audio work. Pending/playing work is bounded, cancelled on logout or failed/cancelled casts, and released on server shutdown. [The Phase 5 guide](phase5-voice-effects.md) records timing and RAM bounds.

## Shortcut and verification

G opens the Grimoire in gameplay or over a nonempty inventory item. JEI's active text-field focus strategy protects private creative search boxes and registered text widgets. The handler runs at low priority and receives no cancelled events, allowing earlier overlay handlers to consume their input. Typing has priority over the shortcut. No JEI dependency is introduced.

The shipping capture adapter was exercised against all 43 downloaded SVC release APIs. All server methods used for channels, playback, encoders, categories, filters and lifecycle are present in those APIs. Native dedicated-server checks exercise actual Opus encode/decode and paced playback, including the oldest supported pair. The client probe exercises real text widgets and Grimoire rendering. Automated tests do not establish audible delivery to human listeners; the two-client listening check remains pending.

Generated test output and downloaded compatibility caches were removed at the user's request. Regression sources and pinned development dependencies remain; fetch/bootstrap and Gradle tasks can regenerate the checks.
