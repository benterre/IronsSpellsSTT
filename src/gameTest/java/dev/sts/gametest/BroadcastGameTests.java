package dev.sts.gametest;

import com.mojang.logging.LogUtils;
import dev.sts.broadcast.*;
import dev.sts.config.StsOptions;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.List;

@GameTestHolder("sts")
@PrefixGameTestTemplate(false)
public final class BroadcastGameTests {
    @GameTest(template = "empty", batch = "sts_broadcast", timeoutTicks = 350)
    public static void voiceReplayRequiresEligibleSuccessfulCompletion(GameTestHelper helper) {
        boolean enabled = StsOptions.BROADCAST.get(), maximum = StsOptions.BROADCAST_MAX.get();
        boolean maxCasting = StsOptions.MAX.get();
        List<? extends String> ids = StsOptions.BROADCAST_SPELLS.get();
        StrongSpellBroadcasts.clear();
        Probe backend = new Probe(); BroadcastBackend original = StrongSpellBroadcasts.setBackend(backend);
        ServerPlayer player = SpellBridgeGameTests.mockSurvivalPlayer(helper);
        var spell = SpellRegistry.FIREBOLT_SPELL.get(); var magic = MagicData.getPlayerMagicData(player);
        Runnable cleanup = () -> {
            StrongSpellBroadcasts.clear(); StrongSpellBroadcasts.setBackend(original);
            StsOptions.BROADCAST.set(enabled); StsOptions.BROADCAST_MAX.set(maximum); StsOptions.BROADCAST_SPELLS.set(ids);
            StsOptions.MAX.set(maxCasting);
            SpellBridgeGameTests.removePlayer(player);
        };
        try {
            StsOptions.BROADCAST_SPELLS.set(List.of(spell.getSpellId())); StsOptions.BROADCAST_MAX.set(true);
            StsOptions.BROADCAST.set(true); StsOptions.MAX.set(false);
            equip(player, spell, spell.getMaxLevel()); dev.sts.spell.SpellKnowledge.observe(player);
            var empty = new ItemStack(ItemRegistry.WIMPY_SPELL_BOOK.get());
            SpellBridgeGameTests.setContainer(empty, ISpellContainer.create(1, true, true)); Utils.setPlayerSpellbookStack(player, empty);
            helper.assertTrue(dev.sts.spell.SpellBridge.broadcastableSpellIds(player).isEmpty(), "Level-one unprepared mode retained maximum-level audio eligibility");
            StsOptions.MAX.set(true);
            helper.assertTrue(dev.sts.spell.SpellBridge.broadcastableSpellIds(player).contains(spell.getSpellId()), "Maximum remembered spell was not eligible for replay");
            StsOptions.MAX.set(maxCasting);
            player.getAttribute(AttributeRegistry.MAX_MANA).setBaseValue(3000);
            player.getAttribute(AttributeRegistry.MANA_REGEN).setBaseValue(0);
            short[] tone = new short[8000]; for (int i = 0; i < tone.length; i++) tone[i] = (short)(7000 * Math.sin(i * 2 * Math.PI * 220 / 16000));
            VoiceClip clip = AdpcmCodec.encode(tone);
            equip(player, spell, spell.getMaxLevel()); magic.setMana(3000);
            StsOptions.BROADCAST.set(false);
            helper.assertTrue(StrongSpellBroadcasts.request(player, spell.getSpellResource(), clip), "Disabled mode broke native casting");
            helper.assertTrue(StrongSpellBroadcasts.pendingCount() == 0 && !StrongSpellBroadcasts.workerAllocated(), "Disabled mode allocated audio work");
            Utils.serverSideCancelCast(player, false); magic.getPlayerCooldowns().clearCooldowns();
            StsOptions.BROADCAST.set(true); equip(player, spell, 1);
            helper.assertTrue(StrongSpellBroadcasts.request(player, spell.getSpellResource(), clip), "Submaximum cast rejected");
            helper.assertTrue(StrongSpellBroadcasts.pendingCount() == 0 && !StrongSpellBroadcasts.workerAllocated(), "Submaximum cast prepared replay");
            Utils.serverSideCancelCast(player, false); magic.getPlayerCooldowns().clearCooldowns(); equip(player, spell, spell.getMaxLevel());
            helper.assertTrue(StrongSpellBroadcasts.request(player, spell.getSpellResource(), clip), "Cancellation fixture rejected");
            helper.assertTrue(StrongSpellBroadcasts.pendingCount() == 1 && backend.plays == 0, "Audio replayed at initiation");
            Utils.serverSideCancelCast(player, false);
            helper.runAfterDelay(10, () -> {
                try {
                    helper.assertTrue(StrongSpellBroadcasts.pendingCount() == 0 && backend.plays == 0, "Cancelled cast replayed");
                    magic.getPlayerCooldowns().clearCooldowns(); magic.setMana(3000);
                    helper.assertTrue(StrongSpellBroadcasts.request(player, spell.getSpellResource(), clip), "Successful replay fixture rejected");
                    helper.assertTrue(backend.plays == 0, "Replay began before successful completion");
                    helper.runAfterDelay(100, () -> {
                        try {
                            helper.assertTrue(!magic.isCasting() && backend.plays == 1 && backend.samples > 24000, "Native completion did not replay exactly once");
                            helper.assertTrue(StrongSpellBroadcasts.pendingCount() == 0, "Completed cast retained pending audio");
                            StsOptions.BROADCAST.set(false);
                            helper.runAfterDelay(5, () -> {
                                try {
                                    helper.assertTrue(backend.stops == 1 && StrongSpellBroadcasts.playbackCount() == 0 && !StrongSpellBroadcasts.workerAllocated(), "Disabling failed to stop/release effects");
                                    helper.succeed();
                                } finally { cleanup.run(); }
                            });
                        } catch (RuntimeException | AssertionError error) { cleanup.run(); throw error; }
                    });
                } catch (RuntimeException | AssertionError error) { cleanup.run(); throw error; }
            });
        } catch (RuntimeException | AssertionError error) { cleanup.run(); throw error; }
    }
    private static void equip(ServerPlayer player, AbstractSpell spell, int level) {
        ItemStack book = new ItemStack(ItemRegistry.WIMPY_SPELL_BOOK.get());
        var container = ISpellContainer.create(1, true, true).mutableCopy(); container.addSpellAtIndex(spell, level, 0, true);
        SpellBridgeGameTests.setContainer(book, container.toImmutable()); Utils.setPlayerSpellbookStack(player, book);
        MagicData.getPlayerMagicData(player).getSyncedData().learnSpell(spell);
    }
    private static final class Probe implements BroadcastBackend {
        int plays, stops, samples;
        public boolean available() { return true; }
        public Playback play(ServerLevel world, Vec3 origin, short[] pcm) {
            plays++; samples = pcm.length;
            return new Playback() { boolean stopped; public boolean stopped() { return stopped; } public void stop() { if (!stopped) stops++; stopped = true; } };
        }
    }
    @GameTest(template = "empty", batch = "sts_hearing", timeoutTicks = 100)
    public static void loudIncantationActivatesNativeSensorAndWarden(GameTestHelper helper) {
        ServerPlayer player = SpellBridgeGameTests.mockSurvivalPlayer(helper);
        var world = helper.getLevel(); BlockPos sensor = helper.absolutePos(new BlockPos(3, 2, 1));
        // Historical test servers place structures far from spawn. Keep vibration travel chunks ticking.
        var forced = new java.util.ArrayList<net.minecraft.world.level.ChunkPos>();
        var center = new net.minecraft.world.level.ChunkPos(player.blockPosition());
        for (int x = center.x - 2; x <= center.x + 2; x++) for (int z = center.z - 2; z <= center.z + 2; z++) {
            var chunk = new net.minecraft.world.level.ChunkPos(x, z);
            if (!world.getForcedChunks().contains(chunk.toLong())) { world.setChunkForced(x, z, true); forced.add(chunk); }
            world.getChunk(x, z);
        }
        world.setBlockAndUpdate(sensor.below(), Blocks.STONE.defaultBlockState()); world.setBlockAndUpdate(sensor, Blocks.SCULK_SENSOR.defaultBlockState());
        var warden = EntityType.WARDEN.create(world); warden.setPos(player.getX(), player.getY(), player.getZ() + 5); warden.setNoGravity(true);
        warden.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).setBaseValue(0);
        // EntityType.create omits natural-spawn initialization. Keep this listener awake.
        warden.getBrain().setMemoryWithExpiry(net.minecraft.world.entity.ai.memory.MemoryModuleType.DIG_COOLDOWN, net.minecraft.util.Unit.INSTANCE, 1200);
        world.addFreshEntity(warden);
        helper.runAfterDelay(15, () -> {
            int before = warden.getAngerManagement().getActiveAnger(player);
            warden.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.VIBRATION_COOLDOWN);
            warden.getVibrationData().setCurrentVibration(null); warden.getVibrationData().getSelectionStrategy().startOver();
            helper.assertTrue(warden.getVibrationUser().canReceiveVibration(world,player.blockPosition(),net.minecraft.world.level.gameevent.GameEvent.EXPLODE,net.minecraft.world.level.gameevent.GameEvent.Context.of(player)), "Warden fixture is not ready to listen");
            StrongSpellBroadcasts.emitVibration(player);
            helper.runAfterDelay(12, () -> {
                try {
                    helper.assertTrue(world.getBlockState(sensor).getValue(SculkSensorBlock.PHASE) == SculkSensorPhase.ACTIVE, "Loud voice did not activate sculk sensor");
                    helper.assertTrue(warden.getAngerManagement().getActiveAnger(player) > before, "Warden did not hear attributed incantation: before="+before+", after="+warden.getAngerManagement().getActiveAnger(player));
                    helper.succeed();
                } finally {
                    warden.discard(); world.removeBlock(sensor, false); world.removeBlock(sensor.below(), false);
                    forced.forEach(chunk -> world.setChunkForced(chunk.x, chunk.z, false)); SpellBridgeGameTests.removePlayer(player);
                }
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void auditStrongSpellRegistryAndNativeCodec(GameTestHelper helper) throws Exception {
        for (var spell : SpellRegistry.getEnabledSpells()) {
            int mana = spell.getManaCost(spell.getMaxLevel()), ticks = spell.getCastTime(spell.getMaxLevel());
            if (mana >= 150 && ticks >= 40 && spell.getCastType() == CastType.LONG)
                LogUtils.getLogger().info("STS_STRONG_SPELL {} max={} mana={} chargeTicks={}", spell.getSpellId(), spell.getMaxLevel(), mana, ticks);
        }
        if (net.neoforged.fml.ModList.get().isLoaded("voicechat")) NativeVoiceProbe.check(helper);
        helper.succeed();
    }
}
