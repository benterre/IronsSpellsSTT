package dev.sts.gametest;

import com.mojang.logging.LogUtils;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import dev.sts.broadcast.VoiceEffects;
import net.minecraft.gametest.framework.GameTestHelper;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Native SVC codec and paced playback, without requiring a physical speaker or microphone. */
final class NativeVoiceProbe {
    static void check(GameTestHelper helper) throws Exception {
        var api = (VoicechatServerApi)Class.forName("de.maxhenkel.voicechat.plugins.impl.VoicechatServerApiImpl").getMethod("instance").invoke(null);
        // Use this game's real buffer mappings and this SVC release's own packet codec.
        // The catalog-only API audit deliberately cannot substitute for this wire check.
        var category=dev.sts.voice.SpellVoiceCategory.create(api);
        var packetType=Class.forName("de.maxhenkel.voicechat.net.AddCategoryPacket");
        var packet=packetType.getConstructor(category.getClass()).newInstance(category);
        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            packetType.getMethod("toBytes",net.minecraft.network.FriendlyByteBuf.class).invoke(packet,buffer);
            var restored=packetType.getMethod("fromBytes",net.minecraft.network.FriendlyByteBuf.class).invoke(packetType.getConstructor().newInstance(),buffer);
            var restoredCategory=packetType.getMethod("getCategory").invoke(restored);
            helper.assertTrue("Spell voices".equals(restoredCategory.getClass().getMethod("getName").invoke(restoredCategory))&&buffer.readableBytes()==0,"Shipping voice category failed native packet round trip");
        } finally { buffer.release(); }
        var encoder = api.createEncoder(); var decoder = api.createDecoder(); short[] frame = new short[960];
        for (int i = 0; i < frame.length; i++) frame[i] = (short)(9000 * Math.sin(i * 2 * Math.PI * 185 / 48000));
        long[] measured = new long[300]; int bytes = 0;
        try {
            for (int i = 0; i < 400; i++) {
                long start = System.nanoTime(); byte[] encoded = encoder.encode(frame); long elapsed = System.nanoTime() - start;
                helper.assertTrue(decoder.decode(encoded).length == 960, "Native Opus frame failed to round trip");
                if (i >= 100) measured[i - 100] = elapsed; bytes += encoded.length;
            }
        } finally { encoder.close(); decoder.close(); }
        Arrays.sort(measured);
        LogUtils.getLogger().info("STS_NATIVE_OPUS encoder={} p50Ms={} p95Ms={} averageFrameBytes={} maxClipFrames={}",
                encoder.getClass().getSimpleName(), measured[150]/1e6, measured[285]/1e6, bytes/400.0, (VoiceEffects.MAX_OUTPUT_SAMPLES + 959)/960);
        AtomicInteger packets = new AtomicInteger(), stopped = new AtomicInteger(); UUID channelId = UUID.randomUUID();
        AudioChannel channel = (AudioChannel)Proxy.newProxyInstance(NativeVoiceProbe.class.getClassLoader(), new Class<?>[]{AudioChannel.class}, (proxy, method, args) -> {
            if (method.getName().equals("send")) packets.incrementAndGet();
            return method.getName().equals("getId") ? channelId : null;
        });
        var nativeEncoder = api.createEncoder(); var playback = api.createAudioPlayer(channel, nativeEncoder, frame);
        playback.setOnStopped(stopped::incrementAndGet); playback.startPlaying();
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!playback.isStopped() && System.nanoTime() < deadline) Thread.sleep(5);
        helper.assertTrue(playback.isStopped() && stopped.get() == 1 && packets.get() == 1 && nativeEncoder.isClosed(), "Native paced audio player failed to send/close exactly once");
        LogUtils.getLogger().info("STS_NATIVE_PLAYBACK_PASS one Opus packet; paced playback; stopped callback; encoder closed");
    }
}
