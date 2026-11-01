package dev.sts.broadcast;

/** Bounded 16 kHz mono clip; allocated only after recognizing an eligible strong spell. */
public record VoiceClip(int samples, byte[] encoded) {
    public static final int SAMPLE_RATE = 16_000;
    public static final int MAX_SAMPLES = SAMPLE_RATE * 3;
    public static final int MAX_BYTES = 3 + MAX_SAMPLES / 2;
    public VoiceClip {
        if (samples < 1 || samples > MAX_SAMPLES || encoded.length != 3 + samples / 2)
            throw new IllegalArgumentException("Invalid voice clip size");
        if ((encoded[2] & 255) > 88) throw new IllegalArgumentException("Invalid ADPCM step index");
    }
}
