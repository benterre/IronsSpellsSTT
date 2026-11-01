package dev.sts.speech;

import dev.sts.client.VoiceLibrary;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Private-audio developer evaluation mirroring the client cache and on-disk shortlist path. */
public final class SpeechRuntimeEvaluation {
    private SpeechRuntimeEvaluation() {}

    public static void main(String[] args) throws Exception {
        Path manifest = Path.of(args.length > 0 ? args[0] : "recordings/prepared/manifest.tsv");
        Path libraryPath = Path.of(args.length > 1 ? args[1] : "build/speech-runtime/library");
        Map<String, List<short[]>> dataset = SpeechDatasetTool.load(manifest);
        if (dataset.isEmpty()) throw new IOException("No real recordings supplied");
        var library = new VoiceLibrary(libraryPath);
        var recognizer = new TemplateRecognizer();
        long firstBuildNanos = 0;
        long maxFirstBuildPcm = 0;
        for (var entry : dataset.entrySet()) {
            List<short[]> training = entry.getValue().subList(0, 7);
            library.save(entry.getKey(), training);
            long buildStart = System.nanoTime();
            var features = library.loadAcousticTemplates(entry.getKey());
            firstBuildNanos += System.nanoTime() - buildStart;
            maxFirstBuildPcm = Math.max(maxFirstBuildPcm, training.stream().mapToLong(take -> (long) take.length * Short.BYTES).sum());
            library.saveSignature(SpeechSignature.fromTemplates(features));
        }
        Set<String> all = Set.copyOf(dataset.keySet());
        var allEquipped = new RuntimeBank(library, all);
        int correct = 0, wrong = 0, rejected = 0, total = 0;
        List<Long> positiveTimes = new ArrayList<>(), diskTimes = new ArrayList<>();
        for (var entry : dataset.entrySet()) for (int take = 7; take < 10; take++) {
            long start = System.nanoTime();
            var result = allEquipped.match(entry.getValue().get(take));
            positiveTimes.add(System.nanoTime() - start);
            total++;
            if (!result.accepted()) rejected++;
            else if (entry.getKey().equals(result.spellId())) correct++;
            else wrong++;
        }
        int cross = 0, crossTotal = 0, single = 0, singleTotal = 0;
        for (var entry : dataset.entrySet()) {
            var other = new HashSet<>(all); other.remove(entry.getKey());
            var othersEquipped = new RuntimeBank(library, other);
            for (int take = 7; take < 10; take++) {
                crossTotal++;
                if (othersEquipped.match(entry.getValue().get(take)).accepted()) cross++;
            }
            for (String wrongId : other) {
                var oneEquipped = new RuntimeBank(library, Set.of(wrongId));
                for (int take = 7; take < 10; take++) {
                    long start = System.nanoTime();
                    if (oneEquipped.match(entry.getValue().get(take)).accepted()) single++;
                    diskTimes.add(System.nanoTime() - start);
                    singleTotal++;
                }
            }
        }
        System.out.printf(Locale.ROOT, "Runtime-shaped held-out: correct=%d wrong=%d rejected=%d total=%d accuracy=%.1f%%%n", correct, wrong, rejected, total, 100.0 * correct / total);
        System.out.printf(Locale.ROOT, "Known unequipped negatives: false accepts=%d/%d%n", cross, crossTotal);
        System.out.printf(Locale.ROOT, "Single wrong equipped spell: false accepts=%d/%d%n", single, singleTotal);
        timings("All ten equipped, warm active feature cache", positiveTimes);
        timings("Single wrong equipped, inactive shortlist feature-sidecar disk reads", diskTimes);
        long featureBytes = allEquipped.active.values().stream().flatMap(List::stream).mapToLong(t -> (long) t.frameCount() * 26 * Float.BYTES).sum();
        long descriptors = allEquipped.descriptors.values().stream().flatMap(List::stream).mapToLong(d -> (long) d.length * Float.BYTES).sum();
        System.out.printf(Locale.ROOT, "Active full-feature primitives=%d bytes (70 templates); global descriptors=%d bytes; retained PCM=0; warm feature-cache PCM reads=0.%n", featureBytes, descriptors);
        System.out.printf(Locale.ROOT, "First acoustic-cache build from 70 training takes: %.3f ms total across ten spell files; maximum one-spell transient PCM=%d bytes. Initial signature calibration is separate.%n", firstBuildNanos / 1e6, maxFirstBuildPcm);
        System.out.println("Dataset arrays belong to this offline harness and are excluded from runtime cache estimates. These are primitive bytes, not process RSS.");
        System.out.println("This evaluator measures positive holdouts and known-unequipped cases only; unknown near-match and conversation clips are evaluated separately by the streaming task. Live SVC/network timing remains unverified.");
    }

