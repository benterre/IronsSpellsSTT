# Phase 4: remembered spells and casting options

STS 0.2.0-alpha.1 remembers the highest spell level the server has encountered in each player's inventory, hands or equipped book. A scroll does not have to be equipped, selected or recorded again after an ink upgrade. Once its upgraded form enters inventory, its level is remembered after removal and across logout, death and respawn. Spell IDs and levels are stored with the world's player data; voice recordings remain local to the client.

The existing Grimoire remains the only STS screen. It includes remembered spells after their scrolls leave inventory, shows their maximum known level and keeps the same recording/deletion controls. No negative examples are required. Each recording belongs to a spell ID, so changing its unlocked level reuses the same incantation.

## One configuration file

Minecraft creates `config/sts/options.toml` on startup:

```toml
grimoireless_cast_any_spell = true
grimoireless_cast_at_max_level = false
cast_without_wand = false
```

In singleplayer, edit this file in that instance's game directory. In multiplayer, edit the server's file: the server sends its authoritative options and remembered levels to clients. Client-side edits cannot grant casting rights or higher spell levels. Restarting applies edits; NeoForge's normal config reload also propagates changed options on the next player tick. Install the updated STS jar on both sides because its state-sync protocol has changed.

| Option | Default | Result for a remembered spell absent from the equipped book |
|---|---|---|
| `grimoireless_cast_any_spell` | `true` | Cast at exactly level 1 with an equipped book or a held casting implement. |
| `grimoireless_cast_at_max_level` | `false` | Enable unprepared casting at the highest remembered level, bounded by the spell's current maximum. Overrides level-one mode. |
| `cast_without_wand` | `false` | Enable unprepared casting with neither an equipped book nor a held implement. Level 1 unless max-level mode is enabled. |

The options are independent: max-level mode enables unprepared casting even if the first option is false; equipment-free mode enables level-one casting if both other options are false. When all three are false, the original book-required native wheel/scroll behavior is retained. Open Minecraft screens and the Grimoire's existing casting toggle continue to suppress commands; explicit recording remains available.

## Levels and mana

Spells actually prepared in the equipped book keep Iron's native level calculation and normal mana cost. The optional modes do not turn a level-one book slot into a higher-level spell merely because a better scroll was encountered.

Unprepared casts use **150% of the normal mana cost at the chosen level**, rounded up to a whole mana point. A normal cost of 20 becomes 30. Level-one mode ignores the higher scroll level; max-level mode uses the remembered level. Insufficient mana rejects the cast with an action-bar message. Max-level mode does not silently downcast to a cheaper level.

Unprepared casts use Iron's mana-consuming spellbook source, normal cooldowns, native learning requirements, spell preconditions and cast events. Starting a cast does not pre-charge mana. Native completion or each continuous pulse applies the surcharge. Canceling before the first effect costs no mana; already completed pulses retain their cost. Native recast and creative-mode rules remain intact. Unlock scrolls are not consumed by an unprepared mana cast.

The server accepts only a spell ID from recognition. It resolves the remembered level, equipment, policy and mana itself. Local recordings and client-edited files do not unlock a spell on a server. The server also cancels an unfinished unprepared cast if its funding or permitted equipment disappears.

## Casting implements and addons

Iron's casting-implement component, native CastingItem/staff hierarchy and held native wheel containers are recognized in either hand. A scroll or merely held book does not count as a wand. Addons using Iron's registry and spell-container system require no incantation dictionary or spell-name list.

An addon with a custom right-click caster outside those markers can declare it in the `sts:casting_implements` item tag. A data pack can place this file at `data/sts/tags/item/casting_implements.json`:

```json
{"replace":false,"values":["my_addon:custom_wand"]}
```

The tag grants only implement status: it does not unlock spells, supply mana, waive learning or bypass cooldowns.

## Memory and migration

All raw recordings stay on disk. Expanding casting rights to remembered spells does not expand the permanent full-feature audio cache to every unlocked spell. The small native equipped set remains cached; the compact index shortlists up to eight other spell files for temporary matching. The Grimoire retains only the viewed page's compact waveform previews.

Old STS recordings remain usable. Server-side inventory history begins with this version, so a spell whose scroll/book was discarded before updating must be presented to the server once to establish its unlock and level. Recordings do not have to be repeated. A new world or different server has its own spell history, even when the client reuses the same voice files.

The user confirmed live casting with the previous release on 2026-10-14. Phase 4's new policy, pricing, progression and protocol are covered by automated checks; additional voices and third-party gameplay testing remain pending. Version-specific results and dependency combinations are recorded separately in the [compatibility report](version-compatibility.md).

Both Iron and Simple Voice Chat dependency ranges have no upper bound. Future releases are accepted within Minecraft 1.21.1; the report's last tested version is a test boundary, not a loader restriction. Only minimum working versions and specifically tested historical failures are excluded.
