package dev.sts.speech;

import dev.sts.client.VoiceProfileStore;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Offline Java-only evaluation/import; does not connect to Minecraft or any relay. */
public final class SpeechDatasetTool {
    private SpeechDatasetTool() {}

    public static void main(String[] args) throws Exception {
        String operation = args.length > 0 ? args[0] : "evaluate";
        Path manifest = Path.of(args.length > 1 ? args[1] : "recordings/incoming/manifest.tsv");
        if (!operation.equals("evaluate") && !operation.equals("evaluate-revised") && !operation.equals("import")) {
            throw new IllegalArgumentException("Usage: evaluate[-revised] [manifest.tsv] OR import manifest.tsv output.sts");
        }
        if (!Files.exists(manifest)) {
            System.out.println("No recording manifest found. Real speech validation remains incomplete.");
            return;
        }
        var dataset = load(manifest);
        if (dataset.isEmpty()) {
            System.out.println("No recordings listed. Real speech validation remains incomplete.");
            return;
        }
        if (operation.equals("import")) {
            if (args.length != 3) throw new IllegalArgumentException("Import requires a manifest and output profile path");
            VoiceProfileStore.save(Path.of(args[2]).toAbsolutePath(), dataset);
            System.out.printf(Locale.ROOT, "Imported %d spells, %d takes. Original WAV files are unchanged.%n",
                    dataset.size(), dataset.values().stream().mapToInt(List::size).sum());
        } else evaluate(dataset, manifest.toAbsolutePath().getParent().resolve("negative"), !operation.equals("evaluate"),
                args.length > 2 ? args[2] : "development", args.length > 3 ? Integer.parseInt(args[3]) : 7);
    }

