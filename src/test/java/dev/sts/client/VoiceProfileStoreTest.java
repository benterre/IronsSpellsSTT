package dev.sts.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VoiceProfileStoreTest {
    @TempDir Path directory;
    @Test void profileRoundTripsAndReplacesAtomically() throws Exception {
        Path file = directory.resolve("player/voices.bin");
        short[] pcm = new short[4800];
        pcm[17] = -1234;
        VoiceProfileStore.save(file, Map.of("addon:ignis", List.of(pcm)));
        assertArrayEquals(pcm, VoiceProfileStore.load(file).get("addon:ignis").getFirst());
        VoiceProfileStore.save(file, Map.of());
        assertTrue(VoiceProfileStore.load(file).isEmpty());
    }
    @Test void malformedCountsAndTruncationAreRejected() throws Exception {
        Path file = directory.resolve("bad.bin");
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(0x53545331); out.writeInt(Integer.MAX_VALUE);
        }
        assertThrows(IOException.class, () -> VoiceProfileStore.load(file));
        Files.write(file, new byte[] { 0x53, 0x54 });
        assertThrows(IOException.class, () -> VoiceProfileStore.load(file));
    }
    @Test void boundsRejectUnsafeProfiles() {
        assertThrows(IOException.class, () -> VoiceProfileStore.save(directory.resolve("bad.bin"),
                Map.of("../../escape", List.of(new short[4800]))));
        assertThrows(IOException.class, () -> VoiceProfileStore.save(directory.resolve("bad.bin"),
                Map.of("irons_spellbooks:fireball", List.of(new short[0]))));
    }
}
