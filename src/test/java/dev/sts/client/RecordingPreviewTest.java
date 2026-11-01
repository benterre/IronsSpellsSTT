package dev.sts.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecordingPreviewTest {
    @Test void previewRetainsOnlyBoundedBinsAndDuration() {
        short[] pcm = new short[48_000];
        pcm[12_000] = Short.MIN_VALUE;
        RecordingPreview preview = RecordingPreview.from(pcm);
        assertEquals(128, preview.binCount());
        assertEquals(48_000, preview.sampleCount());
        assertEquals(1f, preview.peak(32));
        pcm[12_000] = 0;
        assertEquals(1f, preview.peak(32));
        float[] copy = preview.peaks();
        copy[32] = 0;
        assertEquals(1f, preview.peak(32));
    }

    @Test void silenceHasFiniteZeroPreviews() {
        RecordingPreview preview = RecordingPreview.from(new short[1600]);
        for (int i = 0; i < preview.binCount(); i++) assertEquals(0f, preview.peak(i));
    }
}
