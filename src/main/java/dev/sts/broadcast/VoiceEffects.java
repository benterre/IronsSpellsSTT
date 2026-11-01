package dev.sts.broadcast;

/** One-shot pitch resampling, damped Schroeder reverb and RMS normalization with a soft limiter. */
public final class VoiceEffects {
    /** Additional slowdown relative to the first audition; resampling also deepens the pitch. */
    public static final double PLAYBACK_SPEED_RATIO = 0.80;
    public static final double PITCH_RATIO = 0.84 * PLAYBACK_SPEED_RATIO;
    public static final int OUTPUT_RATE = 48_000;
    public static final int REVERB_TAIL_SAMPLES = VoiceClip.SAMPLE_RATE / 2;
    public static final double REVERB_DECAY_SECONDS = 0.90; // Approximate broadband RT60; bounded by the faded tail.
    public static final float REVERB_WET_GAIN = 1.80f;
    private static final float DAMPING = 0.35f, DIFFUSION = 0.5f;
    private static final int TAIL_FADE_SAMPLES = VoiceClip.SAMPLE_RATE / 20;
    // Incommensurate delays distribute reflections instead of producing discrete repeated words.
    private static final int[] COMB_DELAYS = {479, 557, 631, 683, 743, 809, 877, 941};
    private static final int[] ALLPASS_DELAYS = {181, 137, 97, 61};
    public static final int MAX_OUTPUT_SAMPLES =
            ((int)Math.ceil(VoiceClip.MAX_SAMPLES / PITCH_RATIO) + REVERB_TAIL_SAMPLES) * 3;
    /** Decoded input, mixed signal, output PCM and reverb delay arrays; excludes object/codec overhead. */
    public static final int MAX_WORKING_ARRAY_BYTES = VoiceClip.MAX_SAMPLES * 2
            + MAX_OUTPUT_SAMPLES / 3 * 4 + MAX_OUTPUT_SAMPLES * 2
            + (java.util.Arrays.stream(COMB_DELAYS).sum() + java.util.Arrays.stream(ALLPASS_DELAYS).sum()) * 4;
    private VoiceEffects() {}
    public static short[] render(short[] input) {
        if (input.length < 1 || input.length > VoiceClip.MAX_SAMPLES) throw new IllegalArgumentException("Invalid effect input");
        int dryLength = (int)Math.ceil(input.length / PITCH_RATIO);
        float[] mixed = new float[dryLength + REVERB_TAIL_SAMPLES];
        Comb[] combs = new Comb[COMB_DELAYS.length];
        for (int i = 0; i < combs.length; i++) combs[i] = new Comb(COMB_DELAYS[i]);
        Allpass[] allpasses = new Allpass[ALLPASS_DELAYS.length];
        for (int i = 0; i < allpasses.length; i++) allpasses[i] = new Allpass(ALLPASS_DELAYS[i]);
        float parallelGain = (float)(1 / Math.sqrt(combs.length));
        double mean = 0;
        for (short value : input) mean += value;
        mean /= input.length;
        for (int i = 0; i < mixed.length; i++) {
            float dry = 0;
            if (i < dryLength) {
                double source = i * PITCH_RATIO;
                int low = Math.min((int)source, input.length - 1), high = Math.min(low + 1, input.length - 1);
                dry = (float)(input[low] + (input[high] - input[low]) * (source - low) - mean);
            }
            float wet = 0;
            for (Comb comb : combs) wet += comb.process(dry);
            wet *= parallelGain;
            for (Allpass allpass : allpasses) wet = allpass.process(wet);
            mixed[i] = dry + wet * REVERB_WET_GAIN;
        }
        double sum = 0;
        // Normalize the voiced section once; neither silence nor the tail gets a separate gain boost.
        for (int i = 0; i < dryLength; i++) sum += mixed[i] * (double)mixed[i];
        double rms = Math.sqrt(sum / dryLength);
        if (rms < 8) return new short[0];
        double gain = 0.36 / rms;
        for (int i = 0; i < mixed.length; i++) {
            float value = (float)(Math.tanh(mixed[i] * gain) * 31_128);
            int remaining = mixed.length - 1 - i;
            // The last 50 ms fade smoothly to exactly zero, avoiding a truncated-tail click.
            if (remaining < TAIL_FADE_SAMPLES)
                value *= (float)(0.5 - 0.5 * Math.cos(Math.PI * remaining / TAIL_FADE_SAMPLES));
            mixed[i] = value;
        }
        short[] output = new short[mixed.length * 3];
        for (int i = 0; i < output.length; i++) {
            int low = i / 3, high = Math.min(low + 1, mixed.length - 1);
            output[i] = (short)Math.round(mixed[low] + (mixed[high] - mixed[low]) * (i % 3 / 3f));
        }
        return output;
    }

    private static final class Comb {
        private final float[] buffer;
        private final float feedback;
        private int position;
        private float filtered;
        private Comb(int delay) {
            buffer = new float[delay];
            feedback = (float)Math.pow(10, -3.0 * delay / (VoiceClip.SAMPLE_RATE * REVERB_DECAY_SECONDS));
        }
        private float process(float input) {
            float delayed = buffer[position];
            filtered = delayed * (1 - DAMPING) + filtered * DAMPING;
            buffer[position] = input + filtered * feedback;
            if (++position == buffer.length) position = 0;
            return delayed;
        }
    }

    private static final class Allpass {
        private final float[] buffer;
        private int position;
        private Allpass(int delay) { buffer = new float[delay]; }
        private float process(float input) {
            float output = buffer[position] - input * DIFFUSION;
            buffer[position] = input + output * DIFFUSION;
            if (++position == buffer.length) position = 0;
            return output;
        }
    }
}
