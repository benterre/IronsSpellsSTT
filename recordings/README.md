# Real speech validation

Use these ten Iron spells: Fireball, Firebolt, Heal, Teleport, Frostwave, Lightning Bolt, Blood Step, Gust, Shield and Magic Missile. Say the spell name or your own invented incantation consistently. Keep different spells acoustically distinct.

Record ten independent takes for each spell, preferably **mono signed PCM16 WAV** at **16 kHz or 48 kHz**, each 0.1–3 seconds with little leading/trailing silence. The Java preparation tool also supports ordinary stereo 44.1 kHz WAV recordings. Use your gameplay microphone. Include slight natural speed/volume variation; do not duplicate the same WAV ten times.

The supplied recordings use flat filenames in `incoming`: `fireball_01.wav` through `fireball_10.wav`, likewise `firebolt`, `heal`, `teleport`, `frostwave`, `lightningBolt`, `bloodStep`, `gust`, `shield`, and `magicMissile`. Run preparation to derive mono 16 kHz copies and a manifest under `prepared`, preserving every original. For other spells, make your own manifest mapping registry IDs to folders of ten already prepared files and pass `-PdatasetManifest=path/to/manifest.tsv`.

```powershell
.\gradlew.bat speechPrepare
.\gradlew.bat speechDataset
.\gradlew.bat speechRuntimeEvaluation
.\gradlew.bat speechStreamEvaluation
```

The offline Java evaluator trains takes 01–07, holds out 08–10 and reports correct/incorrect/rejected counts and matching latency. The runtime evaluator uses real disk profile/signature files and the same global-shortlist/eligibility order as the client. The streaming evaluator feeds untrimmed originals through 20 ms audio frames, runtime VAD and a simulated PTT release; long gameplay files are segmented rather than rejected for exceeding three seconds. Its default split reads only development negatives. Missing recordings mean verification is incomplete, never a passing result. These are development tools; players train directly in the Grimoire without running them.

See [near-match negatives](NEGATIVES.md) for the requested negative word list and reserved validation takes. Negatives evaluate generic rules; they never become casting or rejection templates and have no Grimoire page. Final results and commands are recorded in [the speech report](../docs/real-speech-validation.md).

To import a prepared bank, exit Minecraft first and specify the exact local player UUID file:

```powershell
.\gradlew.bat speechDataset -PdatasetOperation=import "-PdatasetOutput=run/client/config/sts/voices/<uuid>.bin"
```

This writes an import bank. On next join STS splits it into per-spell files under `config/sts/voices/<uuid>/`, replacing those spells' recordings and keeping the import original as `<uuid>.bin.imported`. Preserve existing recordings before importing. The import tool accepts up to 128 spells per bank; normal in-game training has no aggregate spell limit. The files contain your voice and remain local. Realistic follow-up should include gameplay noise, changed microphones, unknown phrases and real client/server voice delivery. Offline matching does not prove that voice was audible or a live spell cast.
