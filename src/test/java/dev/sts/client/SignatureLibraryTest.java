package dev.sts.client;

import dev.sts.speech.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SignatureLibraryTest {
    @TempDir Path directory;

    @Test void compactIndexRoundTripsAndInvalidatesWhenAudioChanges() throws Exception {
        VoiceLibrary library = new VoiceLibrary(directory);
        String id = "addon:ignis";
        short[] pcm = tone();
        library.save(id, List.of(pcm, pcm.clone()));
        assertEquals(List.of(id), library.spellIds());
        assertTrue(library.loadSignature(id).isEmpty());
        TemplateRecognizer recognizer = new TemplateRecognizer();
        SpeechSignature signature = SpeechSignature.fromTemplates(List.of(
                recognizer.createAcousticTemplate(id, pcm, 16000),
                recognizer.createAcousticTemplate(id, pcm, 16000)));
        library.saveSignature(signature);
        SpeechSignature loaded = library.loadSignature(id).orElseThrow();
        assertEquals(signature.calibration(), loaded.calibration());
        assertArrayEquals(signature.descriptors().getFirst(), loaded.descriptors().getFirst());
        assertEquals(2, loaded.descriptors().size());
        library.save(id, List.of(pcm));
        assertTrue(library.loadSignature(id).isEmpty());
        library.save(id, List.of());
        assertTrue(library.spellIds().isEmpty());
    }

    @Test void truncatedIndexFallsBackToRebuildingWithoutDamagingAudio() throws Exception {
        VoiceLibrary library = new VoiceLibrary(directory);
        short[] pcm = tone();
        library.save("addon:ignis", List.of(pcm));
        try (var files = Files.list(directory)) {
            Path raw = files.filter(p -> p.toString().endsWith(".bin")).findFirst().orElseThrow();
            Files.write(raw.resolveSibling(raw.getFileName() + ".signature"), new byte[] { 0x53, 0x54 });
        }
        assertTrue(library.loadSignature("addon:ignis").isEmpty());
        assertArrayEquals(pcm, library.load("addon:ignis").getFirst());
    }

    @Test void acousticDiskCacheRetainsNoPcmAndInvalidatesAfterChanges() throws Exception {
        VoiceLibrary library = new VoiceLibrary(directory);
        String id = "addon:ignis";
        library.save(id, List.of(tone()));
        var first = library.loadAcousticTemplates(id);
        assertEquals(0, first.getFirst().retainedPcmCount());
        var cached = library.loadAcousticTemplates(id);
        assertArrayEquals(first.getFirst().features()[0], cached.getFirst().features()[0]);
        library.save(id, List.of(tone(), tone()));
        assertEquals(2, library.loadAcousticTemplates(id).size());
        library.save(id, List.of());
        assertTrue(library.loadAcousticTemplates(id).isEmpty());
    }

    @Test void corruptAcousticCacheRebuildsFromPreservedPcm() throws Exception {
        VoiceLibrary library = new VoiceLibrary(directory);
        library.save("addon:ignis", List.of(tone()));
        var expected = library.loadAcousticTemplates("addon:ignis").getFirst();
        try (var files = Files.list(directory)) {
            Path cache = files.filter(p -> p.toString().endsWith(".features")).findFirst().orElseThrow();
            Files.write(cache, new byte[] {0x53, 0x54});
        }
        var rebuilt = library.loadAcousticTemplates("addon:ignis").getFirst();
        assertEquals(0, rebuilt.retainedPcmCount());
        assertArrayEquals(expected.features()[0], rebuilt.features()[0]);
        assertArrayEquals(tone(), library.load("addon:ignis").getFirst());
    }

    private static short[] tone() {
        short[] pcm = new short[4800];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (6000 * Math.sin(i * 2 * Math.PI * 440 / 16000));
        return pcm;
    }
}
