package dev.sts.speech;

import java.util.Arrays;
import java.util.Optional;

/** Energy VAD for Simple Voice Chat PCM. Own on one worker; reset discards unfinished speech. */
public final class StreamingVoiceSegmenter {
    public static final int END_SILENCE_MS = 220;
    private static final int FRAME = 160;
    private static final int END_FRAMES = END_SILENCE_MS / 10;
    private static final int MIN_VOICED_FRAMES = 12;
    private static final int PRE_ROLL_FRAMES = 6;
    private final MfccExtractor normalizer = new MfccExtractor();
    private final short[] recording = new short[MfccExtractor.MAX_SAMPLES];
    private final short[] pending = new short[FRAME];
    private final short[] preRoll = new short[FRAME * PRE_ROLL_FRAMES];
    private int pendingSize, length, quietFrames, voicedFrames, preRollLength;
    private boolean active, overlong;
    private double noiseRms = 120;

    /** Accepts a normal 20 ms PCM packet at 48 kHz; completion returns mono 16 kHz PCM. */
    public Optional<short[]> accept(short[] mono48k) {
        if (mono48k.length > 48_000) throw new IllegalArgumentException("Audio packet exceeds one second");
        short[] samples = normalizer.normalize(mono48k, 48_000);
        Optional<short[]> result = Optional.empty();
        for (short sample : samples) {
            pending[pendingSize++] = sample;
            if (pendingSize == FRAME) {
                Optional<short[]> finished = consumeFrame();
                pendingSize = 0;
                if (finished.isPresent()) result = finished;
            }
        }
        return result;
    }

    /** Ends a PTT/VAD gap. SVC may stop emitting packets without sending silence. */
    public Optional<short[]> finish() {
        if (active && !overlong && pendingSize > 0 && length + pendingSize <= recording.length) {
            System.arraycopy(pending, 0, recording, length, pendingSize);
            length += pendingSize;
        }
        Optional<short[]> result = completed();
        clearPhrase();
        pendingSize = 0;
        return result;
    }

    /** Mute, disconnect and cancellation must discard speech rather than emit a command. */
    public void reset() {
        clearPhrase();
        pendingSize = 0;
        noiseRms = 120;
    }

    private Optional<short[]> consumeFrame() {
        double energy = 0, mean = 0;
        for (short sample : pending) mean += sample;
        mean /= FRAME;
        for (short sample : pending) { double centered = sample - mean; energy += centered * centered; }
        double rms = Math.sqrt(energy / FRAME);
        boolean voiced = rms > Math.max(280, noiseRms * 2.8);
        if (!active && !overlong && !voiced) {
            noiseRms = 0.98 * noiseRms + 0.02 * Math.min(rms, 2000);
            rememberPreRoll();
            return Optional.empty();
        }
        if (!active && !overlong && voiced) {
            active = true;
            System.arraycopy(preRoll, 0, recording, 0, preRollLength);
            length = preRollLength;
        }
        if (voiced) { quietFrames = 0; voicedFrames++; }
        else quietFrames++;
        if (active && !overlong) {
            if (length + FRAME > recording.length) {
                overlong = true;
                length = 0;
            } else {
                System.arraycopy(pending, 0, recording, length, FRAME);
                length += FRAME;
            }
        }
        if (quietFrames >= END_FRAMES) {
            Optional<short[]> result = completed();
            clearPhrase();
            return result;
        }
        return Optional.empty();
    }

    private Optional<short[]> completed() {
        if (!active || overlong || voicedFrames < MIN_VOICED_FRAMES) return Optional.empty();
        int end = Math.max(FRAME, length - Math.max(0, quietFrames - 2) * FRAME);
        return Optional.of(Arrays.copyOf(recording, end));
    }

    private void rememberPreRoll() {
        if (preRollLength == preRoll.length) {
            System.arraycopy(preRoll, FRAME, preRoll, 0, preRoll.length - FRAME);
            preRollLength -= FRAME;
        }
        System.arraycopy(pending, 0, preRoll, preRollLength, FRAME);
        preRollLength += FRAME;
    }

    private void clearPhrase() {
        length = quietFrames = voicedFrames = preRollLength = 0;
        active = overlong = false;
    }
}
