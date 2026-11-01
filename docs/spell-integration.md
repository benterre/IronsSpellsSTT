# Iron's spell integration

Historical report: generated raw outputs were removed after Phase 5 at the user's request. Numerical summaries and regression sources remain; current behavior is described in [Phase 5](phase5-voice-effects.md) and [casting options](phase4-options.md).

## Phase 4

STS 0.2.0-alpha.1 adds persistent, server-observed spell levels and synchronized server policy. The player must encounter a scroll or spell container in inventory to establish remembered rights; audio files and request IDs cannot grant them. Prepared book spells retain their normal native level/cost. Remembered unprepared spells use exactly level 1 or the highest remembered level, according to `config/sts/options.toml`, and pay 150% mana rounded up through `SpellOnCastEvent`. The server still checks native learning, enabled state, casting preconditions, mana and cooldowns.

Native `attemptInitiateCast` starts the unprepared cast with mana-consuming `CastSource.SPELLBOOK` and a distinct casting-slot marker. Its native completion/continuous pulses charge the modified cost; cancellations before the first effect do not charge. The player-tick guard cancels if permitted equipment or funding disappears. The request payload remains only a bounded spell ID; a separate server-to-client payload carries authoritative policy and remembered levels. Client settings cannot widen server rights.

The twelve current GameTests passed with and without SVC using the four-addon pack. The original strict gate is still tested explicitly with all optional modes disabled. See [phase 4 behavior and migration](phase4-options.md), current server evidence, and without-SVC evidence. The earlier records below describe the previous alpha.

STS targets Minecraft 1.21.1 and Java 21, compiling against Iron's Spells 'n Spellbooks 1.21.1-3.16.3. Iron's is mandatory on both sides; installed versions use the open ranges and historical exceptions in the [compatibility report](version-compatibility.md). Simple Voice Chat provides the client audio path when installed; recognition itself remains local and sends only a spell resource ID to the server.

## Automatic addon discovery

`SpellBridge` reads the live `SpellRegistry.REGISTRY` and the player's `SpellSelectionManager`, including options added by other mods' `SpellSelectionEvent` handlers. Spell IDs, names, icons, levels, and ownership come from Iron's APIs. STS has no list of addon IDs or spell names to maintain. Mods registering ordinary Iron spells and spell containers participate automatically.

The Grimoire shows enabled, learned spells in the player's inventory and equipped spell wheel. An inventory scroll or unequipped book can be enrolled before equipping it. **Command listening requires a real spellbook equipped in Iron's Curios spellbook slot.** A book merely held in either hand or carried in inventory does not qualify. With a book equipped, a current native wheel option or matching held native scroll can be voice-cast. Without it, the candidate list is empty and every server voice-cast request is rejected, including requests for imbued weapons or held scrolls. “Available for training” does not grant casting rights. Iron's separate learning requirement is also preserved where its school requires learning. STS recordings remain available locally after temporarily removing the item.

## Server authority

`RequestCastPayload` contains one bounded resource ID, at most 256 characters. It carries no client-selected level, slot, cast source, mana cost, or audio. Requests execute on the server thread and are limited to one per four game ticks per player. The server independently requires a nonempty Curios spellbook-slot item implementing Iron's public `ISpellbook` marker, which also supports addon spellbooks. Dead and spectator players cannot cast; requests during an active cast are ignored so duplicate recognitions cannot cancel it.

After the equipped-book gate, the server resolves that ID against current equipment and invokes `Utils.serverSideInitiateQuickCast`. Iron's native code then checks mana, cooldowns, learning, adventure restrictions, recasts, target preconditions, and cancellable cast events. Level bonuses and casting animations use the original lifecycle. Held `Scroll` subclasses use their native `use` path and real stack, preserving consumption at cast completion. A custom addon item outside Iron's native wheel/Scroll semantics needs its own item integration; registering its spell remains automatic.

## Development verification

Run `gradlew.bat -PgameTests runGameTestServer`; add `-Paddons` to load the downloaded test addons. These development-only tests are excluded from the release jar:

