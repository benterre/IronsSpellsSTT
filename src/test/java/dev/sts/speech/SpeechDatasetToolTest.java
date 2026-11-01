package dev.sts.speech;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SpeechDatasetToolTest {
    @TempDir Path directory;

    @Test void readsMonoPcmAndNormalizes48k() throws Exception {
        Path file = directory.resolve("48k.wav");
        writeWav(file, 48000, 1);
        assertEquals(16000, SpeechDatasetTool.readWav(file).length);
        writeWav(file, 16000, 1);
        assertEquals(16000, SpeechDatasetTool.readWav(file).length);
    }

    @Test void rejectsStereoAndWrongSampleRate() throws Exception {
        Path file = directory.resolve("invalid.wav");
        writeWav(file, 48000, 2);
        assertThrows(java.io.IOException.class, () -> SpeechDatasetTool.readWav(file));
        writeWav(file, 8000, 1);
        assertThrows(java.io.IOException.class, () -> SpeechDatasetTool.readWav(file));
    }

    @Test void manifestRequiresTenTakesInsideItsDirectory() throws Exception {
        Path manifest = directory.resolve("manifest.tsv");
        Path spell = Files.createDirectory(directory.resolve("spell"));
        Files.writeString(manifest, "addon:ignis\tspell\n");
        writeWav(spell.resolve("01.wav"), 16000, 1);
        assertThrows(java.io.IOException.class, () -> SpeechDatasetTool.load(manifest));
        for (int i = 2; i <= 10; i++) writeWav(spell.resolve(String.format("%02d.wav", i)), 16000, 1);
        assertEquals(10, SpeechDatasetTool.load(manifest).get("addon:ignis").size());
        Files.writeString(manifest, "addon:ignis\t..\n");
        assertThrows(java.io.IOException.class, () -> SpeechDatasetTool.load(manifest));
    }

    private static void writeWav(Path file, int rate, int channels) throws Exception {
        short[] pcm = SpeechBenchmark.synthetic(rate, 1, 0);
        byte[] bytes = new byte[pcm.length * channels * 2];
        for (int i = 0; i < pcm.length; i++) {
            for (int channel = 0; channel < channels; channel++) {
                int offset = (i * channels + channel) * 2;
                bytes[offset] = (byte) pcm[i]; bytes[offset + 1] = (byte) (pcm[i] >> 8);
            }
        }
        var format = new AudioFormat(rate, 16, channels, true, false);
        try (var stream = new AudioInputStream(new ByteArrayInputStream(bytes), format, pcm.length)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile());
        }
    }
}
