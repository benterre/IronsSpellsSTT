# Near-match negatives

These recordings are for developer evaluation of generic recognition rules. They are never imported as player training, stored as rejection templates, or exposed in the Grimoire. Players need only record their own spell incantations, including arbitrary addon incantations.

Record five independent takes of each phrase below, plus ten short clips of ordinary gameplay conversation. These clips should never cast any spell. Use your normal microphone and pronunciation, with natural differences in speed and volume.

| Positive spell | Negative phrases |
|---|---|
| Fireball | firewall, eyeball |
| Firebolt | fireboat, lifeboat |
| Heal | hill, ill |
| Teleport | telephone, telegraph |
| Frostwave | first wave, frosted |
| Lightning Bolt | lighting bulb, lightning bug |
| Blood Step | footstep, doorstep |
| Gust | guest, just |
| Shield | field, sealed |
| Magic Missile | magic mist, massive muscle |

Save them under `incoming/negative`, for example `firewall_01.wav` through `firewall_05.wav`, `firstWave_01.wav`, and `gameplay_01.wav` through `gameplay_10.wav`. PCM16 WAV is preferred; the preparation tool supports the same stereo 44.1 kHz format as your spell recordings and derives mono 16 kHz copies while preserving originals.

Keep near-match takes 04–05 and gameplay clips 06–10 reserved for fresh final validation. The first three near-match takes and first five gameplay clips can be used for development checks. Spell cutoff calibration is derived from positive enrollment examples; negative phrases must not become casting templates. Exact homophones such as heel/heal contain no reliable acoustic difference and cannot be distinguished by speech matching alone.

Command listening requires an equipped spellbook. The evaluation prioritizes intended-cast recall, then reducing false casts while the book is equipped. Other speakers should enroll their own positive recordings; one speaker's results cannot establish accuracy for other voices or microphones.
