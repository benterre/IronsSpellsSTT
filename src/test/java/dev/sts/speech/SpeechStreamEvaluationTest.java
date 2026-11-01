package dev.sts.speech;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SpeechStreamEvaluationTest {
    @TempDir Path directory;

    @Test void selectsOnlyDevelopmentNegativesWithoutOpeningReservedAudio() throws Exception {
        for (String filename : List.of("near_01.wav", "near_03.wav", "near_04.wav", "near_05.wav",
                "gameplay_01.wav", "gameplay_05.wav", "gameplay_06.wav", "gameplay_10.wav", "notes.txt"))
            Files.writeString(directory.resolve(filename), "Deliberately not a WAV file");
        assertEquals(List.of("gameplay_01.wav", "gameplay_05.wav", "near_01.wav", "near_03.wav"),
                SpeechStreamEvaluation.developmentNegatives(directory).stream().map(path -> path.getFileName().toString()).toList());
    }

    @Test void longGameplayAudioIsSegmentedInsteadOfRejectedByWholeFileDuration() {
        short[] audio = new short[48_000 * 8];
        addTone(audio, 48_000, 48_000 / 2);
        addTone(audio, 48_000 * 5, 48_000 / 2);
        var replay = SpeechStreamEvaluation.replay(audio);
        assertEquals(8.0, replay.sourceSeconds());
        assertEquals(400, replay.frames());
        assertEquals(2, replay.utterances().size());
        assertTrue(replay.utterances().stream().allMatch(utterance -> utterance.endpoint().equals("vad_silence")));
    }

    @Test void validationSelectionIsDisjointAndCanBeCheckedWithoutDecodingAudio() throws Exception {
        for (String filename : List.of("near_01.wav", "near_03.wav", "near_04.wav", "near_05.wav",
                "gameplay_01.wav", "gameplay_05.wav", "gameplay_06.wav", "gameplay_10.wav"))
            Files.writeString(directory.resolve(filename), "Synthetic selection fixture, not real audio");
        var validation = SpeechStreamEvaluation.negativeFiles(directory, "validation");
        assertEquals(List.of("gameplay_06.wav", "gameplay_10.wav", "near_04.wav", "near_05.wav"),
                validation.stream().map(path -> path.getFileName().toString()).toList());
        assertTrue(SpeechStreamEvaluation.developmentNegatives(directory).stream().noneMatch(validation::contains));
    }

    @Test void matcherSettingsAreExplicitAndValidated() {
        assertEquals(DtwSettings.BASELINE, SpeechStreamEvaluation.parseSettings("baseline"));
        assertEquals(new DtwSettings(1, .005, .55, 1.8), SpeechStreamEvaluation.parseSettings("1,0.005,0.55,1.8"));
        assertThrows(IllegalArgumentException.class, () -> SpeechStreamEvaluation.parseSettings("1,NaN,.55,1.8"));
    }

    @Test void calibrationParametersDistinguishBaselineAndTwoExampleConsensus() {
        assertEquals(1, SpeechStreamEvaluation.parseCalibration("baseline").neighbors());
        assertEquals(2, SpeechStreamEvaluation.parseCalibration("1.6,.78,2").neighbors());
        assertThrows(IllegalArgumentException.class, () -> SpeechStreamEvaluation.parseCalibration("1.6,.78,3"));
        assertThrows(IllegalArgumentException.class, () -> SpeechStreamEvaluation.parseCalibration("1.6,NaN,2"));
    }

    @Test void pushToTalkFinishAddsSimulatedIdleDelayAndEmitsPendingSpeech() {
        short[] audio = new short[48_000 / 2];
        addTone(audio, 0, audio.length);
        var replay = SpeechStreamEvaluation.replay(audio);
        assertEquals(1, replay.utterances().size());
        assertEquals("ptt_idle", replay.utterances().getFirst().endpoint());
        assertEquals(750.0, replay.utterances().getFirst().virtualEndpointMs());
    }

    private static void addTone(short[] audio, int start, int samples) {
        for (int i = 0; i < samples; i++) audio[start + i] = (short) (5000 * Math.sin(2 * Math.PI * 180 * i / 48_000));
    }
}
