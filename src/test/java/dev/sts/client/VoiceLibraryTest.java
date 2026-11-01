package dev.sts.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class VoiceLibraryTest {
    @TempDir Path directory;

    @Test void perSpellRoundTripAndDeletionAreIsolated() throws Exception {
        var library = new VoiceLibrary(directory.resolve("voices"));
        short[] first = new short[1600]; first[42] = 1234;
        short[] second = new short[3200]; second[99] = -678;
        library.save("addon:fire", List.of(first));
        library.save("other:fire", List.of(second));
        assertArrayEquals(first, library.load("addon:fire").getFirst());
        assertArrayEquals(second, library.load("other:fire").getFirst());
        library.load("addon:fire").getFirst()[42] = 7;
        assertEquals(1234, library.load("addon:fire").getFirst()[42]);
        library.save("addon:fire", List.of());
        assertEquals(0, library.count("addon:fire"));
        assertEquals(1, library.count("other:fire"));
    }

    @Test void rejectsStoredIdentifierMismatchAndMultipleEntries() throws Exception {
        var library = new VoiceLibrary(directory);
        Path path = path("addon:fire");
        VoiceProfileStore.save(path, Map.of("other:ice", List.of(new short[1600])));
        assertThrows(IOException.class, () -> library.load("addon:fire"));
        VoiceProfileStore.save(path, Map.of("addon:fire", List.of(new short[1600]), "other:ice", List.of(new short[1600])));
        assertThrows(IOException.class, () -> library.load("addon:fire"));
    }

    @Test void invalidIdsAndTakesCannotReplaceExistingVoice() throws Exception {
        var library = new VoiceLibrary(directory);
        short[] original = new short[1600]; original[10] = 91;
        library.save("addon:fire", List.of(original));
        assertThrows(IOException.class, () -> library.save("../fire", List.of(original)));
        assertThrows(IOException.class, () -> library.load("addon:" + "a".repeat(257)));
        assertThrows(IOException.class, () -> library.save("addon:fire", java.util.Collections.nCopies(11, original)));
        assertThrows(IOException.class, () -> library.save("addon:fire", List.of(new short[1599])));
        assertThrows(IOException.class, () -> library.save("addon:fire", List.of(new short[48001])));
        assertArrayEquals(original, library.load("addon:fire").getFirst());
    }

    @Test void noAggregateSpellLimitAndFilenamesCannotEscapeDirectory() throws Exception {
        var library = new VoiceLibrary(directory);
        for (int i = 0; i < 140; i++) library.save("addon:spell/" + i, List.of(new short[1600]));
        assertEquals(1, library.count("addon:spell/139"));
        try (var files = Files.list(directory)) {
            assertEquals(140, files.filter(file -> file.getFileName().toString().matches("[0-9a-f]{64}\\.bin")).count());
        }
    }

    @Test void oversizedFileRejectsBeforeDecoding() throws Exception {
        var library = new VoiceLibrary(directory);
        Files.write(path("addon:fire"), new byte[1_000_001]);
        assertThrows(IOException.class, () -> library.load("addon:fire"));
    }

    private Path path(String id) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
        return directory.resolve(HexFormat.of().formatHex(digest) + ".bin");
    }
}