    private static void timings(String label, List<Long> nanos) {
        nanos.sort(Long::compare);
        System.out.printf(Locale.ROOT, "%s: p50=%.3fms p95=%.3fms mean=%.3fms samples=%d%n", label,
                nanos.get(nanos.size() / 2) / 1e6, nanos.get((int) Math.ceil(nanos.size() * .95) - 1) / 1e6,
                nanos.stream().mapToLong(Long::longValue).average().orElseThrow() / 1e6, nanos.size());
    }

    static final class RuntimeBank {
        private final VoiceLibrary library;
        private final TemplateRecognizer recognizer;
        private final MfccExtractor extractor;
        private final Set<String> equipped;
        private final Map<String, List<SpeechTemplate>> active = new HashMap<>();
        private final Map<String, List<float[]>> descriptors;
        private final Map<String, SpellCalibration> calibrations;

        RuntimeBank(VoiceLibrary library, Set<String> equipped) throws IOException {
            this(library, equipped, DtwSettings.BASELINE);
        }

        RuntimeBank(VoiceLibrary library, Set<String> equipped, DtwSettings settings) throws IOException {
            this(library, equipped, settings, 1.6, .78, 1);
        }

        RuntimeBank(VoiceLibrary library, Set<String> equipped, DtwSettings settings,
                    double multiplier, double runnerRatio, int neighbors) throws IOException {
            this.library = library; this.equipped = Set.copyOf(equipped);
            this.recognizer = new TemplateRecognizer(settings, neighbors);
            this.extractor = new MfccExtractor();
            var indexedDescriptors = new HashMap<String, List<float[]>>();
            var indexedCalibration = new HashMap<String, SpellCalibration>();
            for (String id : library.spellIds()) {
                SpeechSignature signature = library.loadSignature(id).orElseThrow(() -> new IOException("Missing signature"));
                indexedDescriptors.put(id, signature.descriptors());
                indexedCalibration.put(id, settings.equals(DtwSettings.BASELINE) && multiplier == 1.6 && runnerRatio == .78 && neighbors == 1
                        ? signature.calibration() : TemplateRecognizer.calibrate(library.loadAcousticTemplates(id), settings, multiplier, runnerRatio, neighbors));
            }
            this.descriptors = Map.copyOf(indexedDescriptors);
            this.calibrations = Map.copyOf(indexedCalibration);
            for (String id : equipped) active.put(id, load(id));
        }

        private RuntimeBank(RuntimeBank shared, Set<String> equipped) throws IOException {
            this.library = shared.library;
            this.equipped = Set.copyOf(equipped);
            this.recognizer = shared.recognizer;
            this.extractor = shared.extractor;
            this.descriptors = shared.descriptors;
            this.calibrations = shared.calibrations;
            for (String id : equipped) active.put(id, load(id));
        }

        RuntimeBank withEquipped(Set<String> equipped) throws IOException { return new RuntimeBank(this, equipped); }

        List<SpeechTemplate> load(String id) throws IOException {
            var features = library.loadAcousticTemplates(id);
            if (features.stream().anyMatch(template -> template.retainedPcmCount() != 0)) throw new IllegalStateException("Runtime cache retained PCM");
            return features;
        }

        RecognitionResult match(short[] pcm) throws IOException {
            float[][] queryFeatures = extractor.extract(pcm, 16000);
            var query = TemplateRecognizer.descriptor(queryFeatures);
            var ids = new HashSet<>(TemplateRecognizer.shortlist(query, descriptors, 8)); ids.addAll(equipped);
            List<SpeechTemplate> loaded = new ArrayList<>();
            for (String id : ids) loaded.addAll(active.containsKey(id) ? active.get(id) : load(id));
            var result = recognizer.recognizeCalibratedFeatures(queryFeatures, loaded, ids, calibrations);
            if (result.accepted() && !equipped.contains(result.spellId())) return new RecognitionResult(result.spellId(), false,
                    result.distance(), result.runnerUpDistance(), "Known incantation is not equipped");
            return result;
        }
    }
}
