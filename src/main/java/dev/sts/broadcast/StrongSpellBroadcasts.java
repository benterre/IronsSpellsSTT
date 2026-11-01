package dev.sts.broadcast;

import com.mojang.logging.LogUtils;
import dev.sts.config.StsOptions;
import dev.sts.spell.SpellBridge;
import dev.sts.spell.SpellKnowledge;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Server-owned cast confirmation; no idle audio capture, disk recording or per-player codec. */
public final class StrongSpellBroadcasts {
    public static final float RADIUS = 50;
    public static final int MAX_CONCURRENT = 8;
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, BroadcastBackend.Playback> PLAYING = new HashMap<>();
    private static volatile BroadcastBackend backend = BroadcastBackend.NONE;
    private static ThreadPoolExecutor worker;
    private StrongSpellBroadcasts() {}

    private static final class Pending {
        final ServerPlayer player;
        final ResourceLocation spell;
        final long started;
        final int timeoutTicks;
        volatile boolean valid = true;
        volatile short[] rendered;
        volatile boolean failed;
        VoiceClip clip;
        SpellOnCastEvent completion;
        Pending(ServerPlayer player, ResourceLocation spell, VoiceClip clip, int timeout) {
            this.player = player; this.spell = spell; this.clip = clip;
            this.started = player.serverLevel().getGameTime(); this.timeoutTicks = timeout;
        }
    }
    public static BroadcastBackend setBackend(BroadcastBackend value) {
        BroadcastBackend previous = backend; backend = value; return previous;
    }
    public static boolean available() { return backend.available(); }

    /** Called on the server thread instead of accepting client-provided cast level or timing. */
    public static boolean request(ServerPlayer player, ResourceLocation id, VoiceClip clip) {
        var options = StsOptions.broadcast();
        if (!options.enabled() || !backend.available()) return SpellBridge.requestCast(player, id);
        SpellKnowledge.observe(player);
        var spell = SpellRegistry.REGISTRY.get(id);
        int predicted = SpellBridge.voiceCastLevel(player, id);
        if (spell == null || predicted < 1 || !options.includes(id, predicted, spell.getMaxLevel())
                || MagicData.getPlayerMagicData(player).isCasting() || PENDING.containsKey(player.getUUID())
                || PLAYING.containsKey(player.getUUID()) || PENDING.size() + PLAYING.size() >= MAX_CONCURRENT)
            return SpellBridge.requestCast(player, id);
        int timeout = Math.max(200, Math.min(1200, spell.getEffectiveCastTime(predicted, player) + 100));
        Pending pending = new Pending(player, id, clip, timeout);
        PENDING.put(player.getUUID(), pending);
        boolean accepted;
        try { accepted = SpellBridge.requestCast(player, id); }
        catch (RuntimeException error) { discard(player.getUUID()); throw error; }
        if (!accepted) { discard(player.getUUID()); return false; }
        try {
            worker().execute(() -> {
                VoiceClip captured = pending.clip;
                pending.clip = null;
                if (!pending.valid || captured == null) return;
                try {
                    short[] processed = VoiceEffects.render(AdpcmCodec.decode(captured));
                    if (pending.valid) pending.rendered = processed;
                } catch (RuntimeException error) { pending.failed = true; LOG.warn("STS could not process a spell voice", error); }
            });
        } catch (RejectedExecutionException error) { discard(player.getUUID()); }
        return true;
    }
    private static ThreadPoolExecutor worker() {
        if (worker == null) worker = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_CONCURRENT - 1), task -> {
                    Thread thread = new Thread(task, "STS spell voice effects"); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        worker.allowCoreThreadTimeOut(true);
        return worker;
    }
    public static void onCast(SpellOnCastEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || PENDING.isEmpty()) return;
        Pending pending = PENDING.get(player.getUUID());
        if (pending != null && pending.completion == null && pending.spell.toString().equals(event.getSpellId()))
            pending.completion = event;
    }
    /** Runs after Iron's onCast has created the spell effect, never at cast initiation. */
    public static void tick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty() && PLAYING.isEmpty()) {
            if (worker != null && !StsOptions.broadcast().enabled()) clear();
            return;
        }
        if (!StsOptions.broadcast().enabled() || !backend.available()) { clear(); return; }
        PLAYING.entrySet().removeIf(entry -> entry.getValue().stopped());
        var iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); Pending pending = entry.getValue(); ServerPlayer player = pending.player;
            var spell = SpellRegistry.REGISTRY.get(pending.spell);
            boolean invalid = !pending.valid || pending.failed || spell == null || !player.isAlive()
                    || player.isRemoved() || player.isSpectator()
                    || event.getServer().getPlayerList().getPlayer(player.getUUID()) != player
                    || player.serverLevel().getGameTime() - pending.started > pending.timeoutTicks;
            if (invalid) { pending.valid = false; iterator.remove(); continue; }
            if (pending.completion != null) {
                if (!StsOptions.broadcast().includes(pending.spell, pending.completion.getSpellLevel(), spell.getMaxLevel())) {
                    pending.valid = false; iterator.remove(); continue;
                }
                short[] audio = pending.rendered;
                if (audio == null) continue;
                pending.valid = false; iterator.remove();
                if (audio.length == 0) continue;
                BroadcastBackend.Playback playback;
                try { playback = backend.play(player.serverLevel(), player.position(), audio); }
                catch (RuntimeException error) { LOG.warn("STS could not play a spell voice", error); continue; }
                if (playback != null) {
                    PLAYING.put(entry.getKey(), playback);
                    emitVibration(player);
                }
            } else {
                var magic = MagicData.getPlayerMagicData(player);
                if (!magic.isCasting() || !pending.spell.toString().equals(magic.getCastingSpellId())) {
                    pending.valid = false; iterator.remove();
                }
            }
        }
    }
    public static void emitVibration(ServerPlayer player) {
        // A vanilla loud vibration, without creating an explosion or causing damage.
        player.serverLevel().gameEvent(GameEvent.EXPLODE, player.position(), GameEvent.Context.of(player));
    }
    public static void discard(UUID owner) {
        Pending pending = PENDING.remove(owner);
        if (pending != null) { pending.valid = false; pending.clip = null; pending.rendered = null; }
        var playback = PLAYING.remove(owner);
        if (playback != null) playback.stop();
    }
    public static int pendingCount() { return PENDING.size(); }
    public static int playbackCount() { return PLAYING.size(); }
    public static boolean workerAllocated() { return worker != null; }
    public static void clear() {
        PENDING.values().forEach(p -> { p.valid = false; p.clip = null; p.rendered = null; }); PENDING.clear();
        PLAYING.values().forEach(BroadcastBackend.Playback::stop); PLAYING.clear();
        if (worker != null) { worker.shutdownNow(); worker = null; }
    }
    public static void stopped(ServerStoppedEvent event) { clear(); backend = BroadcastBackend.NONE; }
}
