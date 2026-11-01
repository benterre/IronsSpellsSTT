package dev.sts.speech;

import java.util.Objects;

/** Immutable acoustic example, optionally retaining mono PCM16 at 16 kHz for offline tools. */
public final class SpeechTemplate {
    private final String spellId;
    private final float[][] features;
    private final short[] recording;
    private final int sampleCount;

    public SpeechTemplate(String spellId, float[][] features, short[] recording) {
        this(spellId, features, Objects.requireNonNull(recording, "recording"), recording.length);
    }

    /** Gameplay form: keeps acoustic features and duration, without retaining PCM. */
    public SpeechTemplate(String spellId, float[][] features, int sampleCount) {
        this(spellId, features, new short[0], sampleCount);
    }

    private SpeechTemplate(String spellId, float[][] features, short[] recording, int sampleCount) {
        this.spellId = Objects.requireNonNull(spellId, "spellId");
        Objects.requireNonNull(features, "features");
        Objects.requireNonNull(recording, "recording");
        if (spellId.isBlank() || features.length == 0 || features.length > 298
                || sampleCount <= 0 || sampleCount > 48_000) {
            throw new IllegalArgumentException("Invalid voice template size or identifier");
        }
        this.features = copy(features);
        for (float[] frame : this.features) {
            if (frame.length != MfccExtractor.FEATURE_DIMENSIONS) {
                throw new IllegalArgumentException("Unsupported voice feature dimension");
            }
            for (float value : frame) {
                if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite feature");
            }
        }
        this.recording = recording.clone();
        this.sampleCount = sampleCount;
    }

    public String spellId() { return spellId; }
    public float[][] features() { return copy(features); }
    /** Empty for an acoustic-only template; voice waveforms belong in the disk library. */
    public short[] recording() { return recording.clone(); }
    public int sampleCount() { return sampleCount; }
    public int retainedPcmCount() { return recording.length; }
    public int frameCount() { return features.length; }

    // Package-private read-only access avoids allocating copies during recognition.
    float[][] featureData() { return features; }

    private static float[][] copy(float[][] matrix) {
        float[][] copied = new float[matrix.length][];
        for (int i = 0; i < matrix.length; i++) {
            copied[i] = Objects.requireNonNull(matrix[i], "feature frame").clone();
        }
        return copied;
    }
}
