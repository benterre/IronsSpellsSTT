package dev.sts.broadcast;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoiceEffectsTest {
    @Test void adpcmHasBoundedSizeAndPreservesSpeechBandSignal() {
        short[] signal = tone(48000, 220, 6000);
        VoiceClip clip = AdpcmCodec.encode(signal);
        short[] decoded = AdpcmCodec.decode(clip);
        assertEquals(24003, clip.encoded().length);
        double noise = 0, energy = 0;
        for (int i = 100; i < signal.length; i++) { noise += Math.pow(signal[i] - decoded[i], 2); energy += signal[i] * (double)signal[i]; }
        assertTrue(10 * Math.log10(energy / noise) > 25, "ADPCM should retain the speech-band tone");
        assertThrows(IllegalArgumentException.class, () -> AdpcmCodec.encode(new short[48001]));
        assertThrows(IllegalArgumentException.class, () -> new VoiceClip(48001, new byte[24003]));
        assertThrows(IllegalArgumentException.class, () -> new VoiceClip(1, new byte[]{0,0,89}));
    }
    @Test void pitchReverbAndNormalizationDoNotMutateTheOriginal() {
        short[] signal = tone(16000, 220, 1000), original = signal.clone();
        short[] effect = VoiceEffects.render(signal);
        assertArrayEquals(original, signal);
        assertTrue(effect.length > 48000);
        int crossings = 0;
        // Measure the steady section; the reverberator should retain the shifted fundamental.
        for (int i = 24001; i < 48000; i++) if (effect[i - 1] <= 0 && effect[i] > 0) crossings++;
        assertEquals(220 * VoiceEffects.PITCH_RATIO, crossings * 2.0, 6.0);
        int peak = 0;
        for (short sample : effect) peak = Math.max(peak, Math.abs((int)sample));
        assertTrue(peak > 10000 && peak <= 31128);
        assertEquals(0, VoiceEffects.render(new short[1000]).length);
        assertTrue(VoiceEffects.render(tone(48000, 220, 20000)).length <= VoiceEffects.MAX_OUTPUT_SAMPLES);
    }
    @Test void isolatedPulseProducesDenseReflectionsRatherThanSeparatedRepeats() {
        short[] input = new short[8000]; input[100] = 16000; input[101] = -16000;
        short[] output = VoiceEffects.render(input);
        int active = 0;
        for (int i = 4800; i < 14400; i++) if (Math.abs((int)output[i]) > 5) active++;
        assertTrue(active > 4800, "Reflections should fill over half the 100-300 ms interval");
        assertTrue(rms(output, 4800, 9600) > rms(output, 19200, 24000) * 4,
                "The diffuse response should decay instead of sustaining indefinitely");
    }
    @Test void reverbHasExactlyHalfASecondOfTailAndEndsSmoothly() {
        short[] input = tone(16000, 220, 6000);
        short[] output = VoiceEffects.render(input);
        int dryEnd = (int)Math.ceil(input.length / VoiceEffects.PITCH_RATIO) * 3;
        assertEquals(dryEnd + VoiceEffects.OUTPUT_RATE / 2, output.length);
        assertTrue(rms(output, dryEnd, dryEnd + 4800) > 100, "A voiced ending must leave audible reverb");
        assertTrue(rms(output, dryEnd, dryEnd + 4800) > rms(output, output.length - 4800, output.length) * 10);
        assertEquals(0, output[output.length - 1]);
        assertEquals(0, output[output.length - 3]);
        assertTrue(Math.abs((int)output[output.length - 4]) <= 1, "Tail must approach zero before stopping");
    }
    @Test void spokenSectionIsTwentyPercentSlowerThanTheFirstAudition() {
        // The first preset used 0.84 pitch resampling. Its fixed tail is not part of speaking speed.
        short[] output = VoiceEffects.render(tone(16000, 220, 6000));
        double spokenSeconds = (output.length - VoiceEffects.OUTPUT_RATE / 2) / (double)VoiceEffects.OUTPUT_RATE;
        double previousSeconds = 1 / 0.84;
        assertEquals(previousSeconds / 0.80, spokenSeconds, 1.0 / VoiceClip.SAMPLE_RATE);
    }
    @Test void maximumDurationAndFullScaleSignalsStayWithinPlaybackBounds() {
        short[] output = VoiceEffects.render(tone(VoiceClip.MAX_SAMPLES, 330, 32767));
        assertEquals(VoiceEffects.MAX_OUTPUT_SAMPLES, output.length);
        for (short sample : output) assertTrue(Math.abs((int)sample) <= 31128);
        assertThrows(IllegalArgumentException.class, () -> VoiceEffects.render(new short[0]));
        assertThrows(IllegalArgumentException.class, () -> VoiceEffects.render(new short[VoiceClip.MAX_SAMPLES + 1]));
    }
    @Test void consecutiveRendersDoNotCarryReverbBetweenPlayers() {
        short[] input = tone(4000, 220, 3000);
        short[] first = VoiceEffects.render(input);
        VoiceEffects.render(tone(16000, 440, 32767));
        assertArrayEquals(first, VoiceEffects.render(input));
        assertEquals(0, VoiceEffects.render(new short[1000]).length);
    }
    private static double rms(short[] input, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) sum += input[i] * (double)input[i];
        return Math.sqrt(sum / (end - start));
    }
    static short[] tone(int length, double hz, int amplitude) {
        short[] pcm = new short[length];
        for (int i = 0; i < length; i++) pcm[i] = (short)(Math.sin(i * Math.PI * 2 * hz / 16000) * amplitude);
        return pcm;
    }
}
