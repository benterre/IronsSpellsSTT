package dev.sts.speech;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Stateless matcher. Caller owns the bounded template bank and eligible spell selection. */
public final class TemplateRecognizer {
    public static final int MAX_TEMPLATES_PER_SPELL = 10;
    private static final double DEFAULT_MAX_DISTANCE = 0.12;
    private static final double MAX_RUNNER_UP_RATIO = 0.78;
    private static final double MIN_RUNNER_UP_MARGIN = 0.008;
    private final MfccExtractor extractor = new MfccExtractor();
    private final DtwSettings settings;
    private final int nearestNeighbors;
    public static final int DESCRIPTOR_DIMENSIONS = 8 * MfccExtractor.FEATURE_DIMENSIONS;

    public TemplateRecognizer() { this(DtwSettings.BASELINE); }
    public TemplateRecognizer(DtwSettings settings) { this(settings, 1); }
    public TemplateRecognizer(DtwSettings settings, int nearestNeighbors) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (nearestNeighbors < 1 || nearestNeighbors > 2) throw new IllegalArgumentException("Expected one or two nearest examples");
        this.nearestNeighbors = nearestNeighbors;
    }

    public SpeechTemplate createTemplate(String spellId, short[] pcm, int sampleRate) {
        Objects.requireNonNull(pcm, "pcm");
        float[][] features = extractor.extract(pcm, sampleRate);
        return new SpeechTemplate(spellId, features, extractor.normalize(pcm, sampleRate));
    }

    /** Gameplay enrollment/cache: only MFCCs and duration survive feature extraction. */
    public SpeechTemplate createAcousticTemplate(String spellId, short[] pcm, int sampleRate) {
        Objects.requireNonNull(pcm, "pcm");
        float[][] features = extractor.extract(pcm, sampleRate);
        return new SpeechTemplate(spellId, features, pcm.length / (sampleRate / MfccExtractor.SAMPLE_RATE));
    }

    public RecognitionResult recognize(short[] pcm, int sampleRate, Collection<SpeechTemplate> templates,
                                       Set<String> candidates) {
        Objects.requireNonNull(templates, "templates");
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty() || templates.isEmpty()) return RecognitionResult.rejected("No trained eligible spells");
        float[][] query;
        try { query = extractor.extract(pcm, sampleRate); }
        catch (IllegalArgumentException e) { return RecognitionResult.rejected(e.getMessage()); }
        return recognizeFeatures(query, templates, candidates);
    }

    public RecognitionResult recognizeCalibrated(short[] pcm, int sampleRate, Collection<SpeechTemplate> templates,
                                                Set<String> candidates, Map<String, SpellCalibration> calibration) {
        Objects.requireNonNull(calibration, "calibration");
        if (candidates.isEmpty() || templates.isEmpty()) return RecognitionResult.rejected("No trained eligible spells");
        try { return recognizeFeatures(extractor.extract(pcm, sampleRate), templates, candidates, calibration); }
        catch (IllegalArgumentException e) { return RecognitionResult.rejected(e.getMessage()); }
    }

    /** Reuses the query features already extracted for global coarse search. */
    public RecognitionResult recognizeCalibratedFeatures(float[][] query, Collection<SpeechTemplate> templates,
                                                         Set<String> candidates, Map<String, SpellCalibration> calibration) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(templates, "templates");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(calibration, "calibration");
        if (candidates.isEmpty() || templates.isEmpty()) return RecognitionResult.rejected("No trained eligible spells");
        return recognizeFeatures(query, templates, candidates, calibration);
    }

    RecognitionResult recognizeFeatures(float[][] query, Collection<SpeechTemplate> templates, Set<String> candidates) {
        return recognizeFeatures(query, templates, candidates, Map.of());
    }

    private RecognitionResult recognizeFeatures(float[][] query, Collection<SpeechTemplate> templates, Set<String> candidates,
                                                 Map<String, SpellCalibration> calibration) {
        Map<String, Double> distances = new HashMap<>();
        Map<String, double[]> neighbors = nearestNeighbors == 1 ? null : new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (SpeechTemplate template : templates) {
            if (!candidates.contains(template.spellId())) continue;
            // Bound work even if a malformed imported bank violates the service's cap.
            int count = counts.merge(template.spellId(), 1, Integer::sum);
            if (count > MAX_TEMPLATES_PER_SPELL) continue;
            double distance = distance(query, template.featureData(), settings);
            if (nearestNeighbors == 1) {
                distances.merge(template.spellId(), distance, Math::min);
            } else {
                double[] closest = neighbors.computeIfAbsent(template.spellId(), ignored -> {
                    double[] values = new double[nearestNeighbors]; java.util.Arrays.fill(values, Double.POSITIVE_INFINITY); return values;
                });
                insertNeighbor(closest, distance);
                distances.put(template.spellId(), meanNeighbors(closest));
            }
        }
        String bestId = null;
        double best = Double.POSITIVE_INFINITY, runnerUp = Double.POSITIVE_INFINITY;
        for (var entry : distances.entrySet()) {
            if (entry.getValue() < best) {
                runnerUp = best; best = entry.getValue(); bestId = entry.getKey();
            } else if (entry.getValue() < runnerUp) runnerUp = entry.getValue();
        }
        if (bestId == null) return RecognitionResult.rejected("No matching trained spell");
        SpellCalibration bounds = calibration.getOrDefault(bestId,
                new SpellCalibration(DEFAULT_MAX_DISTANCE, MAX_RUNNER_UP_RATIO, MIN_RUNNER_UP_MARGIN));
        if (best > bounds.maxDistance()) return new RecognitionResult(bestId, false, best, runnerUp, "Unfamiliar pronunciation");
        if (runnerUp - best < bounds.minRunnerUpMargin() || best > runnerUp * bounds.maxRunnerUpRatio()) {
            return new RecognitionResult(bestId, false, best, runnerUp, "Ambiguous incantation");
        }
        return new RecognitionResult(bestId, true, best, runnerUp, "Matched");
    }

    /** Fixed enrollment rule, computed once per spell from training takes only. */
    public static SpellCalibration calibrate(Collection<SpeechTemplate> takes) {
        return calibrate(takes, DtwSettings.BASELINE);
    }

    public static SpellCalibration calibrate(Collection<SpeechTemplate> takes, DtwSettings settings) {
        return calibrate(takes, settings, 1.6, .78);
    }

    public static SpellCalibration calibrate(Collection<SpeechTemplate> takes, DtwSettings settings,
                                              double distanceMultiplier, double runnerUpRatio) {
        return calibrate(takes, settings, distanceMultiplier, runnerUpRatio, 1);
    }

    public static SpellCalibration calibrate(Collection<SpeechTemplate> takes, DtwSettings settings,
                                              double distanceMultiplier, double runnerUpRatio, int nearestNeighbors) {
        if (nearestNeighbors < 1 || nearestNeighbors > 2) throw new IllegalArgumentException("Expected one or two nearest examples");
        if (!Double.isFinite(distanceMultiplier) || distanceMultiplier < .5 || distanceMultiplier > 3
                || !Double.isFinite(runnerUpRatio) || runnerUpRatio < .5 || runnerUpRatio >= .99) {
            throw new IllegalArgumentException("Invalid generic calibration settings");
        }
        List<SpeechTemplate> examples = takes.stream().limit(MAX_TEMPLATES_PER_SPELL).toList();
        if (examples.isEmpty() || examples.stream().anyMatch(example -> !example.spellId().equals(examples.getFirst().spellId()))) {
            throw new IllegalArgumentException("Calibration requires enrollment takes for one spell");
        }
        if (examples.size() < 3) return new SpellCalibration(0.008, runnerUpRatio, 0.001);
        double[] nearest = new double[examples.size()];
        for (int i = 0; i < examples.size(); i++) {
            double[] closest = new double[nearestNeighbors]; java.util.Arrays.fill(closest, Double.POSITIVE_INFINITY);
            for (int j = 0; j < examples.size(); j++) {
                if (i != j) insertNeighbor(closest, distance(examples.get(i).featureData(), examples.get(j).featureData(), settings));
            }
            nearest[i] = meanNeighbors(closest);
        }
        nearest = java.util.Arrays.stream(nearest).filter(Double::isFinite).sorted().toArray();
        if (nearest.length < 3) return new SpellCalibration(.008, runnerUpRatio, .001);
        double p90 = nearest[Math.min(nearest.length - 1, (int) Math.ceil(nearest.length * .9) - 1)];
        double median = nearest[nearest.length / 2];
        return new SpellCalibration(Math.min(.08, Math.max(.004, p90 * distanceMultiplier)), runnerUpRatio,
                Math.max(.0005, Math.min(.004, median * .06)));
    }

    /** Compact temporal pooling: 208 floats per take, independent of phrase length. */
    public static float[] descriptor(float[][] features) {
        if (features.length == 0) throw new IllegalArgumentException("Empty features");
        float[] output = new float[DESCRIPTOR_DIMENSIONS];
        for (int bin = 0; bin < 8; bin++) {
            int start = Math.min(features.length - 1, bin * features.length / 8);
            int end = Math.max(start + 1, (bin + 1) * features.length / 8);
            for (int frame = start; frame < end; frame++) {
                for (int dim = 0; dim < MfccExtractor.FEATURE_DIMENSIONS; dim++) {
                    output[bin * MfccExtractor.FEATURE_DIMENSIONS + dim] += features[frame][dim] / (end - start);
                }
            }
        }
        return output;
    }

    public static float[] descriptor(SpeechTemplate template) { return descriptor(template.featureData()); }

    /** Scores an immutable template without allocating a defensive frame-matrix copy. */
    public static double distanceTo(float[][] query, SpeechTemplate example) { return distance(query, example.featureData()); }
    public static double distanceTo(float[][] query, SpeechTemplate example, DtwSettings settings) { return distance(query, example.featureData(), settings); }

    public static double descriptorDistance(float[] a, float[] b) {
        if (a.length != DESCRIPTOR_DIMENSIONS || b.length != DESCRIPTOR_DIMENSIONS) throw new IllegalArgumentException("Invalid descriptor dimension");
        double sum = 0;
        for (int i = 0; i < a.length; i++) { double difference = a[i] - b[i]; sum += difference * difference; }
        return sum / a.length;
    }

    /** Global acoustic search must precede cast eligibility to recognize known unequipped words. */
    public static java.util.List<String> shortlist(float[] query, Map<String, java.util.List<float[]>> bank, int limit) {
        if (limit < 1 || limit > 32) throw new IllegalArgumentException("Invalid shortlist size");
        var order = java.util.Comparator.comparingDouble(DescriptorRank::distance).thenComparing(DescriptorRank::id);
        var retained = new java.util.PriorityQueue<DescriptorRank>(limit, order.reversed());
        for (var entry : bank.entrySet()) {
            double best = Double.POSITIVE_INFINITY;
            for (float[] example : entry.getValue()) best = Math.min(best, descriptorDistance(query, example));
            DescriptorRank worst = retained.peek();
            int difference = worst == null ? -1 : Double.compare(best, worst.distance());
            boolean better = difference < 0 || difference == 0 && entry.getKey().compareTo(worst.id()) < 0;
            if (retained.size() < limit || better) {
                if (retained.size() == limit) retained.remove();
                retained.add(new DescriptorRank(entry.getKey(), best));
            }
        }
        return retained.stream().sorted(order).map(DescriptorRank::id).toList();
    }

    private record DescriptorRank(String id, double distance) {}

    private static void insertNeighbor(double[] closest, double distance) {
        for (int i = 0; i < closest.length; i++) {
            if (distance < closest[i]) {
                for (int j = closest.length - 1; j > i; j--) closest[j] = closest[j - 1];
                closest[i] = distance;
                return;
            }
        }
    }

    private static double meanNeighbors(double[] closest) {
        double sum = 0; int count = 0;
        for (double distance : closest) if (Double.isFinite(distance)) { sum += distance; count++; }
        return count == 0 ? Double.POSITIVE_INFINITY : sum / count;
    }

    /** Two-row Sakoe-Chiba DTW with path-length-normalized squared distance. */
    public static double distance(float[][] a, float[][] b) {
        return distance(a, b, DtwSettings.BASELINE);
    }

    public static double distance(float[][] a, float[][] b, DtwSettings settings) {
        int n = a.length, m = b.length;
        double minRatio = settings.minDurationRatio(), maxRatio = settings.maxDurationRatio();
        double deltaWeight = settings.deltaWeight(), penalty = settings.nonDiagonalPenalty();
        boolean ordinaryWeights = deltaWeight == 1;
        double dimensions = ordinaryWeights ? MfccExtractor.FEATURE_DIMENSIONS : 13 + 13 * deltaWeight;
        if (n == 0 || m == 0 || n > 298 || m > 298 || (double) n / m < minRatio || (double) n / m > maxRatio) {
            return Double.POSITIVE_INFINITY;
        }
        int band = Math.max(Math.abs(n - m), Math.max(8, (int) Math.ceil(Math.max(n, m) * 0.22)));
        double[] previous = new double[m + 1], current = new double[m + 1];
        int[] previousSteps = new int[m + 1], currentSteps = new int[m + 1];
        java.util.Arrays.fill(previous, Double.POSITIVE_INFINITY);
        previous[0] = 0;
        for (int i = 1; i <= n; i++) {
            java.util.Arrays.fill(current, Double.POSITIVE_INFINITY);
            java.util.Arrays.fill(currentSteps, 0);
            for (int j = Math.max(1, i - band); j <= Math.min(m, i + band); j++) {
                double cost = 0;
                if (ordinaryWeights) {
                    for (int dimension = 0; dimension < MfccExtractor.FEATURE_DIMENSIONS; dimension++) {
                        double difference = a[i - 1][dimension] - b[j - 1][dimension];
                        cost += difference * difference;
                    }
                } else {
                    for (int dimension = 0; dimension < MfccExtractor.FEATURE_DIMENSIONS; dimension++) {
                        double difference = a[i - 1][dimension] - b[j - 1][dimension];
                        cost += difference * difference * (dimension < 13 ? 1 : deltaWeight);
                    }
                }
                double prefix = previous[j - 1];
                int steps = previousSteps[j - 1];
                if (penalty == 0) {
                    if (previous[j] < prefix) { prefix = previous[j]; steps = previousSteps[j]; }
                    if (current[j - 1] < prefix) { prefix = current[j - 1]; steps = currentSteps[j - 1]; }
                } else {
                    if (previous[j] + penalty < prefix) { prefix = previous[j] + penalty; steps = previousSteps[j]; }
                    if (current[j - 1] + penalty < prefix) { prefix = current[j - 1] + penalty; steps = currentSteps[j - 1]; }
                }
                current[j] = prefix + cost / dimensions;
                currentSteps[j] = steps + 1;
            }
            double[] swap = previous; previous = current; current = swap;
            int[] swapSteps = previousSteps; previousSteps = currentSteps; currentSteps = swapSteps;
        }
        return previous[m] / Math.max(1, previousSteps[m]);
    }
}
