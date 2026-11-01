package dev.sts.speech;

/** Generic acoustic alignment parameters; contains no spell vocabulary or speaker data. */
public record DtwSettings(double deltaWeight, double nonDiagonalPenalty,
                          double minDurationRatio, double maxDurationRatio) {
    public static final DtwSettings BASELINE = new DtwSettings(1, 0, .55, 1.8);

    public DtwSettings {
        if (!Double.isFinite(deltaWeight) || deltaWeight < .1 || deltaWeight > 16
                || !Double.isFinite(nonDiagonalPenalty) || nonDiagonalPenalty < 0 || nonDiagonalPenalty > .1
                || !Double.isFinite(minDurationRatio) || minDurationRatio < .3 || minDurationRatio > 1
                || !Double.isFinite(maxDurationRatio) || maxDurationRatio < 1 || maxDurationRatio > 3) {
            throw new IllegalArgumentException("Invalid generic DTW settings");
        }
    }
}
