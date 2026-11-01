package dev.sts.speech;

/** Enrollment-derived acceptance bounds; scores are distances, never probabilities. */
public record SpellCalibration(double maxDistance, double maxRunnerUpRatio, double minRunnerUpMargin) {
    public SpellCalibration {
        if (!Double.isFinite(maxDistance) || maxDistance <= 0 || maxDistance > 0.12
                || !Double.isFinite(maxRunnerUpRatio) || maxRunnerUpRatio <= 0 || maxRunnerUpRatio >= 1
                || !Double.isFinite(minRunnerUpMargin) || minRunnerUpMargin < 0) {
            throw new IllegalArgumentException("Invalid speech calibration");
        }
    }
}
