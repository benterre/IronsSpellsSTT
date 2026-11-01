# README screenshots

`grimoire-empty.png` and `grimoire-recordings.png` are unedited captures of the actual Minecraft Grimoire screen from `GrimoireClientProbe`, captured on 2026-11-01.

The populated page uses a demonstration catalog and synthetic waveform fixtures. No private player recording is distributed. These demonstrate the interface rather than recognition accuracy or gameplay-world behavior.

Regenerate with Java 21 and `gradlew.bat -PclientProbe -PprobeScale=2 runClientProbe`. The probe checks the inventory text-focus shortcut and writes both images under `run/client-probe/screenshots/`.
