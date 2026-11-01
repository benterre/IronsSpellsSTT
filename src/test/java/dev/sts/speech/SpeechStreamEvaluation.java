package dev.sts.speech;

import dev.sts.client.VoiceLibrary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Offline original-WAV -> SVC-sized frames -> runtime VAD -> calibrated matching replay.
 * Development is the default; reserved audio requires the explicit validation split.
 */
public final class SpeechStreamEvaluation {
    private static final int FRAME_SAMPLES = 960, INPUT_RATE = 48_000, IDLE_FINISH_MS = 250;
    private static final Pattern TAKE_SUFFIX = Pattern.compile(".*[_-](\\d{2})\\.wav", Pattern.CASE_INSENSITIVE);
    private SpeechStreamEvaluation() {}

    public static void main(String[] args) throws Exception {
        Path manifest = Path.of(args.length > 0 ? args[0] : "recordings/prepared/manifest.tsv").toAbsolutePath().normalize();
        Path originals = Path.of(args.length > 1 ? args[1] : "recordings/incoming").toAbsolutePath().normalize();
        Path output = Path.of(args.length > 2 ? args[2] : "build/speech-stream/development").toAbsolutePath().normalize();
        if (output.equals(originals) || output.startsWith(originals)) throw new IOException("Derived output must be outside originals");
        Files.createDirectories(output);
        Map<String, List<short[]>> prepared = SpeechDatasetTool.load(manifest);
        Map<String, String> prefixes = sourcePrefixes(manifest);
        if (prepared.isEmpty()) throw new IOException("No positive recordings supplied");
        int enrollment = args.length > 3 ? Integer.parseInt(args[3]) : 7;
        if (enrollment != 7 && enrollment != 10) throw new IllegalArgumentException("Enrollment must be7(holdout) or10(self-query sanity)");
        DtwSettings settings = parseSettings(args.length > 4 ? args[4] : "baseline");
        String split = args.length > 5 ? args[5] : "development";
        if (!Set.of("development", "validation").contains(split)) throw new IllegalArgumentException("Negative split must be development or validation");
        CalibrationParameters calibration = parseCalibration(args.length > 6 ? args[6] : "baseline");
        VoiceLibrary library = new VoiceLibrary(output.resolve("library"));
        for (var entry : prepared.entrySet()) {
            library.save(entry.getKey(), entry.getValue().subList(0, enrollment));
            library.saveSignature(SpeechSignature.fromTemplates(library.loadAcousticTemplates(entry.getKey())));
        }
        Set<String> allIds = Set.copyOf(prepared.keySet());
        var allEquipped = new SpeechRuntimeEvaluation.RuntimeBank(library, allIds, settings,
                calibration.multiplier(), calibration.runnerRatio(), calibration.neighbors());
        Counters positives = new Counters(), near = new Counters(), gameplay = new Counters();
        List<Long> segmentCpu = new ArrayList<>(), matchCpu = new ArrayList<>();
        StringBuilder filesReport = new StringBuilder("category\tfile\texpected\tsource_seconds\tframes\tutterances\tcorrect_casts\twrong_casts\trejected\tno_utterance\n");
        StringBuilder segmentsReport = new StringBuilder("category\tfile\tutterance\tendpoint\tvirtual_endpoint_ms\tutterance_ms\taccepted\tpredicted\tdistance\tmatch_cpu_ms\treason\n");
        StringBuilder eligibilityReport = new StringBuilder("file\tutterance\tscope\taccepted\tpredicted\treason\n");
        int crossFalse = 0, crossSegments = 0, singleFalse = 0, singleSegments = 0;
        System.out.println(enrollment == 7 ? "Streaming replay: prepared positive01–07 train; untrimmed original08–10 held out."
                : "Streaming sanity: prepared positive01–10 train; original08–10 are enrolled self-queries, not held out.");
        System.out.println(split.equals("development")
                ? "Negative reads restricted to near-match01–03/gameplay01–05. Reserved negatives are not decoded."
                : "Validation reads restricted to near-match04–05/gameplay06–10; no development negatives queried.");
        System.out.println("Generic DTW: " + settings);
        System.out.println("Generic calibration: " + calibration);
        System.out.println("Mono48k input: 960-sample20ms frames, actual runtime VAD, simulated250ms PTT-idle finish.");
        for (var entry : prepared.entrySet()) {
            Set<String> others = new HashSet<>(allIds); others.remove(entry.getKey());
            var othersEquipped = allEquipped.withEquipped(others);
            for (int take = 8; take <= 10; take++) {
                Path source = safeSource(originals, prefixes.get(entry.getKey()) + String.format(Locale.ROOT, "_%02d.wav", take));
                Replay replay = replay(SpeechDatasetPrepare.readMono48k(source));
                segmentCpu.addAll(replay.frameCpuNanos());
                evaluate("positive", source, entry.getKey(), replay, allEquipped, positives, matchCpu, filesReport, segmentsReport);
                for (int utterance = 0; utterance < replay.utterances().size(); utterance++) {
                    var result = safeMatch(othersEquipped, replay.utterances().get(utterance).pcm16000());
                    crossSegments++; if (result.accepted()) crossFalse++;
                    eligibilityReport.append(String.format(Locale.ROOT, "%s\t%d\tall_except_expected\t%s\t%s\t%s%n",
                            source.getFileName(), utterance + 1, result.accepted(), result.spellId(), result.reason()));
                }
                for (String other : others.stream().sorted().toList()) {
                    var oneEquipped = allEquipped.withEquipped(Set.of(other));
                    for (int utterance = 0; utterance < replay.utterances().size(); utterance++) {
                        var result = safeMatch(oneEquipped, replay.utterances().get(utterance).pcm16000());
                        singleSegments++; if (result.accepted()) singleFalse++;
                        eligibilityReport.append(String.format(Locale.ROOT, "%s\t%d\t%s\t%s\t%s\t%s%n",
                                source.getFileName(), utterance + 1, other, result.accepted(), result.spellId(), result.reason()));
                    }
                }
            }
            System.out.println("Streamed positive originals: " + entry.getKey());
        }
        for (Path source : negativeFiles(originals.resolve("negative"), split)) {
            String category = source.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("gameplay") ? "gameplay" : "near_match";
            Replay replay = replay(SpeechDatasetPrepare.readMono48k(source));
            segmentCpu.addAll(replay.frameCpuNanos());
            evaluate(category, source, null, replay, allEquipped, category.equals("gameplay") ? gameplay : near,
                    matchCpu, filesReport, segmentsReport);
        }
        StringBuilder summary = new StringBuilder();
        summary.append("Generic DTW: ").append(settings).append("; negative split=").append(split).append('\n');
        summary.append("Generic calibration: ").append(calibration).append('\n');
        summarize(summary, enrollment == 7 ? "Held-out positive originals" : "Enrolled positive-original self-query sanity", positives, true);
        summarize(summary, split + " near-match originals", near, false);
        summarize(summary, split + " gameplay originals", gameplay, false);
        summary.append(String.format(Locale.ROOT, "Known unequipped positives: false casts=%d/%d utterances%n", crossFalse, crossSegments));
        summary.append(String.format(Locale.ROOT, "Single wrong equipped spell: false casts=%d/%d utterances%n", singleFalse, singleSegments));
        timing(summary, "20ms frame/finish segmenter CPU", segmentCpu);
        timing(summary, "Emitted-utterance global/calibrated/eligibility CPU", matchCpu);
        summary.append(String.format(Locale.ROOT, "Configured endpoint silence=%dms; simulated PTT idle=%dms; waiting times excluded from CPU.%n",
                StreamingVoiceSegmenter.END_SILENCE_MS, IDLE_FINISH_MS));
        summary.append("Virtual cadence runs without sleeping: SVC microphone/noise suppression, network, Minecraft ticks and listener audibility are not measured.\n");
        summary.append("Long audio is segmented rather than rejected by whole-file duration. No-utterance files are explicit.\n");
        summary.append("Offline source/prepared PCM is excluded from production cache RAM estimates.\n");
        Files.writeString(output.resolve("files.tsv"), filesReport);
        Files.writeString(output.resolve("utterances.tsv"), segmentsReport);
        Files.writeString(output.resolve("eligibility.tsv"), eligibilityReport);
        Files.writeString(output.resolve("summary.txt"), summary);
        System.out.print(summary);
        System.out.println("Streaming " + split + " reports: " + output);
    }

