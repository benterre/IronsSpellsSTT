package dev.sts.broadcast;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Optional voice-chat boundary: common bootstrap never loads SVC classes. */
public interface BroadcastBackend {
    interface Playback {
        boolean stopped();
        void stop();
    }
    boolean available();
    Playback play(ServerLevel world, Vec3 origin, short[] pcm48000);
    BroadcastBackend NONE = new BroadcastBackend() {
        public boolean available() { return false; }
        public Playback play(ServerLevel world, Vec3 origin, short[] pcm48000) { return null; }
    };
}
