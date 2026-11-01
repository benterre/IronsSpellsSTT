package dev.sts.broadcast;

import java.util.Arrays;

/** Development-only audit. No microphone, network or audio output. */
public final class BroadcastBenchmark {
    public static void main(String[] args) {
        short[] pcm = VoiceEffectsTest.tone(48000, 220, 7000);
        for (int i = 0; i < 150; i++) VoiceEffects.render(AdpcmCodec.decode(AdpcmCodec.encode(pcm)));
        long[] encode = new long[300], effects = new long[300];
        int output = 0;
        for (int i = 0; i < encode.length; i++) {
            long start = System.nanoTime(); VoiceClip clip = AdpcmCodec.encode(pcm); encode[i] = System.nanoTime() - start;
            start = System.nanoTime(); short[] rendered = VoiceEffects.render(AdpcmCodec.decode(clip)); effects[i] = System.nanoTime() - start; output = rendered.length;
        }
        Arrays.sort(encode); Arrays.sort(effects);
        System.out.printf("3-second clip: packed=%d bytes; output=%d samples / %d PCM bytes%n", AdpcmCodec.encode(pcm).encoded().length, output, output * 2);
        System.out.printf("Client pack p50=%.3f ms p95=%.3f ms; server decode+effects p50=%.3f ms p95=%.3f ms%n", encode[150]/1e6,encode[285]/1e6,effects[150]/1e6,effects[285]/1e6);
        System.out.printf("Transient DSP primitives upper bound=%d bytes; no additional idle recording buffer%n", VoiceEffects.MAX_WORKING_ARRAY_BYTES);
    }
}
