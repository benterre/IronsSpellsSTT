package dev.sts.voice;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import dev.sts.broadcast.BroadcastBackend;
import dev.sts.broadcast.StrongSpellBroadcasts;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** SVC handles pacing, encoding, positional playback and normal listener volume settings. */
public final class SvcBroadcastBackend implements BroadcastBackend {
    private final VoicechatServerApi api;
    private boolean categoryRegistered;
    public SvcBroadcastBackend(VoicechatServerApi api) { this.api = api; }
    @Override public boolean available() { return true; }
    @Override public Playback play(ServerLevel world, Vec3 origin, short[] audio) {
        if (!categoryRegistered) {
            api.registerVolumeCategory(SpellVoiceCategory.create(api));
            categoryRegistered = true;
        }
        // A distinct channel ID makes the caster an ordinary listener, not an excluded sender.
        var channel = api.createLocationalAudioChannel(UUID.randomUUID(), api.fromServerLevel(world),
                api.createPosition(origin.x, origin.y, origin.z));
        if (channel == null) return null;
        channel.setDistance(StrongSpellBroadcasts.RADIUS);
        channel.setCategory(SpellVoiceCategory.ID);
        channel.setFilter(listener -> {
            if (listener.getServerLevel().getServerLevel() != world) return false;
            var position = listener.getPosition();
            return withinRadius(origin.x, origin.y, origin.z, position.getX(), position.getY(), position.getZ());
        });
        var encoder = api.createEncoder();
        AtomicBoolean finished = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!finished.compareAndSet(false, true)) return;
            try { channel.flush(); } finally { if (!encoder.isClosed()) encoder.close(); }
        };
        try {
            var player = api.createAudioPlayer(channel, encoder, audio);
            player.setOnStopped(cleanup);
            player.startPlaying();
            return new Playback() {
                public boolean stopped() {
                    // Also release resources if SVC's playback thread ended exceptionally.
                    if (!finished.get() && player.isStopped()) cleanup.run();
                    return finished.get();
                }
                public void stop() { player.stopPlaying(); if (player.isStopped()) cleanup.run(); }
            };
        } catch (RuntimeException error) {
            cleanup.run();
            throw error;
        }
    }
    public static boolean withinRadius(double ox, double oy, double oz, double x, double y, double z) {
        double dx = x - ox, dy = y - oy, dz = z - oz;
        return dx * dx + dy * dy + dz * dz <= StrongSpellBroadcasts.RADIUS * StrongSpellBroadcasts.RADIUS;
    }
}
