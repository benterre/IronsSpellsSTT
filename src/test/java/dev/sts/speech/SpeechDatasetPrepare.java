package dev.sts.speech;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Java-only developer preprocessing; original recordings are opened read-only. */
public final class SpeechDatasetPrepare {
    private static final Map<String, String> SPELLS = Map.ofEntries(
            Map.entry("bloodStep", "blood_step"), Map.entry("fireball", "fireball"),
            Map.entry("firebolt", "firebolt"), Map.entry("frostwave", "frostwave"),
            Map.entry("gust", "gust"), Map.entry("heal", "heal"),
            Map.entry("lightningBolt", "lightning_bolt"), Map.entry("magicMissile", "magic_missile"),
            Map.entry("shield", "shield"), Map.entry("teleport", "teleport"));
    private SpeechDatasetPrepare() {}

    public static void main(String[] args) throws Exception {
        Path incoming = Path.of(args.length > 0 ? args[0] : "recordings/incoming").toAbsolutePath().normalize();
        Path prepared = Path.of(args.length > 1 ? args[1] : "recordings/prepared").toAbsolutePath().normalize();
        String negativeSplit = args.length > 2 ? args[2] : "development";
        if (!Set.of("all", "development", "validation").contains(negativeSplit)) throw new IllegalArgumentException("Expected negative split all/development/validation");
        if (prepared.equals(incoming) || prepared.startsWith(incoming)) throw new IllegalArgumentException("Derived output must be outside incoming originals");
        Files.createDirectories(prepared);
        StringBuilder manifest = new StringBuilder("# spell_id\trelative_folder\n");
        StringBuilder report = new StringBuilder("file\tsource_format\tsource_seconds\tprepared_seconds\ttrimmed_seconds\n");
        for (var entry : SPELLS.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            Path folder = Files.createDirectories(prepared.resolve(entry.getKey()));
            manifest.append("irons_spellbooks:").append(entry.getValue()).append('\t').append(entry.getKey()).append('\n');
            for (int take = 1; take <= 10; take++) {
                String filename = String.format(Locale.ROOT, "%s_%02d.wav", entry.getKey(), take);
                Prepared decoded = decode(incoming.resolve(filename));
                short[] trimmed = trim(decoded.pcm());
                if (trimmed.length > MfccExtractor.MAX_SAMPLES || trimmed.length < 1600) throw new IOException("Prepared take out of supported duration: " + filename);
                writeWav(folder.resolve(String.format(Locale.ROOT, "%02d.wav", take)), trimmed);
                report.append(String.format(Locale.ROOT, "%s\t%s\t%.6f\t%.6f\t%.6f%n", filename,
                        decoded.format(), decoded.sourceSeconds(), trimmed.length / 16000.0,
                        decoded.sourceSeconds() - trimmed.length / 16000.0));
            }
            System.out.printf(Locale.ROOT, "Prepared 10 takes: %s -> irons_spellbooks:%s%n", entry.getKey(), entry.getValue());
        }
        Files.writeString(prepared.resolve("manifest.tsv"), manifest);
        Files.writeString(prepared.resolve("preparation.tsv"), report);
        Path negativeInput = incoming.resolve("negative");
        if (Files.isDirectory(negativeInput)) {
            int count = 0;
            try (var sources = Files.list(negativeInput)) {
                for (Path file : sources.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav")).sorted().toList()) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    boolean gameplay = name.startsWith("gameplay") || name.startsWith("ordinary") || name.startsWith("conversation");
                    boolean validation = gameplay ? name.matches(".*[_-](0[6-9]|10)\\.wav") : name.matches(".*[_-]0[45]\\.wav");
                    if (negativeSplit.equals("development") && validation || negativeSplit.equals("validation") && !validation) continue;
                    Prepared decoded = decode(file);
                    short[] trimmed = gameplay ? decoded.pcm() : trim(decoded.pcm());
                    if (!gameplay && trimmed.length > MfccExtractor.MAX_SAMPLES) throw new IOException("Negative clip over3s needs segmentation: " + file.getFileName());
                    Path negativeOutput = Files.createDirectories(prepared.resolve(gameplay ? "gameplay" : "negative"));
                    writeWav(negativeOutput.resolve(file.getFileName()), trimmed);
                    count++;
                }
            }
            System.out.printf(Locale.ROOT, "Prepared %d negative/gameplay clips (%s split). %s%n", count, negativeSplit,
                    negativeSplit.equals("development") ? "Reserved validation audio was not decoded."
                            : "Requested split was prepared; classifier evaluation is separate.");
        } else System.out.println("Negative clips have not arrived; unknown-conversation validation remains incomplete.");
        System.out.println("Original WAV files preserved. Derived mono16k PCM16 recordings and trim report written.");
    }

