package dev.sts.gametest;

import com.mojang.authlib.GameProfile;
import dev.sts.spell.SpellBridge;
import dev.sts.spell.SpellKnowledge;
import dev.sts.spell.UnpreparedCasting;
import dev.sts.config.CastOptions;
import io.netty.channel.embedded.EmbeddedChannel;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.UUID;
import java.util.stream.Collectors;

/** Development-only integration tests; excluded from the distributed mod jar. */
@GameTestHolder("sts")
@PrefixGameTestTemplate(false)
public final class SpellBridgeGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void rejectsForgedAndUnequippedSpells(GameTestHelper helper) {
        ServerPlayer player = mockSurvivalPlayer(helper);
        try {
            var spell = SpellRegistry.FIREBOLT_SPELL.get();
            var id = spell.getSpellResource();
            helper.assertTrue(!strictCast(player, ResourceLocation.fromNamespaceAndPath("sts", "not_registered")), "Unregistered ID was accepted");
            helper.assertTrue(!strictCast(player, id), "Unequipped spell was accepted");
            ItemStack inventoryScroll = new ItemStack(ItemRegistry.SCROLL.get());
            ISpellContainer.createScrollContainer(spell, 1, inventoryScroll);
            player.getInventory().setItem(9, inventoryScroll);
            helper.assertTrue(SpellBridge.availableSpells(player).stream().anyMatch(entry -> entry.id().equals(id) && !entry.castable()), "Owned inventory spell is unavailable for enrollment");
            helper.assertTrue(!strictCast(player, id), "Inventory-only scroll bypassed equipment rights");
            equipEmptySpellbook(player);
            SpellRegistry.getEnabledSpells().stream().filter(candidate -> candidate.requiresLearning()).findFirst().ifPresent(unlearned -> {
                ItemStack imbued = new ItemStack(Items.STICK);
                ISpellContainer.createImbuedContainer(unlearned, 1, imbued);
                player.setItemInHand(InteractionHand.MAIN_HAND, imbued);
                helper.assertTrue(!strictCast(player, unlearned.getSpellResource()), "Unlearned spell bypassed Iron's learning requirement");
            });
            helper.assertTrue(!MagicData.getPlayerMagicData(player).isCasting(), "Rejected request started a cast");
        } finally {
            removePlayer(player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeManaCooldownAndCastingChecks(GameTestHelper helper) {
        ServerPlayer player = mockSurvivalPlayer(helper);
        try {
            var spell = SpellRegistry.FIREBOLT_SPELL.get();
            var id = spell.getSpellResource();
            ItemStack book = new ItemStack(ItemRegistry.WIMPY_SPELL_BOOK.get());
            var container = ISpellContainer.create(1, true, true).mutableCopy();
            container.addSpellAtIndex(spell, 1, 0, true);
            setContainer(book, container.toImmutable());
            Utils.setPlayerSpellbookStack(player, book);
            helper.assertTrue(strictCandidates(player).contains(id), "Native Curios spellbook was not discovered");
            player.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
            helper.assertTrue(!strictCast(player, id), "Spectator was allowed to cast");
            player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            var magic = MagicData.getPlayerMagicData(player);
            magic.setMana(0);
            helper.assertTrue(!strictCast(player, id), "Zero mana bypassed Iron's checks");
            magic.setMana(100);
            magic.getPlayerCooldowns().addCooldown(spell, 200);
            helper.assertTrue(!strictCast(player, id), "Cooldown bypassed Iron's checks");
            magic.getPlayerCooldowns().clearCooldowns();
            helper.assertTrue(strictCast(player, id), "Authorized equipped spell did not begin casting");
            helper.assertTrue(magic.isCasting() && magic.getCastingSpellId().equals(id.toString()), "Native cast state does not match the requested spell");
            helper.assertTrue(magic.getCastSource() == CastSource.SPELLBOOK, "Cast source bypasses spellbook semantics");
            helper.assertTrue(magic.getCastingSpellLevel() == spell.getLevelFor(1, player), "Server did not resolve the equipped spell level");
            helper.assertTrue(!strictCast(player, id) && magic.isCasting(), "Duplicate voice request cancelled the active native cast");
            Utils.serverSideCancelCast(player, false);
            helper.assertTrue(!magic.isCasting(), "Native cancellation did not clear the cast");
        } finally {
            removePlayer(player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void heldScrollIsConsumedByNativeCompletion(GameTestHelper helper) {
        ServerPlayer player = mockSurvivalPlayer(helper);
        equipEmptySpellbook(player);
        var spell = SpellRegistry.FIREBOLT_SPELL.get();
        ItemStack scroll = new ItemStack(ItemRegistry.SCROLL.get(), 2);
        ISpellContainer.createScrollContainer(spell, 1, scroll);
        player.setItemInHand(InteractionHand.MAIN_HAND, scroll);
        helper.assertTrue(strictCast(player, spell.getSpellResource()), "Held scroll did not begin casting");
        var magic = MagicData.getPlayerMagicData(player);
        helper.assertTrue(magic.getCastSource() == CastSource.SCROLL, "Held scroll used a mana/cooldown cast source");
        helper.assertTrue(scroll.getCount() == 2, "Scroll was consumed before native completion");
        helper.runAfterDelay(80, () -> {
            try {
                helper.assertTrue(!magic.isCasting(), "Scroll cast did not finish through native server ticks");
                helper.assertTrue(scroll.getCount() == 1, "Native completion did not consume exactly one scroll");
                helper.assertTrue(!magic.getPlayerCooldowns().isOnCooldown(spell), "Native scroll cast unexpectedly added a spellbook cooldown");
            } finally {
                removePlayer(player);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void registryIncludesEveryAddonSpell(GameTestHelper helper) {
        var nativeIds = SpellRegistry.getEnabledSpells().stream().map(spell -> spell.getSpellResource()).collect(Collectors.toSet());
        helper.assertTrue(!nativeIds.isEmpty(), "Iron's spell registry did not initialize");
        helper.assertTrue(SpellBridge.registeredSpellIds().size() == nativeIds.size() && nativeIds.containsAll(SpellBridge.registeredSpellIds()), "Registry discovery omitted spells");
        var counts = nativeIds.stream().collect(Collectors.groupingBy(ResourceLocation::getNamespace, Collectors.counting()));
        LOGGER.info("STS integration registry counts: {}", counts);
        String requiredNamespaces = System.getProperty("sts.test.requiredAddonNamespaces", "");
        for (String namespace : requiredNamespaces.split(",")) {
            if (!namespace.isBlank()) {
                helper.assertTrue(counts.containsKey(namespace), "Required addon spell namespace absent: " + namespace);
            }
        }
        ServerPlayer player = mockSurvivalPlayer(helper);
        try {
            equipEmptySpellbook(player);
            for (var spell : SpellRegistry.getEnabledSpells()) {
                if (spell.getSpellResource().getNamespace().equals("irons_spellbooks")) {
                    continue;
                }
                magicLearn(player, spell);
                ItemStack imbued = new ItemStack(Items.STICK);
                ISpellContainer.createImbuedContainer(spell, 1, imbued);
                player.setItemInHand(InteractionHand.MAIN_HAND, imbued);
                helper.assertTrue(strictCandidates(player).contains(spell.getSpellResource()), "Addon spell container was not automatically discovered: " + spell.getSpellId());
            }
        } finally {
            removePlayer(player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void spellbookGateRejectsScrollImbuedAndRepeatedRequests(GameTestHelper helper) {
        ServerPlayer player = mockSurvivalPlayer(helper);
        try {
            var spell = SpellRegistry.FIREBOLT_SPELL.get();
            var id = spell.getSpellResource();
            var magic = MagicData.getPlayerMagicData(player);
            magic.setMana(100);
            ItemStack scroll = new ItemStack(ItemRegistry.SCROLL.get(), 2);
            ISpellContainer.createScrollContainer(spell, 1, scroll);
            player.setItemInHand(InteractionHand.MAIN_HAND, scroll);
            helper.assertTrue(!SpellBridge.hasEquippedSpellbook(player), "Empty Curios slot unexpectedly satisfies the book gate");
            helper.assertTrue(strictCandidates(player).isEmpty(), "Held scroll enabled listening without a book");
            helper.assertTrue(SpellBridge.availableSpells(player).stream().anyMatch(entry -> entry.id().equals(id) && !entry.castable()), "Held scroll cannot be trained without equipping a book");
            for (int request = 0; request < 20; request++) {
                helper.assertTrue(!strictCast(player, id), "Repeated no-book request initiated casting");
            }
            helper.assertTrue(scroll.getCount() == 2 && !magic.isCasting(), "Rejected no-book scroll request consumed an item or changed cast state");
            ItemStack imbued = new ItemStack(Items.STICK);
            ISpellContainer.createImbuedContainer(spell, 1, imbued);
            player.setItemInHand(InteractionHand.MAIN_HAND, imbued);
            helper.assertTrue(strictCandidates(player).isEmpty() && !strictCast(player, id), "Imbued/staff-style native wheel source bypassed the book gate");
            ItemStack heldBook = new ItemStack(ItemRegistry.WIMPY_SPELL_BOOK.get());
            player.setItemInHand(InteractionHand.OFF_HAND, heldBook);
            player.getInventory().setItem(9, heldBook.copy());
            helper.assertTrue(!SpellBridge.hasEquippedSpellbook(player) && !strictCast(player, id), "Hand/inventory book was treated as equipped in the Curios spellbook slot");
            equipEmptySpellbook(player);
            helper.assertTrue(SpellBridge.hasEquippedSpellbook(player) && strictCandidates(player).contains(id), "Equipping a real book did not enable the existing native wheel source");
            helper.assertTrue(strictCast(player, id), "Book-equipped imbued source failed to initiate its native cast");
            Utils.serverSideCancelCast(player, false);
            Utils.setPlayerSpellbookStack(player, ItemStack.EMPTY);
            helper.assertTrue(!SpellBridge.hasEquippedSpellbook(player) && strictCandidates(player).isEmpty(), "Unequipping the book left client candidate listening enabled");
            helper.assertTrue(!strictCast(player, id), "Server accepted a cast after the book was unequipped");
        } finally {
            removePlayer(player);
        }
        helper.succeed();
    }

    private static void equipEmptySpellbook(ServerPlayer player) {
        ItemStack book = new ItemStack(ItemRegistry.WIMPY_SPELL_BOOK.get());
        setContainer(book, ISpellContainer.create(1, true, true));
        Utils.setPlayerSpellbookStack(player, book);
    }

    private static boolean strictCast(ServerPlayer player, ResourceLocation id) {
        return SpellBridge.requestCast(player, id, CastOptions.STRICT);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void preparedBookKeepsItsLevelAndNormalManaCost(GameTestHelper helper) {
        ServerPlayer player = mockSurvivalPlayer(helper);
        try {
            var spell = SpellRegistry.FIREBOLT_SPELL.get();
            int level = Math.min(3, spell.getMaxLevel());
            ItemStack book = new ItemStack(ItemRegistry.WIMPY_SPELL_BOOK.get());
            var container = ISpellContainer.create(1, true, true).mutableCopy();
            container.addSpellAtIndex(spell, level, 0, true);
            setContainer(book, container.toImmutable());
            Utils.setPlayerSpellbookStack(player, book);
            var magic = MagicData.getPlayerMagicData(player);
            magic.setMana(100);
            helper.assertTrue(SpellBridge.requestCast(player, spell.getSpellResource(), new CastOptions(true, true, true)), "Prepared book failed with all optional modes enabled");
            int effective = spell.getLevelFor(level, player);
            helper.assertTrue(magic.getCastingSpellLevel() == effective && !UnpreparedCasting.active(player), "Optional modes replaced native prepared level/source");
            float before = magic.getMana();
            spell.castSpell(player.level(), effective, player, CastSource.SPELLBOOK, true);
            spell.onServerCastComplete(player.level(), effective, player, magic, false);
            helper.assertTrue(magic.getMana() == before - spell.getManaCost(effective), "Prepared spell acquired the unprepared surcharge");
        } finally { removePlayer(player); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void continuousUnpreparedPulsesEachReceiveSurcharge(GameTestHelper helper) {
        ServerPlayer player = mockSurvivalPlayer(helper);
        try {
            var spell = SpellRegistry.getEnabledSpells().stream().filter(candidate -> !candidate.requiresLearning()
                    && candidate.getCastType() == io.redspace.ironsspellbooks.api.spells.CastType.CONTINUOUS
                    && CastOptions.surcharge(candidate.getManaCost(1)) * 2 <= 100).findFirst().orElseThrow();
            ItemStack scroll = new ItemStack(ItemRegistry.SCROLL.get());
            ISpellContainer.createScrollContainer(spell, 1, scroll);
            SpellKnowledge.observe(player, scroll);
            equipEmptySpellbook(player);
            var magic = MagicData.getPlayerMagicData(player);
            magic.setMana(100);
            helper.assertTrue(SpellBridge.requestCast(player, spell.getSpellResource(), CastOptions.DEFAULT), "Continuous fixture failed to initiate");
            float before = magic.getMana();
            spell.castSpell(player.level(), 1, player, CastSource.SPELLBOOK, false);
            spell.castSpell(player.level(), 1, player, CastSource.SPELLBOOK, false);
            helper.assertTrue(magic.getMana() == before - 2 * CastOptions.surcharge(spell.getManaCost(1)), "Continuous pulses did not each pay 150% mana");
        } finally { removePlayer(player); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void configuredHandfreeCastCompletesThroughServerTicks(GameTestHelper helper) {
        ServerPlayer player = mockSurvivalPlayer(helper);
        var spell = SpellRegistry.FIREBOLT_SPELL.get();
        ItemStack scroll = new ItemStack(ItemRegistry.SCROLL.get());
        ISpellContainer.createScrollContainer(spell, Math.min(3, spell.getMaxLevel()), scroll);
        SpellKnowledge.observe(player, scroll);
        var options = dev.sts.config.StsOptions.get();
        var magic = MagicData.getPlayerMagicData(player);
        if (!options.castWithoutWand()) {
            helper.assertTrue(!SpellBridge.requestCast(player, spell.getSpellResource()), "Default server policy allowed equipment-free casting");
            removePlayer(player); helper.succeed(); return;
        }
        int level = options.unpreparedLevel(SpellKnowledge.level(player, spell.getSpellResource()), spell.getMaxLevel());
        player.getAttribute(io.redspace.ironsspellbooks.api.registry.AttributeRegistry.MANA_REGEN).setBaseValue(0);
        magic.setMana(CastOptions.surcharge(spell.getManaCost(level)));
        helper.assertTrue(SpellBridge.requestCast(player, spell.getSpellResource()), "Configured equipment-free request was rejected");
        helper.succeedWhen(() -> {
            helper.assertTrue(!magic.isCasting(), "Equipment-free cast did not finish through native ticks");
            helper.assertTrue(magic.getMana() == 0, "Native equipment-free completion did not charge exactly 150%");
            helper.assertTrue(magic.getPlayerCooldowns().isOnCooldown(spell), "Equipment-free completion bypassed cooldown");
            removePlayer(player);
        });
    }
    private static java.util.List<ResourceLocation> strictCandidates(ServerPlayer player) {
        return SpellBridge.castableSpellIds(player, CastOptions.STRICT);
    }
    static void setContainer(ItemStack stack, ISpellContainer container) {
        // Historical Iron releases lack ISpellContainer.set; the registered component is stable.
        @SuppressWarnings("unchecked")
        var type = (net.minecraft.core.component.DataComponentType<ISpellContainer>)
                net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "spell_container"));
        if (type == null) throw new IllegalStateException("Missing native spell container component");
        stack.set(type, container);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void inventoryUpgradeIsRememberedAndSurvivesReplacement(GameTestHelper helper) {
        ServerPlayer player = mockSurvivalPlayer(helper), replacement = mockSurvivalPlayer(helper);
        try {
            var spell = SpellRegistry.FIREBOLT_SPELL.get();
            var id = spell.getSpellResource();
            ItemStack scroll = new ItemStack(ItemRegistry.SCROLL.get());
            ISpellContainer.createScrollContainer(spell, 1, scroll);
            player.getInventory().setItem(12, scroll);
            SpellKnowledge.observe(player);
            helper.assertTrue(SpellKnowledge.level(player, id) == 1, "Inventory scroll did not unlock level one");
            int upgraded = Math.min(5, spell.getMaxLevel());
            ISpellContainer.createScrollContainer(spell, upgraded, scroll);
            SpellKnowledge.observe(player);
            player.getInventory().setItem(12, ItemStack.EMPTY);
            helper.assertTrue(SpellKnowledge.level(player, id) == upgraded, "Inventory-only upgrade was forgotten after removal");
            ISpellContainer.createScrollContainer(spell, 1, scroll);
            SpellKnowledge.observe(player, scroll);
            helper.assertTrue(SpellKnowledge.level(player, id) == upgraded, "Lower scroll downgraded knowledge");
            SpellKnowledge.copy(player, replacement);
            helper.assertTrue(SpellKnowledge.level(replacement, id) == upgraded, "Replacement/death copy lost knowledge");
            helper.assertTrue(SpellBridge.availableSpells(player).stream().anyMatch(entry -> entry.id().equals(id) && entry.level() == upgraded), "Removed scroll's spell disappeared from Grimoire");
        } finally { removePlayer(player); removePlayer(replacement); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void unpreparedLevelOneCostsOneAndHalfAndPreservesScroll(GameTestHelper helper) {
        ServerPlayer player = mockSurvivalPlayer(helper);
        var spell = SpellRegistry.FIREBOLT_SPELL.get();
        ItemStack scroll = new ItemStack(ItemRegistry.SCROLL.get(), 2);
        ISpellContainer.createScrollContainer(spell, Math.min(5, spell.getMaxLevel()), scroll);
        player.getInventory().setItem(9, scroll);
        equipEmptySpellbook(player);
        var magic = MagicData.getPlayerMagicData(player);
        int required = CastOptions.surcharge(spell.getManaCost(1));
        magic.setMana(required - 1);
        helper.assertTrue(!SpellBridge.requestCast(player, spell.getSpellResource(), CastOptions.DEFAULT), "Base mana without surcharge was accepted");
        helper.assertTrue(magic.getMana() == required - 1, "Rejected request consumed mana");
        magic.setMana(required);
        helper.assertTrue(SpellBridge.requestCast(player, spell.getSpellResource(), CastOptions.DEFAULT), "Remembered unprepared spell failed");
        helper.assertTrue(magic.getCastingSpellLevel() == 1 && UnpreparedCasting.active(player), "Level-five scroll did not produce level-one unprepared cast");
        // Native completion, with no mana regeneration between assertion and charge.
        spell.castSpell(player.level(), 1, player, CastSource.SPELLBOOK, true);
        spell.onServerCastComplete(player.level(), 1, player, magic, false);
        helper.assertTrue(magic.getMana() == 0, "Completion did not charge exactly 150% mana");
        helper.assertTrue(scroll.getCount() == 2, "Mana cast consumed the unlock scroll");
        helper.assertTrue(magic.getPlayerCooldowns().isOnCooldown(spell), "Mana cast bypassed native cooldown");
        removePlayer(player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void maxLevelWithStaffAndHandfreePoliciesAreAuthoritative(GameTestHelper helper) {
        ServerPlayer player = mockSurvivalPlayer(helper);
        try {
            var spell = SpellRegistry.FIREBOLT_SPELL.get();
            int upgraded = Math.min(5, spell.getMaxLevel());
            ItemStack scroll = new ItemStack(ItemRegistry.SCROLL.get());
            ISpellContainer.createScrollContainer(spell, upgraded, scroll);
            SpellKnowledge.observe(player, scroll);
            var max = new CastOptions(false, true, false);
            helper.assertTrue(!SpellBridge.requestCast(player, spell.getSpellResource(), max), "No equipment bypassed wand requirement");
            ItemStack implement = new ItemStack(Items.STICK);
            ISpellContainer.createImbuedContainer(spell, 1, implement);
            player.setItemInHand(InteractionHand.MAIN_HAND, implement);
            helper.assertTrue(SpellBridge.hasCastingImplement(player), "Generic addon-style imbued implement not recognized");
            var magic = MagicData.getPlayerMagicData(player);
            magic.setMana(100);
            helper.assertTrue(SpellBridge.requestCast(player, spell.getSpellResource(), max), "Max-level staff cast failed");
            helper.assertTrue(magic.getCastingSpellLevel() == upgraded, "Max-level mode used staff level instead of highest unlock");
            Utils.serverSideCancelCast(player, false);
            magic.getPlayerCooldowns().clearCooldowns();
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertTrue(SpellBridge.requestCast(player, spell.getSpellResource(), new CastOptions(false, false, true)), "Level-one handfree mode failed");
            helper.assertTrue(magic.getCastingSpellLevel() == 1, "Level-one handfree cast had wrong level");
            Utils.serverSideCancelCast(player, false);
            magic.getPlayerCooldowns().clearCooldowns();
            helper.assertTrue(SpellBridge.requestCast(player, spell.getSpellResource(), new CastOptions(false, true, true)), "Max-level handfree mode failed");
            helper.assertTrue(magic.getCastingSpellLevel() == upgraded, "Max-level handfree cast had wrong level");
        } finally { removePlayer(player); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void unpreparedCancellationAndUnknownRequestsConsumeNothing(GameTestHelper helper) {
        ServerPlayer player = mockSurvivalPlayer(helper);
        try {
            equipEmptySpellbook(player);
            var spell = SpellRegistry.FIREBOLT_SPELL.get();
            var magic = MagicData.getPlayerMagicData(player);
            magic.setMana(100);
            helper.assertTrue(!SpellBridge.requestCast(player, spell.getSpellResource(), CastOptions.DEFAULT), "Audio/request ID alone unlocked an unknown spell");
            ItemStack scroll = new ItemStack(ItemRegistry.SCROLL.get());
            ISpellContainer.createScrollContainer(spell, 1, scroll);
            SpellKnowledge.observe(player, scroll);
            float before = magic.getMana();
            helper.assertTrue(SpellBridge.requestCast(player, spell.getSpellResource(), CastOptions.DEFAULT), "Cancellation fixture failed to start");
            Utils.serverSideCancelCast(player, false);
            helper.assertTrue(magic.getMana() == before, "Canceled unprepared cast charged mana before completion");
            magic.getPlayerCooldowns().clearCooldowns();
            magic.setMana(CastOptions.surcharge(spell.getManaCost(1)));
            helper.assertTrue(SpellBridge.requestCast(player, spell.getSpellResource(), CastOptions.DEFAULT), "Mana-drain fixture failed");
            magic.setMana(0);
            UnpreparedCasting.guardMana(player);
            helper.assertTrue(!magic.isCasting(), "Mid-cast mana drain bypassed surcharge funding check");
        } finally { removePlayer(player); }
        helper.succeed();
    }

    private static void magicLearn(ServerPlayer player, io.redspace.ironsspellbooks.api.spells.AbstractSpell spell) {
        MagicData.getPlayerMagicData(player).getSyncedData().learnSpell(spell);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void instantUnpreparedCastPaysOneAndHalf(GameTestHelper helper) {
        ServerPlayer player=mockSurvivalPlayer(helper);
        try {
            equipEmptySpellbook(player);
            var spell=SpellRegistry.REGISTRY.get(ResourceLocation.fromNamespaceAndPath("irons_spellbooks","evasion"));
            helper.assertTrue(spell!=null&&spell.getCastType()==io.redspace.ironsspellbooks.api.spells.CastType.INSTANT,"Instant spell fixture changed");
            magicLearn(player,spell);
            var scroll=new ItemStack(ItemRegistry.SCROLL.get());ISpellContainer.createScrollContainer(spell,1,scroll);SpellKnowledge.observe(player,scroll);
            var magic=MagicData.getPlayerMagicData(player);int cost=CastOptions.surcharge(spell.getManaCost(1));magic.setMana(cost);
            player.getAttribute(io.redspace.ironsspellbooks.api.registry.AttributeRegistry.MANA_REGEN).setBaseValue(0);
            helper.assertTrue(SpellBridge.requestCast(player,spell.getSpellResource()),"Instant unprepared spell rejected");
            helper.runAfterDelay(5,() -> {
                try {
                    helper.assertTrue(!magic.isCasting()&&magic.getMana()==0,"Instant completion bypassed the 150% mana charge: casting="+magic.isCasting()+", mana="+magic.getMana()+", expected=0, cost="+cost);
                    helper.succeed();
                } finally { removePlayer(player); }
            });
        } catch(RuntimeException|Error error) { removePlayer(player);throw error; }
    }

    static ServerPlayer mockSurvivalPlayer(GameTestHelper helper) {
        return mockSurvivalPlayer(helper, null);
    }
    static ServerPlayer mockSurvivalPlayer(GameTestHelper helper, io.netty.channel.ChannelOutboundHandler packetValidator) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "sts-test-player"), false);
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        if (packetValidator != null) channel.pipeline().addFirst("sts_packet_validation", packetValidator);
        // NeoForge's dedicated test hook supplies negotiated channels to the
        // synthetic client. Production negotiation/security remains unchanged.
        NetworkRegistry.configureMockConnection(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.setNoGravity(true);
        var pos = helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
        player.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return player;
    }

    static void removePlayer(ServerPlayer player) {
        MagicData.getPlayerMagicData(player).resetCastingState();
        player.server.getPlayerList().remove(player);
    }
}
