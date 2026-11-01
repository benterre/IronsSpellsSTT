package dev.sts.speech;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SpeechDatasetPrepareTest {
    @TempDir Path directory;

    @Test void arbitraryRateResamplingAndEdgeSilenceTrimmingPreserveSpeech() throws Exception {
        short[] source = new short[44100];
        for (int i = 12000; i < 30000; i++) source[i] = (short) (6000 * Math.sin(2 * Math.PI * 700 * i / 44100));
        short[] resampled = SpeechDatasetPrepare.resample(source, 44100);
        assertEquals(16000, resampled.length);
        short[] trimmed = SpeechDatasetPrepare.trim(resampled);
        assertTrue(trimmed.length > 6000 && trimmed.length < 11000);
        assertNotNull(new MfccExtractor().extract(trimmed, 16000));
    }

    @Test void sourceDirectoryCannotBeUsedAsOutputAndSilenceRejects() {
        assertThrows(IllegalArgumentException.class, () -> SpeechDatasetPrepare.main(new String[]{directory.toString(), directory.toString()}));
        assertThrows(java.io.IOException.class, () -> SpeechDatasetPrepare.trim(new short[16000]));
    }

    @Test void longGameplayResamplingDoesNotOverflowSampleCount() {
        short[] longClip = new short[150_000];
        assertEquals(Math.round(longClip.length * 16000.0 / 44100), SpeechDatasetPrepare.resample(longClip, 44100).length);
    }
}
