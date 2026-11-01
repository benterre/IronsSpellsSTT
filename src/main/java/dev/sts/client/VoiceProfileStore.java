package dev.sts.client;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Small bounded local PCM bank; no voice data or templates are sent to the server. */
public final class VoiceProfileStore {
    public static final int MAX_SPELLS = 128;
    public static final int MAX_TAKES = 10;
    public static final int MAX_SAMPLES = 16_000 * 3;
    private static final int MAGIC = 0x53545331;

    private VoiceProfileStore() {}

    public static Map<String, List<short[]>> load(Path path) throws IOException {
        Map<String, List<short[]>> voices = new LinkedHashMap<>();
        if (!Files.exists(path)) return voices;
        if (Files.size(path) > 130_000_000L) throw new IOException("Voice profile is too large");
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC) throw new IOException("Unsupported STS voice profile");
            int spellCount = bounded(input.readInt(), 0, MAX_SPELLS);
            for (int s = 0; s < spellCount; s++) {
                String id = input.readUTF();
                if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || voices.containsKey(id))
                    throw new IOException("Invalid or duplicate spell id");
                int count = bounded(input.readInt(), 1, MAX_TAKES);
                List<short[]> recordings = new ArrayList<>(count);
                for (int r = 0; r < count; r++) {
                    int length = bounded(input.readInt(), 1600, MAX_SAMPLES);
                    short[] pcm = new short[length];
                    for (int i = 0; i < length; i++) pcm[i] = input.readShort();
                    recordings.add(pcm);
                }
                voices.put(id, recordings);
            }
            if (input.read() != -1) throw new IOException("Trailing voice profile data");
        }
        return voices;
    }

    public static void save(Path path, Map<String, List<short[]>> voices) throws IOException {
        if (voices.size() > MAX_SPELLS) throw new IOException("Too many trained spells");
        Files.createDirectories(path.getParent());
        Path pending = path.resolveSibling(path.getFileName() + ".tmp");
        try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(pending)))) {
            output.writeInt(MAGIC);
            output.writeInt(voices.size());
            for (var entry : voices.entrySet()) {
                if (!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
                    throw new IOException("Invalid spell id");
                output.writeUTF(entry.getKey());
                bounded(entry.getValue().size(), 1, MAX_TAKES);
                output.writeInt(entry.getValue().size());
                for (short[] pcm : entry.getValue()) {
                    bounded(pcm.length, 1600, MAX_SAMPLES);
                    output.writeInt(pcm.length);
                    for (short sample : pcm) output.writeShort(sample);
                }
            }
        }
        try {
            Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int bounded(int value, int min, int max) throws IOException {
        if (value < min || value > max) throw new IOException("Invalid voice profile size");
        return value;
    }
}
