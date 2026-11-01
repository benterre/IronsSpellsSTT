package dev.sts.speech;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** A few generic alignment variants; negatives are evaluated, never enrolled or persisted. */
public final class SpeechGenericTuning {
    private SpeechGenericTuning() {}
    public static Map<String, DtwSettings> profiles() {
        var profiles = new LinkedHashMap<String, DtwSettings>();
        profiles.put("baseline", DtwSettings.BASELINE);
        profiles.put("dynamics2", new DtwSettings(2, 0, .55, 1.8));
        profiles.put("dynamics4", new DtwSettings(4, 0, .55, 1.8));
        profiles.put("elastic", new DtwSettings(4, .003, .65, 1.55));
        profiles.put("alignment", new DtwSettings(1, .003, .65, 1.55));
        profiles.put("penalty", new DtwSettings(1, .01, .55, 1.8));
        profiles.put("guarded", DtwSettings.BASELINE);
        profiles.put("balanced", DtwSettings.BASELINE);
        profiles.put("consensus2", DtwSettings.BASELINE);
        return Map.copyOf(profiles);
    }

    public static void main(String[] args) throws Exception {
        Path manifest = Path.of(args.length > 0 ? args[0] : "recordings/prepared/manifest.tsv");
        var dataset = SpeechDatasetTool.load(manifest);
        var extractor = new MfccExtractor();
        var templates = new ArrayList<SpeechTemplate>();
        var baseRecognizer = new TemplateRecognizer();
        for (var entry : dataset.entrySet()) {
            for (int take = 0; take < 7; take++) templates.add(baseRecognizer.createAcousticTemplate(entry.getKey(), entry.getValue().get(take), 16000));
        }
        var negatives = new LinkedHashMap<String, short[]>();
        try (var files = Files.list(manifest.toAbsolutePath().getParent().resolve("negative"))) {
            for (Path file : files.sorted().toList()) {
                if (file.getFileName().toString().matches(".*_0[123]\\.wav")) negatives.put(file.getFileName().toString(), SpeechDatasetTool.readWav(file));
            }
        }
        System.out.println("Positive training01-07 only; positive development08-10; near negatives01-03 evaluation-only. No reserved reads.");
        List<String> names = args.length > 1 ? Arrays.asList(args[1].split(",")) : List.of("baseline", "dynamics2", "dynamics4", "elastic");
        for (String name : names) {
            DtwSettings settings = profiles().get(name);
            if (settings == null) throw new IllegalArgumentException("Unknown generic profile " + name);
            int neighbors = name.equals("consensus2") ? 2 : 1;
            var recognizer = new TemplateRecognizer(settings, neighbors);
            var calibrations = new HashMap<String, SpellCalibration>();
            double multiplier = name.equals("guarded") ? 1.35 : name.equals("balanced") ? 1.4 : 1.6;
            double runnerRatio = name.equals("guarded") || name.equals("balanced") ? .82 : .78;
            for (String id : dataset.keySet()) calibrations.put(id, TemplateRecognizer.calibrate(templates.stream().filter(t -> t.spellId().equals(id)).toList(), settings, multiplier, runnerRatio, neighbors));
            int correct = 0, wrong = 0, rejected = 0, negativeFalse = 0;
            long start = System.nanoTime();
            for (var entry : dataset.entrySet()) for (int take = 7; take < 10; take++) {
                var result = recognizer.recognizeCalibrated(entry.getValue().get(take), 16000, templates, dataset.keySet(), calibrations);
                if (!result.accepted()) rejected++;
                else if (result.spellId().equals(entry.getKey())) correct++;
                else wrong++;
            }
            List<String> falseWords = new ArrayList<>();
            for (var entry : negatives.entrySet()) {
                var result = recognizer.recognizeCalibrated(entry.getValue(), 16000, templates, dataset.keySet(), calibrations);
                if (result.accepted()) { negativeFalse++; falseWords.add(entry.getKey() + "->" + result.spellId()); }
            }
            System.out.printf(Locale.ROOT, "%s %s cutoffMultiplier=%.2f runnerRatio=%.2f: positive correct=%d/30 wrong=%d rejected=%d near false=%d/%d evaluationCPUwall=%.3fms%n",
                    name, settings, multiplier, runnerRatio, correct, wrong, rejected, negativeFalse, negatives.size(), (System.nanoTime() - start) / 1e6);
            System.out.println("False near words: " + String.join(", ", falseWords));
        }
    }
}
