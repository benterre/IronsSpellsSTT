package dev.sts.speech;

import java.util.Arrays;
import java.util.Objects;

/** Dependency-free, thread-safe FFT/MFCC extraction for short local incantations. */
public final class MfccExtractor {
    public static final int SAMPLE_RATE = 16_000;
    public static final int FEATURE_DIMENSIONS = 26;
    public static final int MAX_SAMPLES = SAMPLE_RATE * 3;
    private static final int WINDOW = 400;
    private static final int HOP = 160;
    private static final int FFT = 512;
    private static final int FILTERS = 26;
    private static final int COEFFICIENTS = 13;
    private static final double[] DECIMATION_FILTER = decimationFilter();
    private final double[] window = new double[WINDOW];
    private final double[][] melWeights = new double[FILTERS][FFT / 2 + 1];
    private final double[][] dct = new double[COEFFICIENTS][FILTERS];

    public MfccExtractor() {
        for (int i = 0; i < WINDOW; i++) window[i] = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (WINDOW - 1));
        double low = mel(80), high = mel(7600);
        double[] centers = new double[FILTERS + 2];
        for (int i = 0; i < centers.length; i++) centers[i] = hz(low + (high - low) * i / (FILTERS + 1));
        for (int filter = 0; filter < FILTERS; filter++) {
            for (int bin = 0; bin <= FFT / 2; bin++) {
                double frequency = bin * (double) SAMPLE_RATE / FFT;
                double left = (frequency - centers[filter]) / (centers[filter + 1] - centers[filter]);
                double right = (centers[filter + 2] - frequency) / (centers[filter + 2] - centers[filter + 1]);
                melWeights[filter][bin] = Math.max(0, Math.min(left, right));
            }
        }
        for (int coefficient = 0; coefficient < COEFFICIENTS; coefficient++) {
            for (int filter = 0; filter < FILTERS; filter++) {
                // Exclude coefficient zero: it primarily carries microphone volume.
                dct[coefficient][filter] = Math.sqrt(2.0 / FILTERS)
                        * Math.cos(Math.PI * (coefficient + 1) * (filter + 0.5) / FILTERS) * 0.1;
            }
        }
    }

    /** Converts supported PCM rates to a fresh mono 16 kHz buffer. */
    public short[] normalize(short[] pcm, int sampleRate) {
        Objects.requireNonNull(pcm, "pcm");
        if (sampleRate == SAMPLE_RATE) return pcm.clone();
        if (sampleRate != 48_000) throw new IllegalArgumentException("Expected PCM at 16000 or 48000 Hz");
        short[] result = new short[pcm.length / 3];
        for (int i = 0; i < result.length; i++) {
            double sum = 0;
            int center = i * 3;
            for (int tap = 0; tap < DECIMATION_FILTER.length; tap++) {
                int source = Math.max(0, Math.min(pcm.length - 1, center + tap - 16));
                sum += pcm[source] * DECIMATION_FILTER[tap];
            }
            result[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sum)));
        }
        return result;
    }

    public float[][] extract(short[] pcm, int sampleRate) {
        if (sampleRate != SAMPLE_RATE && sampleRate != 48_000) {
            throw new IllegalArgumentException("Expected PCM at 16000 or 48000 Hz");
        }
        Objects.requireNonNull(pcm, "pcm");
        if (pcm.length > 3L * sampleRate) throw new IllegalArgumentException("Incantation exceeds three seconds");
        short[] samples = normalize(pcm, sampleRate);
        if (samples.length < SAMPLE_RATE / 10) throw new IllegalArgumentException("Incantation too short");
        double mean = 0;
        for (short sample : samples) mean += sample;
        mean /= samples.length;
        double[] centered = new double[samples.length];
        for (int i = 0; i < samples.length; i++) centered[i] = samples[i] - mean;
        int[] bounds = speechBounds(centered);
        int length = bounds[1] - bounds[0];
        if (length < WINDOW) throw new IllegalArgumentException("Recording contains no usable speech");
        int frames = 1 + (length - WINDOW) / HOP;
        float[][] output = new float[frames][FEATURE_DIMENSIONS];
        double[] real = new double[FFT], imaginary = new double[FFT], energies = new double[FILTERS];
        for (int frame = 0; frame < frames; frame++) {
            Arrays.fill(real, 0);
            Arrays.fill(imaginary, 0);
            int offset = bounds[0] + frame * HOP;
            for (int i = 0; i < WINDOW; i++) {
                double previous = offset + i > 0 ? centered[offset + i - 1] : 0;
                real[i] = (centered[offset + i] - 0.97 * previous) / 32768.0 * window[i];
            }
            fft(real, imaginary);
            for (int filter = 0; filter < FILTERS; filter++) {
                double energy = 0;
                for (int bin = 0; bin <= FFT / 2; bin++) {
                    energy += (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) * melWeights[filter][bin];
                }
                energies[filter] = Math.log(Math.max(1e-10, energy));
            }
            for (int coefficient = 0; coefficient < COEFFICIENTS; coefficient++) {
                double value = 0;
                for (int filter = 0; filter < FILTERS; filter++) value += energies[filter] * dct[coefficient][filter];
                output[frame][coefficient] = (float) value;
            }
        }
        for (int frame = 0; frame < frames; frame++) {
            for (int coefficient = 0; coefficient < COEFFICIENTS; coefficient++) {
                double delta = 0;
                for (int distance = 1; distance <= 2; distance++) {
                    delta += distance * (output[Math.min(frames - 1, frame + distance)][coefficient]
                            - output[Math.max(0, frame - distance)][coefficient]);
                }
                output[frame][COEFFICIENTS + coefficient] = (float) (delta / 10);
            }
        }
        return output;
    }

    private static int[] speechBounds(double[] samples) {
        double peak = 0;
        for (int offset = 0; offset < samples.length; offset += HOP) peak = Math.max(peak, rms(samples, offset));
        double threshold = Math.max(140, peak * 0.08);
        int first = -1, last = -1;
        for (int offset = 0; offset < samples.length; offset += HOP) {
            if (rms(samples, offset) >= threshold) {
                if (first < 0) first = offset;
                last = Math.min(samples.length, offset + HOP);
            }
        }
        if (first < 0) return new int[] {0, 0};
        return new int[] {Math.max(0, first - HOP), Math.min(samples.length, last + HOP)};
    }

    private static double rms(double[] samples, int offset) {
        int end = Math.min(samples.length, offset + HOP);
        double sum = 0;
        for (int i = offset; i < end; i++) sum += samples[i] * samples[i];
        return Math.sqrt(sum / Math.max(1, end - offset));
    }

    private static void fft(double[] real, double[] imaginary) {
        for (int i = 1, j = 0; i < FFT; i++) {
            int bit = FFT >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double value = real[i]; real[i] = real[j]; real[j] = value;
            }
        }
        for (int length = 2; length <= FFT; length <<= 1) {
            double angle = -2 * Math.PI / length;
            double stepReal = Math.cos(angle), stepImaginary = Math.sin(angle);
            for (int offset = 0; offset < FFT; offset += length) {
                double wr = 1, wi = 0;
                for (int j = 0; j < length / 2; j++) {
                    int even = offset + j, odd = even + length / 2;
                    double tr = wr * real[odd] - wi * imaginary[odd];
                    double ti = wr * imaginary[odd] + wi * real[odd];
                    real[odd] = real[even] - tr; imaginary[odd] = imaginary[even] - ti;
                    real[even] += tr; imaginary[even] += ti;
                    double next = wr * stepReal - wi * stepImaginary;
                    wi = wr * stepImaginary + wi * stepReal; wr = next;
                }
            }
        }
    }

    private static double mel(double hz) { return 2595 * Math.log10(1 + hz / 700); }
    private static double hz(double mel) { return 700 * (Math.pow(10, mel / 2595) - 1); }

    private static double[] decimationFilter() {
        double[] weights = new double[33];
        double total = 0, cutoff = 0.15;
        for (int i = 0; i < weights.length; i++) {
            int relative = i - 16;
            double sinc = relative == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * relative) / (Math.PI * relative);
            weights[i] = sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * i / 32));
            total += weights[i];
        }
        for (int i = 0; i < weights.length; i++) weights[i] /= total;
        return weights;
    }
}