    static List<Path> developmentNegatives(Path folder) throws IOException {
        return negativeFiles(folder, "development");
    }

    static List<Path> negativeFiles(Path folder, String split) throws IOException {
        if (!Set.of("development", "validation").contains(split)) throw new IllegalArgumentException("Unsupported negative split");
        if (!Files.isDirectory(folder)) return List.of();
        Path realBase = folder.toRealPath();
        try (var files = Files.list(folder)) {
            List<Path> selected = files.filter(Files::isRegularFile).filter(path -> {
                String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
                var matcher = TAKE_SUFFIX.matcher(filename);
                if (!matcher.matches()) return false;
                int take = Integer.parseInt(matcher.group(1));
                boolean gameplay = filename.startsWith("gameplay");
                int minimum = split.equals("development") ? 1 : gameplay ? 6 : 4;
                int maximum = split.equals("development") ? gameplay ? 5 : 3 : gameplay ? 10 : 5;
                return take >= minimum && take <= maximum;
            }).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            for (Path path : selected) if (!path.toRealPath().startsWith(realBase)) throw new IOException("Negative WAV leaves original directory");
            return selected;
        }
    }

    static DtwSettings parseSettings(String value) {
        if (value.equals("baseline")) return DtwSettings.BASELINE;
        String[] parts = value.split(",", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Matcher must be baseline or deltaWeight,penalty,minDurationRatio,maxDurationRatio");
        return new DtwSettings(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
    }

    static CalibrationParameters parseCalibration(String value) {
        if (value.equals("baseline")) return new CalibrationParameters(1.6, .78, 1);
        String[] parts = value.split(",", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Calibration must be baseline or cutoffMultiplier,runnerRatio,neighbors");
        return new CalibrationParameters(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Integer.parseInt(parts[2]));
    }

    private static Path safeSource(Path root, String filename) throws IOException {
        Path source = root.resolve(filename).normalize();
        if (!source.startsWith(root) || !source.toRealPath().startsWith(root.toRealPath())) throw new IOException("Positive source leaves original directory");
        return source;
    }

    private static Map<String, String> sourcePrefixes(Path manifest) throws IOException {
        Map<String, String> prefixes = new LinkedHashMap<>();
        for (String line : Files.readAllLines(manifest)) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            String[] columns = line.split("\t", -1);
            if (columns.length != 2 || columns[1].isBlank()) throw new IOException("Invalid source manifest");
            Path relative = Path.of(columns[1]);
            if (relative.getNameCount() != 1 || relative.isAbsolute() || columns[1].contains("..")) throw new IOException("Source prefix must be a relative folder");
            prefixes.put(columns[0], columns[1]);
        }
        return prefixes;
    }

    static Replay replay(short[] pcm48000) {
        StreamingVoiceSegmenter segmenter = new StreamingVoiceSegmenter();
        List<Utterance> utterances = new ArrayList<>();
        List<Long> frameCpu = new ArrayList<>();
        for (int offset = 0; offset < pcm48000.length; offset += FRAME_SAMPLES) {
            int end = Math.min(pcm48000.length, offset + FRAME_SAMPLES);
            short[] frame = Arrays.copyOfRange(pcm48000, offset, end);
            long started = System.nanoTime();
            var utterance = segmenter.accept(frame);
            frameCpu.add(System.nanoTime() - started);
            if (utterance.isPresent()) utterances.add(new Utterance(utterance.get(), "vad_silence", end * 1000.0 / INPUT_RATE));
        }
        long started = System.nanoTime();
        var utterance = segmenter.finish();
        frameCpu.add(System.nanoTime() - started);
        if (utterance.isPresent()) utterances.add(new Utterance(utterance.get(), "ptt_idle", pcm48000.length * 1000.0 / INPUT_RATE + IDLE_FINISH_MS));
        return new Replay(pcm48000.length / (double) INPUT_RATE, List.copyOf(utterances), List.copyOf(frameCpu),
                (pcm48000.length + FRAME_SAMPLES - 1) / FRAME_SAMPLES);
    }

    private static RecognitionResult safeMatch(SpeechRuntimeEvaluation.RuntimeBank bank, short[] pcm) throws IOException {
        try { return bank.match(pcm); }
        catch (IllegalArgumentException error) { return RecognitionResult.rejected(error.getMessage()); }
    }

    private static void evaluate(String category, Path source, String expected, Replay replay,
                                 SpeechRuntimeEvaluation.RuntimeBank bank, Counters totals, List<Long> matchCpu,
                                 StringBuilder filesReport, StringBuilder segmentsReport) throws IOException {
        int correct = 0, wrong = 0, rejected = 0;
        for (int i = 0; i < replay.utterances().size(); i++) {
            Utterance utterance = replay.utterances().get(i);
            long started = System.nanoTime();
            RecognitionResult result = safeMatch(bank, utterance.pcm16000());
            long elapsed = System.nanoTime() - started;
            matchCpu.add(elapsed);
            if (!result.accepted()) rejected++;
            else if (expected != null && expected.equals(result.spellId())) correct++;
            else wrong++;
            segmentsReport.append(String.format(Locale.ROOT, "%s\t%s\t%d\t%s\t%.3f\t%.3f\t%s\t%s\t%.7f\t%.3f\t%s%n",
                    category, source.getFileName(), i + 1, utterance.endpoint(), utterance.virtualEndpointMs(),
                    utterance.pcm16000().length / 16.0, result.accepted(), result.spellId(), result.distance(), elapsed / 1e6, result.reason()));
        }
        totals.files++; totals.seconds += replay.sourceSeconds(); totals.utterances += replay.utterances().size();
        totals.correct += correct; totals.wrong += wrong; totals.rejected += rejected;
        if (replay.utterances().isEmpty()) totals.noUtterance++;
        if (wrong > 0) totals.falseCastFiles++;
        else if (correct > 0) totals.correctFiles++;
        else totals.rejectedFiles++;
        totals.duplicateCorrectCasts += Math.max(0, correct - 1);
        filesReport.append(String.format(Locale.ROOT, "%s\t%s\t%s\t%.3f\t%d\t%d\t%d\t%d\t%d\t%s%n",
                category, source.getFileName(), expected == null ? "negative" : expected, replay.sourceSeconds(),
                replay.frames(), replay.utterances().size(), correct, wrong, rejected, replay.utterances().isEmpty()));
    }

    private static void summarize(StringBuilder output, String label, Counters counters, boolean positive) {
        output.append(String.format(Locale.ROOT, "%s: files=%d audio=%.2fs emitted_utterances=%d no_utterance_files=%d%n",
                label, counters.files, counters.seconds, counters.utterances, counters.noUtterance));
        if (positive) output.append(String.format(Locale.ROOT,
                "  file outcomes correct=%d wrong=%d rejected=%d; casts correct=%d wrong=%d rejected=%d duplicate_correct_casts=%d%n",
                counters.correctFiles, counters.falseCastFiles, counters.rejectedFiles, counters.correct, counters.wrong,
                counters.rejected, counters.duplicateCorrectCasts));
        else output.append(String.format(Locale.ROOT, "  false casts=%d/%d utterances, files_with_false_cast=%d/%d%n",
                counters.wrong, counters.utterances, counters.falseCastFiles, counters.files));
    }

    private static void timing(StringBuilder output, String label, List<Long> times) {
        if (times.isEmpty()) { output.append(label).append(": no samples\n"); return; }
        times.sort(Long::compare);
        output.append(String.format(Locale.ROOT, "%s: p50=%.3fms p95=%.3fms mean=%.3fms samples=%d%n", label,
                times.get(times.size() / 2) / 1e6, times.get(Math.min(times.size() - 1, (int) Math.ceil(times.size() * .95) - 1)) / 1e6,
                times.stream().mapToLong(Long::longValue).average().orElseThrow() / 1e6, times.size()));
    }

    record Utterance(short[] pcm16000, String endpoint, double virtualEndpointMs) {}
    record Replay(double sourceSeconds, List<Utterance> utterances, List<Long> frameCpuNanos, int frames) {}
    record CalibrationParameters(double multiplier, double runnerRatio, int neighbors) {
        CalibrationParameters {
            if (!Double.isFinite(multiplier) || multiplier < .5 || multiplier > 3
                    || !Double.isFinite(runnerRatio) || runnerRatio < .5 || runnerRatio >= .99
                    || neighbors < 1 || neighbors > 2) throw new IllegalArgumentException("Invalid calibration parameters");
        }
    }
    private static final class Counters {
        int files, utterances, correct, wrong, rejected, noUtterance, correctFiles, falseCastFiles, rejectedFiles, duplicateCorrectCasts;
        double seconds;
    }
}