| Test | Evidence checked |
| --- | --- |
| Forged and unequipped spells | Unknown IDs, unlearned spells, and inventory-only scrolls cannot cast; owned spells remain trainable |
| Native prerequisites | Spectators, zero mana and cooldown reject; valid spellbook starts with server-derived level/source; duplicate requests preserve cast; cancellation clears it |
| Held scroll completion | With a book equipped, native server ticks consume exactly one scroll and do not add a spellbook cooldown |
| Registry/addon discovery | Registry IDs are complete; with a book equipped every installed enabled addon spell is found through a generic imbued container |
| Required spellbook gate | Held scrolls, imbued sources, held/inventory books, repeated requests and book removal cannot bypass a real equipped book; enrollment remains available |

GameTests use synthetic server players and exercise the Java bridge. They do not establish microphone recognition accuracy, audible multiplayer voice delivery, or voice effects. Check actual test results in the project validation record; source availability alone is not a passing test.

### Recorded run: 2026-10-13

`gradlew.bat -PgameTests -Paddons runGameTestServer` completed with exit code 0 and **all four required tests passed**. The test batch took 3.780 seconds after server startup. The live registry contained 127 enabled spells: 110 from Iron's, 2 from Spells Gone Wrong, 8 from Unstable Spellbooks, and 7 from Wind's Spellbooks. Generic container discovery checked every enabled addon spell. The `sts` voice chat plugin registered, the Simple Voice Chat UDP server started on its configured ephemeral test port (59735), and the game server shut down cleanly.

Synthetic connections use NeoForge's official `NetworkRegistry.configureMockConnection` test hook. This supplies negotiated mod channels to an in-process client fixture; it does not test a real client's network handshake or deliver microphone audio. The initial fixture failed before spell assertions because a vanilla-style synthetic connection had no negotiated Iron channels. The corrected fixture changes development tests only.

The expanded pack also passed all four tests with GTBC's Spellbooks 3.0.0 and SpellLib 2.2.0. Registry discovery found **144 spells**, adding 17 GTBC spells to the counts above. A separate run using `-PnoVoiceChat` passed the same four tests with the voicechat mod and API absent. Logs for both runs are linked in [the final validation record](validation.md).

### Required-book gate verified: 2026-10-14

After the command-listening requirement was narrowed to equipped spellbooks, `gradlew.bat -PgameTests -Paddons runGameTestServer` completed with exit code 0 and **all five required tests passed**. The batch took 1.733 seconds after startup. The new fifth test rejects twenty repeated no-book requests, preserves held scrolls, rejects imbued/staff-style sources without a book, distinguishes held/inventory books from a Curios-equipped book, and verifies that removing the book disables candidate discovery and server casts. Owned spell enrollment remains visible without the book. The scroll completion and addon discovery tests now explicitly equip a book first. The live registry still contained 144 spells, Simple Voice Chat started its UDP server on test port 57362, and the server shut down cleanly. See the captured test log.

The equivalent `gradlew.bat -PgameTests -Paddons -PnoVoiceChat runGameTestServer` run also completed with exit code 0 and **all five tests passed**, in 1.922 seconds after startup. Both `voicechat` and `voicechat_api` were absent from its runtime; native casting, all 144 registry spells, the new equipped-book gate, and clean shutdown remained verified. See the optional-voice-chat test log.

## Primary API references

- [Official registry](https://github.com/iron431/Irons-Spells-n-Spellbooks/blob/1.21/src/main/java/io/redspace/ironsspellbooks/api/registry/SpellRegistry.java)
- [Official selection/extension event API](https://github.com/iron431/Irons-Spells-n-Spellbooks/blob/1.21/src/main/java/io/redspace/ironsspellbooks/api/magic/SpellSelectionManager.java)
- [Official quick-cast entry point](https://github.com/iron431/Irons-Spells-n-Spellbooks/blob/1.21/src/main/java/io/redspace/ironsspellbooks/api/util/Utils.java)
- [Official native cast prerequisites and lifecycle](https://github.com/iron431/Irons-Spells-n-Spellbooks/blob/1.21/src/main/java/io/redspace/ironsspellbooks/api/spells/AbstractSpell.java)
- [Official scroll lifecycle](https://github.com/iron431/Irons-Spells-n-Spellbooks/blob/1.21/src/main/java/io/redspace/ironsspellbooks/item/Scroll.java)
- [Official addon-compatible spellbook marker](https://github.com/iron431/Irons-Spells-n-Spellbooks/blob/1.21/src/main/java/io/redspace/ironsspellbooks/api/item/ISpellbook.java)
- [NeoForge 1.21.1 GameTest documentation](https://docs.neoforged.net/docs/1.21.1/misc/gametest/)
