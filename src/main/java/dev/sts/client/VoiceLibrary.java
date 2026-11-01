package dev.sts.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.io.*;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.concurrent.TimeUnit;
import dev.sts.speech.*;

/** Worker-owned per-spell disk storage; no aggregate limit or retained in-memory bank. */
public final class VoiceLibrary {
    private static final long MAX_FILE_BYTES = 1_000_000;
    private static final int SIGNATURE_MAGIC = 0x53545332;
    // Bump when the MFCC/descriptor/calibration format or algorithm changes.
    private static final int SIGNATURE_VERSION = 2;
    private static final int FEATURE_MAGIC = 0x53545333;
    private final Path directory;

    public VoiceLibrary(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    /** Returns caller-owned PCM16 takes at 16 kHz, or an empty list for an untrained spell. */
    public List<short[]> load(String spellId) throws IOException {
        Path path = pathFor(spellId);
        if (!Files.exists(path)) return List.of();
        if (Files.size(path) > MAX_FILE_BYTES) throw new IOException("Per-spell voice file is too large");
        Map<String, List<short[]>> saved = VoiceProfileStore.load(path);
        if (saved.size() != 1 || !saved.containsKey(spellId)) {
            throw new IOException("Voice file contains a different spell identifier");
        }
        return saved.get(spellId);
    }

    /** Atomically replaces just this spell; an empty take list removes just its file. */
    public void save(String spellId, List<short[]> recordings) throws IOException {
        Path path = pathFor(spellId);
        Objects.requireNonNull(recordings, "recordings");
        if (recordings.isEmpty()) {
            Files.deleteIfExists(path);
            invalidateDerived(spellId);
            return;
        }
        if (recordings.size() > VoiceProfileStore.MAX_TAKES) throw new IOException("Too many voice takes for this spell");
        for (short[] pcm : recordings) {
            if (pcm == null || pcm.length < 1600 || pcm.length > VoiceProfileStore.MAX_SAMPLES) {
                throw new IOException("Invalid voice take size");
            }
        }
        VoiceProfileStore.save(path, Map.of(spellId, recordings));
        invalidateDerived(spellId);
    }

    public int count(String spellId) throws IOException { return load(spellId).size(); }

    /** Full MFCC sequences stay on disk for inactive spells; decode only the shortlist. */
    public List<SpeechTemplate> loadAcousticTemplates(String id) throws IOException {
        Path raw = pathFor(id), cache = featurePath(id);
        if (!Files.exists(raw)) return List.of();
        if (Files.exists(cache) && Files.size(cache) <= 400_000) {
            try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(cache)))) {
                if (input.readInt() == FEATURE_MAGIC && input.readInt() == SIGNATURE_VERSION
                        && input.readLong() == sourceTimestamp(raw)
                        && input.readLong() == Files.size(raw) && input.readUTF().equals(id)) {
                    int count = input.readInt();
                    if (count < 1 || count > 10) throw new IllegalArgumentException("Invalid acoustic cache count");
                    List<SpeechTemplate> templates = new ArrayList<>();
                    for (int take = 0; take < count; take++) {
                        int samples = input.readInt(), frames = input.readInt();
                        if (frames < 1 || frames > 298) throw new IllegalArgumentException("Invalid acoustic frame count");
                        float[][] features = new float[frames][MfccExtractor.FEATURE_DIMENSIONS];
                        for (float[] frame : features) for (int i = 0; i < frame.length; i++) frame[i] = input.readFloat();
                        templates.add(new SpeechTemplate(id, features, samples));
                    }
                    if (input.read() != -1) throw new IllegalArgumentException("Trailing acoustic cache data");
                    return List.copyOf(templates);
                }
            } catch (IOException | IllegalArgumentException corruptedCache) {
                // This is optional derived data; the authoritative recordings are preserved.
            }
        }
        TemplateRecognizer recognizer = new TemplateRecognizer();
        List<SpeechTemplate> computed = load(id).stream().map(pcm -> recognizer.createAcousticTemplate(id, pcm, 16_000)).toList();
        if (!computed.isEmpty()) {
            try { saveAcousticTemplates(id, computed); }
            catch (IOException optionalCacheWrite) { /* Read-only libraries can still recognize. */ }
        }
        return computed;
    }

    public void saveAcousticTemplates(String id, List<SpeechTemplate> templates) throws IOException {
        Path raw = pathFor(id), target = featurePath(id);
        if (!Files.exists(raw) || templates.isEmpty() || templates.size() > 10)
            throw new IOException("Invalid acoustic cache source or count");
        if (templates.stream().anyMatch(template -> !id.equals(template.spellId()) || template.retainedPcmCount() != 0))
            throw new IOException("Acoustic cache must contain only this spell's features");
        Path pending = target.resolveSibling(target.getFileName() + ".tmp");
        try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(pending)))) {
            output.writeInt(FEATURE_MAGIC);
            output.writeInt(SIGNATURE_VERSION);
            output.writeLong(sourceTimestamp(raw));
            output.writeLong(Files.size(raw));
            output.writeUTF(id);
            output.writeInt(templates.size());
            for (SpeechTemplate template : templates) {
                output.writeInt(template.sampleCount());
                output.writeInt(template.frameCount());
                for (float[] frame : template.features()) for (float value : frame) output.writeFloat(value);
            }
        }
        try { Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    /** Read only file headers to discover trained IDs; recordings remain on disk. */
    public List<String> spellIds() throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        List<String> ids = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.bin")).sorted().toList()) {
                if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Per-spell voice file is too large");
                try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                    if (input.readInt() != 0x53545331 || input.readInt() != 1) throw new IOException("Invalid voice file header");
                    String id = input.readUTF();
                    if (!pathFor(id).equals(file)) throw new IOException("Voice file name does not match spell identifier");
                    ids.add(id);
                }
            }
        }
        return List.copyOf(ids);
    }

    /** Compact sidecar is invalidated by recording changes and algorithm-version changes. */
    public Optional<SpeechSignature> loadSignature(String id) throws IOException {
        Path raw = pathFor(id), sidecar = signaturePath(id);
        if (!Files.exists(raw) || !Files.exists(sidecar) || Files.size(sidecar) > 20_000) return Optional.empty();
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(sidecar)))) {
            if (input.readInt() != SIGNATURE_MAGIC || input.readInt() != SIGNATURE_VERSION
                    || input.readLong() != sourceTimestamp(raw) || input.readLong() != Files.size(raw)
                    || !input.readUTF().equals(id)) return Optional.empty();
            SpellCalibration calibration = new SpellCalibration(input.readDouble(), input.readDouble(), input.readDouble());
            int count = input.readInt();
            if (count < 1 || count > 10) return Optional.empty();
            List<float[]> descriptors = new ArrayList<>();
            for (int take = 0; take < count; take++) {
                float[] descriptor = new float[TemplateRecognizer.DESCRIPTOR_DIMENSIONS];
                for (int i = 0; i < descriptor.length; i++) descriptor[i] = input.readFloat();
                descriptors.add(descriptor);
            }
            if (input.read() != -1) return Optional.empty();
            return Optional.of(new SpeechSignature(id, descriptors, calibration));
        } catch (IOException | IllegalArgumentException corrupted) { return Optional.empty(); }
    }

    public void saveSignature(SpeechSignature signature) throws IOException {
        Path raw = pathFor(signature.spellId()), target = signaturePath(signature.spellId());
        if (!Files.exists(raw)) throw new IOException("Cannot index a missing recording file");
        Path pending = target.resolveSibling(target.getFileName() + ".tmp");
        try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(pending)))) {
            output.writeInt(SIGNATURE_MAGIC);
            output.writeInt(SIGNATURE_VERSION);
            output.writeLong(sourceTimestamp(raw));
            output.writeLong(Files.size(raw));
            output.writeUTF(signature.spellId());
            var bounds = signature.calibration();
            output.writeDouble(bounds.maxDistance());
            output.writeDouble(bounds.maxRunnerUpRatio());
            output.writeDouble(bounds.minRunnerUpMargin());
            var descriptors = signature.descriptors();
            output.writeInt(descriptors.size());
            for (float[] descriptor : descriptors) for (float value : descriptor) output.writeFloat(value);
        }
        try { Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private Path signaturePath(String id) throws IOException {
        Path raw = pathFor(id);
        return raw.resolveSibling(raw.getFileName() + ".signature");
    }

    private Path featurePath(String id) throws IOException {
        Path raw = pathFor(id);
        return raw.resolveSibling(raw.getFileName() + ".features");
    }

    private static long sourceTimestamp(Path raw) throws IOException {
        return Files.getLastModifiedTime(raw).to(TimeUnit.NANOSECONDS);
    }

    private void invalidateDerived(String id) throws IOException {
        for (Path derived : List.of(signaturePath(id), featurePath(id))) {
            try { Files.deleteIfExists(derived); }
            catch (IOException optionalCacheFailure) {
                // Authoritative PCM has already committed; timestamp/size/version
                // validation rejects stale derived data even when deletion fails.
            }
        }
    }

    private Path pathFor(String spellId) throws IOException {
        if (spellId == null || spellId.length() > 256 || !spellId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IOException("Invalid spell identifier");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(spellId.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(digest) + ".bin");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime has no SHA-256 implementation", impossible);
        }
    }
}
