package dev.sts.client;

/** Tiny immutable waveform thumbnail; it never retains the source recording. */
public record RecordingPreview(float[] peaks, int sampleCount) {
    public RecordingPreview {
        if (peaks == null || peaks.length != 128 || sampleCount < 0)
            throw new IllegalArgumentException("Invalid waveform preview");
        peaks = peaks.clone();
    }

    @Override public float[] peaks() { return peaks.clone(); }
    public int binCount() { return peaks.length; }
    public float peak(int index) { return peaks[index]; }

    public static RecordingPreview from(short[] pcm16000) {
        int maximum = 1;
        for (short sample : pcm16000) maximum = Math.max(maximum, Math.abs((int) sample));
        float[] bins = new float[128];
        for (int bin = 0; bin < bins.length; bin++) {
            int start = bin * pcm16000.length / bins.length;
            int end = Math.min(pcm16000.length, Math.max(start + 1, (bin + 1) * pcm16000.length / bins.length));
            int peak = 0;
            for (int i = start; i < end; i++) peak = Math.max(peak, Math.abs((int) pcm16000[i]));
            bins[bin] = peak / (float) maximum;
        }
        return new RecordingPreview(bins, pcm16000.length);
    }
}
