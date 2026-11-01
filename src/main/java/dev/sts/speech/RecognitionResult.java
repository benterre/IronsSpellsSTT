package dev.sts.speech;

/** Distances are normalized squared feature costs, never calibrated probabilities. */
public record RecognitionResult(String spellId, boolean accepted, double distance,
                                double runnerUpDistance, String reason) {
    public static RecognitionResult rejected(String reason) {
        return new RecognitionResult(null, false, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, reason);
    }
}
