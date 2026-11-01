# Minecraft and loader compatibility

Audit date: **2026-11-01**. STS uses ordinary **JARs per Minecraft/loader family**, as agreed with the maintainer. Install one matching STS JAR on the client and server.

## Families

| Minecraft | Loader runtime used in validation | Java | Iron runtime example | SVC runtime example | Artifact prefix |
|---|---|---:|---|---|---|
| 1.18.2 | Forge 40.3.12 | 17 | 1.18.2-1.1.4.3 | 2.6.27 | `sts-forge-1.18.2-` |
| 1.19.2 | Forge 43.5.0 | 17 | 1.19.2-3.4.0 | 2.6.27 | `sts-forge-1.19.2-` |
| 1.20.1 | Forge 47.4.0 | 17 | 1.20.1-3.16.3 | 2.6.27 | `sts-forge-1.20.1-` |
| 1.20.1 | Legacy NeoForge 47.1.106 | 17 | 1.20.1-3.4.0 | 2.4.32 | `sts-neoforge-1.20.1-` |
| 1.21 | NeoForge 21.0.167 | 21 | 1.21-3.4.4 | 2.6.27 | `sts-neoforge-1.21-` |
| 1.21.1 | NeoForge 21.1.252 | 21 | 1.21.1-3.16.3 | 2.6.24 | `sts-neoforge-1.21.1-` |

These are tested combinations, not requirements to install only those dependency releases. A dependency must also support the chosen Minecraft/loader and the other libraries in the pack. Follow that Iron release's own dependency metadata. In particular, Minecraft 1.21 uses the historical **Curios API Continuation** dependency; substituting a newer 1.21.1-only Curios or Player Animator build is insufficient.

No official Iron release for Fabric or Quilt was found, so those loaders cannot satisfy the mandatory dependency. Legacy NeoForge's official Maven catalog starts at Minecraft 1.20.1. There is no supported 1.19.2 NeoForge target despite isolated publisher tags.

## Dependency coverage

The official catalogs supplied **158 distinct Iron artifacts** and **470 SVC artifacts** across the relevant games/loaders. Modrinth and CurseForge entries were reconciled by filename. Modrinth downloads were checked against publisher SHA-512; CurseForge-only downloads were checked against publisher file lengths and their observed SHA-256 recorded. The portable reports contain public artifact identities and checksums, without local paths or recordings.

- **158/158 Iron native API surfaces** matched one of the implemented bindings: enum-based spells, older registry spells with item NBT, mutable containers, or immutable slot containers.
- **438/470 SVC API contracts** passed the shipping capture/availability and playback API checks. The other 32 lack `ClientVoicechatInitializationEvent` and are outside the supported range.
- The oldest compatible SVC catalog builds are **2.3.2** for Forge 1.18.2/1.19.2, **2.4.9** for Forge/legacy NeoForge 1.20.1, **2.5.15** for NeoForge 1.21, and **2.5.20** for NeoForge 1.21.1.

**Static/API checks are not full game launches.** Actual client and dedicated-server runs cover all six families and representative API boundaries, including Iron **1.0.3, 1.2.1, 2.0.0, 2.1.0, 3.0.0, 3.1.3, 3.4.0, 3.4.4 and 3.16.3** across their applicable games. The oldest Iron/SVC boundary for 1.19.2 was tested together. The [validation report](publication-validation.md) records the exact scope. This is not a claim that every Cartesian combination of dependencies/addons received a separate client/server run.

SVC checks exercise PCM callbacks, mute/disable/disconnect handling and unchanged original audio. Native server probes also use the release's real Opus encoder/decoder, paced audio player and Minecraft buffer mappings for the shipping volume-category packet. The latter guards against the earlier Black Hole packet-encoding disconnect.

The 1.21.1 build also retains the earlier [dependency-axis runtime investigation](version-compatibility.md). Iron **1.21-3.8.0** remains excluded there because that artifact failed native runtime checks. Other compatible dependency versions have **no upper version ceiling**. Future binary-compatible releases are allowed; unknown breaking APIs cannot be guaranteed in advance.

Historical dependencies can emit their own recipe, loot, animation or version-check warnings. Passing STS's casting/UI/codec tests does not certify every feature of those upstream releases or an arbitrary modpack. Two-human multiplayer listening and recognition with additional speakers remain manual follow-up work.

## Building the families

Use Java 21 to run Gradle and PowerShell 7.4+ for the helper scripts. Gradle provisions a Java 17 toolchain for legacy targets. Dependencies are checksum-pinned for reproducible development; these pins do not constrain players to the compiler's dependency versions.

```powershell
# NeoForge 1.21.1
./tools/bootstrap.ps1 -IncludeAddons
./gradlew.bat build

# Forge: substitute 1.18.2, 1.19.2 or 1.20.1
./tools/prepare-port-dependencies.ps1 -MinecraftVersion 1.20.1 -Loader forge
./gradlew.bat -p ports/legacy '-PtargetMc=1.20.1' build

# Legacy NeoForge 1.20.1
./tools/prepare-port-dependencies.ps1 -MinecraftVersion 1.20.1 -Loader neoforge
./gradlew.bat -p ports/legacy '-PtargetMc=1.20.1' -PlegacyNeo build

# NeoForge 1.21
./tools/prepare-port-dependencies.ps1 -MinecraftVersion 1.21 -Loader neoforge
./gradlew.bat -p ports/neo build

# Build, inspect and collect all six artifacts; does not upload anything
./tools/build-release.ps1
```

Quote dotted `-P` arguments in PowerShell. On other systems use `./gradlew` (or `bash ./gradlew` if the executable bit was not preserved). The legacy plugin automatically produces the **SRG-reobfuscated** release JAR in `build/libs`; `build/devlibs` is for development.

To run development-only client/server probes, add `-PgameTests -PclientProbe runGameTestServer runClientProbe`. For the older mutable-container legacy NeoForge test fixture also add `-PmutableIron`. Production JARs exclude the probes. The pinned `oldest`, `v20`, `v2`, `v3` and `v31` profiles are selected with `-PdependencyProfile=...`; `tools/test-port-boundaries.ps1` runs their native/UI checks on Windows.

## Sources

- [Iron Modrinth catalog](https://api.modrinth.com/v2/project/s4OWxYQQ/version), [Iron CurseForge files](https://www.curseforge.com/minecraft/mc-mods/irons-spells-n-spellbooks/files), [SVC catalog](https://api.modrinth.com/v2/project/9eGKb6K1/version).
- [Legacy NeoForge versions](https://maven.neoforged.net/releases/net/neoforged/forge/maven-metadata.xml).
- [Curios API Continuation](https://modrinth.com/mod/curios-continuation).
- [NeoForge metadata](https://docs.neoforged.net/docs/1.21.1/gettingstarted/modfiles/), [Forge metadata](https://docs.minecraftforge.net/en/1.20.x/gettingstarted/modfiles/), [ModDevGradle legacy support/reobfuscation](https://github.com/neoforged/ModDevGradle/blob/main/LEGACY.md).