    private record Prepared(short[] pcm, String format, double sourceSeconds) {}

    private static Prepared decode(Path path) throws Exception {
        return decode(path, 16000);
    }

    public static short[] readMono16k(Path path) throws Exception { return decode(path, 16000).pcm(); }
    public static short[] readMono48k(Path path) throws Exception { return decode(path, 48000).pcm(); }

    private static Prepared decode(Path path, int targetRate) throws Exception {
        if (Files.size(path) > 32_000_000) throw new IOException("Source WAV too large: " + path.getFileName());
        try (var original = AudioSystem.getAudioInputStream(path.toFile())) {
            AudioFormat source = original.getFormat();
            int channels = source.getChannels();
            float rate = source.getSampleRate();
            if (channels < 1 || channels > 8 || rate < 8000 || rate > 192000) throw new IOException("Unsupported source audio dimensions");
            AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, channels * 2, rate, false);
            if (!AudioSystem.isConversionSupported(format, source)) throw new IOException("Java cannot decode source audio: " + source);
            try (var decoded = AudioSystem.getAudioInputStream(format, original)) {
                int limit = (int) Math.min(32_000_000L, (long) (rate * channels * 2 * 120));
                byte[] bytes = decoded.readNBytes(limit + 1);
                if (bytes.length > limit) throw new IOException("Source recording exceeds 120 seconds");
                short[] mono = new short[bytes.length / format.getFrameSize()];
                for (int frame = 0; frame < mono.length; frame++) {
                    int sum = 0;
                    for (int channel = 0; channel < channels; channel++) {
                        int offset = (frame * channels + channel) * 2;
                        sum += (short) ((bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8));
                    }
                    mono[frame] = (short) (sum / channels);
                }
                return new Prepared(resample(mono, rate, targetRate), source.toString(), mono.length / (double) rate);
            }
        }
    }

    /** Offline anti-aliased windowed-sinc resampling; arbitrary standard WAV rates. */
    static short[] resample(short[] input, double rate) {
        return resample(input, rate, 16000);
    }

    private static short[] resample(short[] input, double rate, int targetRate) {
        if (rate == targetRate) return input.clone();
        short[] output = new short[(int) Math.round(input.length * (double) targetRate / rate)];
        double cutoff = Math.min(.46, targetRate * .475 / rate);
        int radius = 24;
        for (int out = 0; out < output.length; out++) {
            double position = out * rate / targetRate;
            int center = (int) Math.floor(position);
            double sum = 0, weights = 0;
            for (int sample = center - radius; sample <= center + radius; sample++) {
                double offset = sample - position;
                double sinc = Math.abs(offset) < 1e-9 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * offset) / (Math.PI * offset);
                double weight = sinc * (.5 + .5 * Math.cos(Math.PI * offset / (radius + 1)));
                sum += input[Math.max(0, Math.min(input.length - 1, sample))] * weight;
                weights += weight;
            }
            output[out] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sum / weights)));
        }
        return output;
    }

    static short[] trim(short[] pcm) throws IOException {
        int hop = 160;
        double peak = 0;
        double[] rms = new double[(pcm.length + hop - 1) / hop];
        for (int frame = 0; frame < rms.length; frame++) {
            int start = frame * hop, end = Math.min(pcm.length, start + hop);
            double sum = 0;
            for (int i = start; i < end; i++) sum += (double) pcm[i] * pcm[i];
            peak = Math.max(peak, rms[frame] = Math.sqrt(sum / (end - start)));
        }
        double threshold = Math.max(180, peak * .08);
        int first = -1, last = -1;
        for (int i = 0; i < rms.length; i++) {
            if (rms[i] >= threshold) { if (first < 0) first = i; last = i; }
        }
        if (first < 0) throw new IOException("No usable speech in recording");
        return Arrays.copyOfRange(pcm, Math.max(0, (first - 8) * hop), Math.min(pcm.length, (last + 13) * hop));
    }

    private static void writeWav(Path path, short[] pcm) throws Exception {
        byte[] bytes = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) { bytes[i * 2] = (byte) pcm[i]; bytes[i * 2 + 1] = (byte) (pcm[i] >> 8); }
        try (var stream = new AudioInputStream(new ByteArrayInputStream(bytes), new AudioFormat(16000, 16, 1, true, false), pcm.length)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }
}
