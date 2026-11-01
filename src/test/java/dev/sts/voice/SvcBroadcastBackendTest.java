package dev.sts.voice;

import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.audiochannel.*;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.function.Predicate;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

class SvcBroadcastBackendTest {
    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, BiFunction<String, Object[], Object> handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, values) -> handler.apply(method.getName(), values));
    }
    private static Position position(double x, double y, double z) {
        return fake(Position.class, (name, args) -> switch (name) { case "getX" -> x; case "getY" -> y; case "getZ" -> z; default -> null; });
    }
    private static ServerPlayer listener(Object world, double x, double y, double z) {
        var level = fake(de.maxhenkel.voicechat.api.ServerLevel.class, (name, args) -> world);
        return fake(ServerPlayer.class, (name, args) -> switch (name) { case "getServerLevel" -> level; case "getPosition" -> position(x,y,z); default -> null; });
    }
    @Test void positionalPlaybackIncludesCasterAndReleasesCodec() {
        Object[] state = new Object[6]; int[] counts = new int[3]; boolean[] closed = {false};
        var metadata = new java.util.HashMap<String, Object>();
        var category = fake(VolumeCategory.class, (name, args) -> metadata.get(name));
        Object[] builder = new Object[1]; builder[0] = fake(VolumeCategory.Builder.class, (name,args) -> {
            if (name.startsWith("set")) metadata.put("get" + name.substring(3), args[0]);
            return name.equals("build") ? category : builder[0];
        });
        LocationalAudioChannel channel = fake(LocationalAudioChannel.class, (name, args) -> {
            if (name.equals("setDistance")) state[0] = args[0];
            if (name.equals("setFilter")) state[1] = args[0];
            if (name.equals("setCategory")) state[2] = args[0];
            if (name.equals("flush")) counts[1]++;
            return null;
        });
        OpusEncoder encoder = fake(OpusEncoder.class, (name,args) -> {
            if (name.equals("close")) { closed[0] = true; counts[2]++; }
            return name.equals("isClosed") ? closed[0] : null;
        });
        AudioPlayer player = fake(AudioPlayer.class, (name,args) -> {
            if (name.equals("setOnStopped")) state[3] = args[0];
            if (name.equals("startPlaying")) state[4] = true;
            if (name.equals("stopPlaying")) ((Runnable)state[3]).run();
            return name.equals("isStopped") ? closed[0] : null;
        });
        VoicechatServerApi api = fake(VoicechatServerApi.class, (name,args) -> switch (name) {
            case "volumeCategoryBuilder" -> builder[0];
            case "registerVolumeCategory" -> { counts[0]++; yield null; }
            case "createPosition" -> position((double)args[0], (double)args[1], (double)args[2]);
            case "createLocationalAudioChannel" -> channel;
            case "createEncoder" -> encoder;
            case "createAudioPlayer" -> { state[5] = args[2]; yield player; }
            default -> null;
        });
        var backend = new SvcBroadcastBackend(api);
        assertEquals(0, counts[0]); // Category and codec are lazy.
        short[] audio = {1000, -1000}; var playback = backend.play(null, new Vec3(1, 2, 3), audio);
        assertEquals(50f, state[0]); assertEquals("sts_spells", state[2]); assertSame(audio, state[5]); assertEquals(true, state[4]);
        @SuppressWarnings("unchecked") var filter = (Predicate<ServerPlayer>)state[1];
        assertTrue(filter.test(listener(null, 1,2,3))); // Caster at the origin is included.
        assertTrue(filter.test(listener(null, 51,2,3)));
        assertFalse(filter.test(listener(null, 51.01,2,3)));
        assertFalse(filter.test(listener(null, 31,42,3.01)));
        assertFalse(filter.test(listener(new Object(), 1,2,3)));
        assertFalse(playback.stopped()); playback.stop(); playback.stop();
        assertTrue(playback.stopped()); assertEquals(1, counts[2]); assertEquals(1, counts[1]);
    }
}