    static Map<String, List<short[]>> load(Path manifest) throws IOException {
        Path base = manifest.toAbsolutePath().normalize().getParent();
        if (Files.size(manifest) > 65536) throw new IOException("Manifest exceeds 64 KiB");
        var dataset = new LinkedHashMap<String, List<short[]>>();
        var recognizer = new TemplateRecognizer();
        for (String line : Files.readAllLines(manifest)) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            String[] columns = line.split("\t", -1);
            if (columns.length != 2 || !columns[0].matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
                throw new IOException("Expected spell_id<TAB>relative_folder in manifest");
            }
            if (dataset.size() >= VoiceProfileStore.MAX_SPELLS || dataset.containsKey(columns[0])) {
                throw new IOException("Too many or duplicate spell identifiers");
            }
            Path folder = base.resolve(columns[1]).normalize();
            if (!folder.startsWith(base) || !Files.isDirectory(folder)
                    || !folder.toRealPath().startsWith(base.toRealPath())) {
                throw new IOException("Recording folder must be inside the manifest directory");
            }
            List<Path> files;
            try (var stream = Files.list(folder)) {
                files = stream.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav"))
                        .sorted(Comparator.comparing(file -> file.getFileName().toString())).toList();
            }
            if (files.size() != 10) throw new IOException("Each listed spell needs exactly 10 WAV takes");
            var takes = new ArrayList<short[]>();
            for (Path file : files) {
                if (!file.toRealPath().startsWith(base.toRealPath())) throw new IOException("WAV path leaves recording directory");
                short[] pcm = readWav(file);
                try { recognizer.createTemplate(columns[0], pcm, 16000); }
                catch (IllegalArgumentException error) { throw new IOException("Unusable recording: " + file.getFileName(), error); }
                takes.add(pcm);
            }
            dataset.put(columns[0], takes);
        }
        return dataset;
    }

    static short[] readWav(Path path) throws IOException {
        if (Files.size(path) > 400_000) throw new IOException("WAV exceeds the three-second recording size limit");
        try (var audio = AudioSystem.getAudioInputStream(path.toFile())) {
            AudioFormat format = audio.getFormat();
            int rate = Math.round(format.getSampleRate());
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) || format.getChannels() != 1
                    || format.getSampleSizeInBits() != 16 || format.getFrameSize() != 2
                    || format.getSampleRate() != rate || (rate != 16000 && rate != 48000)) {
                throw new IOException("Expected mono PCM16 WAV at 16000 or 48000 Hz");
            }
            byte[] bytes = audio.readNBytes(rate * 3 * 2 + 2);
            if (bytes.length > rate * 3 * 2 || bytes.length % 2 != 0 || bytes.length < rate / 5) {
                throw new IOException("WAV duration must be 0.1 to 3 seconds");
            }
            short[] pcm = new short[bytes.length / 2];
            for (int i = 0; i < pcm.length; i++) {
                int a = bytes[i * 2] & 255, b = bytes[i * 2 + 1] & 255;
                pcm[i] = (short) (format.isBigEndian() ? (a << 8) | b : (b << 8) | a);
            }
            return new MfccExtractor().normalize(pcm, rate);
        } catch (UnsupportedAudioFileException e) { throw new IOException("Unsupported WAV format", e); }
    }

    private static void evaluate(Map<String, List<short[]>> dataset, Path negativeFolder, boolean revised, String negativeSplit,
                                 int positiveEnrollmentCount) throws IOException {
        if (!Set.of("all", "development", "validation").contains(negativeSplit)) throw new IllegalArgumentException("Expected negative split all/development/validation");
        if (positiveEnrollmentCount != 7 && positiveEnrollmentCount != 10) throw new IllegalArgumentException("Expected7heldoutor10productionenrollment takes");
        var recognizer = new TemplateRecognizer();
        var templates = new ArrayList<SpeechTemplate>();
        Set<String> candidates = Set.copyOf(dataset.keySet());
        for (var entry : dataset.entrySet()) {
            for (int take = 0; take < positiveEnrollmentCount; take++) templates.add(recognizer.createAcousticTemplate(entry.getKey(), entry.getValue().get(take), 16000));
        }
        var descriptors = new LinkedHashMap<String, List<float[]>>();
        var calibration = new LinkedHashMap<String, SpellCalibration>();
        for (String id : candidates) {
            var signature = SpeechSignature.fromTemplates(templates.stream().filter(template -> template.spellId().equals(id)).toList());
            descriptors.put(id, signature.descriptors());
            calibration.put(id, signature.calibration());
        }
        System.out.println(positiveEnrollmentCount == 7 ? "Evaluation: takes01-07train,takes08-10heldout.AllmanifestIDscompete."
                : "Production enrollment: all10positive takes train; positive08-10queries are self-training sanity checks, not heldout accuracy.");
        System.out.println(revised ? "Revised: training-only cutoff calibration, global shortlist8, then equipped eligibility." : "Baseline fixed cutoffs, equipped-only recognition.");
        int total = 0, correct = 0, rejected = 0, incorrect = 0;
        long[] elapsed = new long[dataset.size() * 3];
        for (var entry : dataset.entrySet()) {
            for (int take = 7; take < 10; take++) {
                long start = System.nanoTime();
                var result = match(recognizer, entry.getValue().get(take), templates, candidates, descriptors, calibration, revised);
                elapsed[total] = System.nanoTime() - start;
                total++;
                if (!result.accepted()) rejected++;
                else if (entry.getKey().equals(result.spellId())) correct++;
                else incorrect++;
                System.out.printf(Locale.ROOT, "%s take=%02d accepted=%s predicted=%s distance=%.5f reason=%s%n",
                        entry.getKey(), take + 1, result.accepted(), result.spellId(), result.distance(), result.reason());
            }
        }
        java.util.Arrays.sort(elapsed);
        System.out.printf(Locale.ROOT, (positiveEnrollmentCount == 7 ? "Held-out totals" : "Positive training-set sanity")
                        + ": correct=%d incorrect=%d rejected=%d total=%d accuracy=%.1f%% p50=%.3f ms p95=%.3f ms%n",
                correct, incorrect, rejected, total, 100.0 * correct / total, elapsed[total / 2] / 1e6,
                elapsed[Math.min(total - 1, (int) Math.ceil(total * .95) - 1)] / 1e6);
        int negativeCount = 0, falseAccept = 0;
        if (Files.isDirectory(negativeFolder)) {
            try (var files = Files.list(negativeFolder)) {
                for (Path file : files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav")).toList()) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    boolean ordinary = name.startsWith("gameplay") || name.startsWith("ordinary") || name.startsWith("conversation");
                    boolean validation = ordinary ? name.matches(".*[_-](0[6-9]|10)\\.wav") : name.matches(".*[_-]0[45]\\.wav");
                    if (negativeSplit.equals("development") && validation || negativeSplit.equals("validation") && !validation) continue;
                    negativeCount++;
                    var negativeResult = match(recognizer, readWav(file), templates, candidates, descriptors, calibration, revised);
                    if (negativeResult.accepted()) falseAccept++;
                    System.out.printf(Locale.ROOT, "Negative clip=%s accepted=%s predicted=%s distance=%.5f runnerUp=%.5f reason=%s%n",
                            file.getFileName(), negativeResult.accepted(), negativeResult.spellId(), negativeResult.distance(), negativeResult.runnerUpDistance(), negativeResult.reason());
                }
            }
        }
        System.out.printf(Locale.ROOT, "Negative isolated clips (%s split): %d false accepts out of %d clips.%n", negativeSplit, falseAccept, negativeCount);
        int crossTotal = 0, crossAccept = 0, singleTotal = 0, singleAccept = 0;
        for (var entry : dataset.entrySet()) {
            var otherIds = new java.util.HashSet<>(candidates);
            otherIds.remove(entry.getKey());
            for (int take = 7; take < 10; take++) {
                crossTotal++;
                if (match(recognizer, entry.getValue().get(take), templates, otherIds, descriptors, calibration, revised).accepted()) crossAccept++;
                for (String other : otherIds) {
                    singleTotal++;
                    if (match(recognizer, entry.getValue().get(take), templates, Set.of(other), descriptors, calibration, revised).accepted()) singleAccept++;
                }
            }
        }
        System.out.printf(Locale.ROOT, "Cross-spell negatives, true spell excluded: %d false accepts out of %d clips.%n", crossAccept, crossTotal);
        System.out.printf(Locale.ROOT, "Single wrong equipped spell: %d false accepts out of %d query/candidate pairs.%n", singleAccept, singleTotal);
        if (negativeCount == 0) System.out.println("No negative clips supplied: false activation validation remains incomplete.");
        System.out.println("This tests offline isolated recordings. Live SVC/microphone behavior and false activations per hour require gameplay testing.");
    }

    private static RecognitionResult match(TemplateRecognizer recognizer, short[] pcm, List<SpeechTemplate> templates,
                                           Set<String> equipped, Map<String, List<float[]>> descriptors,
                                           Map<String, SpellCalibration> calibration, boolean revised) {
        if (!revised) return recognizer.recognize(pcm, 16000, templates, equipped);
        float[][] queryFeatures = new MfccExtractor().extract(pcm, 16000);
        float[] query = TemplateRecognizer.descriptor(queryFeatures);
        var shortlist = new java.util.HashSet<>(TemplateRecognizer.shortlist(query, descriptors, 8));
        shortlist.addAll(equipped);
        var loaded = templates.stream().filter(template -> shortlist.contains(template.spellId())).toList();
        var result = recognizer.recognizeCalibrated(pcm, 16000, loaded, shortlist, calibration);
        if (result.accepted() && !equipped.contains(result.spellId())) {
            return new RecognitionResult(result.spellId(), false, result.distance(), result.runnerUpDistance(), "Known incantation is not equipped");
        }
        return result;
    }
}
