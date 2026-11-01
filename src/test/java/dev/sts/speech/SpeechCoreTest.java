package dev.sts.speech;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SpeechCoreTest {
    private final TemplateRecognizer recognizer = new TemplateRecognizer();

    @Test void exactExampleRecognizesOnlyEligibleRegistryId() {
        short[] pcm = SpeechBenchmark.synthetic(16_000, 1, 0);
        SpeechTemplate template = recognizer.createTemplate("addon:ignis", pcm, 16_000);
        RecognitionResult result = recognizer.recognize(pcm, 16_000, List.of(template), Set.of("addon:ignis"));
        assertTrue(result.accepted());
        assertEquals("addon:ignis", result.spellId());
        assertEquals(0, result.distance(), 1e-10);
        assertFalse(recognizer.recognize(pcm, 16_000, List.of(template), Set.of("addon:other")).accepted());
        assertFalse(recognizer.recognize(pcm, 16_000, List.of(template), Set.of()).accepted());
    }

    @Test void equivalentIncantationsForDifferentSpellsReject() {
        short[] pcm = SpeechBenchmark.synthetic(16_000, 1, 0);
        var a = recognizer.createTemplate("addon:fire", pcm, 16_000);
        var b = recognizer.createTemplate("addon:ice", pcm, 16_000);
        var result = recognizer.recognize(pcm, 16_000, List.of(a, b), Set.of(a.spellId(), b.spellId()));
        assertFalse(result.accepted());
        assertEquals("Ambiguous incantation", result.reason());
    }

    @Test void silenceDcAndOverlongInputReject() {
        assertThrows(IllegalArgumentException.class, () -> recognizer.createTemplate("addon:fire", new short[16000], 16000));
        short[] dc = new short[16000]; Arrays.fill(dc, (short) 1000);
        assertThrows(IllegalArgumentException.class, () -> recognizer.createTemplate("addon:fire", dc, 16000));
        assertThrows(IllegalArgumentException.class, () -> recognizer.createTemplate("addon:fire", new short[48001], 16000));
        assertThrows(IllegalArgumentException.class, () -> recognizer.createTemplate("addon:fire", new short[8000], 8000));
    }

    @Test void unfamiliarWhiteNoiseRejectsEvenWithOneCandidate() {
        short[] pcm = SpeechBenchmark.synthetic(16_000, 1, 0);
        var template = recognizer.createTemplate("addon:fire", pcm, 16000);
        short[] noise = new short[16000];
        var random = new java.util.Random(11);
        for (int i = 0; i < noise.length; i++) noise[i] = (short) (random.nextInt(12000) - 6000);
        assertFalse(recognizer.recognize(noise, 16000, List.of(template), Set.of(template.spellId())).accepted());
    }

    @Test void templateIsIndependentOfConstructorAndGetterArrays() {
        short[] pcm = SpeechBenchmark.synthetic(16_000, 1, 0);
        float[][] features = new MfccExtractor().extract(pcm, 16000);
        var template = new SpeechTemplate("addon:fire", features, pcm);
        float original = features[0][0]; short originalSample = pcm[200];
        features[0][0] = 999; pcm[200] = 999;
        template.features()[0][0] = 888; template.recording()[200] = 888;
        assertEquals(original, template.features()[0][0]);
        assertEquals(originalSample, template.recording()[200]);
    }

    @Test void gameplayTemplateRetainsNoPcmAndMatchesIdentically() {
        short[] pcm = SpeechBenchmark.synthetic(16000, 1, 0);
        var template = recognizer.createAcousticTemplate("addon:fire", pcm, 16000);
        assertEquals(16000, template.sampleCount());
        assertEquals(0, template.retainedPcmCount());
        assertEquals(0, template.recording().length);
        assertTrue(recognizer.recognize(pcm, 16000, List.of(template), Set.of(template.spellId())).accepted());
        pcm[400] = 32767;
        assertEquals(0, template.retainedPcmCount());
    }

    @Test void compactSignatureIsImmutableAndKeepsKnownUnequippedWordInGlobalSearch() {
        var a = recognizer.createAcousticTemplate("addon:fire", SpeechBenchmark.synthetic(16000, 1, 0), 16000);
        var b = recognizer.createAcousticTemplate("addon:ice", SpeechBenchmark.synthetic(16000, 1, 8), 16000);
        var signature = SpeechSignature.fromTemplates(List.of(a, a, a));
        float original = signature.descriptors().getFirst()[0];
        signature.descriptors().getFirst()[0] = 999;
        assertEquals(original, signature.descriptors().getFirst()[0]);
        assertEquals(208, signature.descriptors().getFirst().length);
        var descriptors = java.util.Map.of(a.spellId(), signature.descriptors(), b.spellId(), List.of(TemplateRecognizer.descriptor(b)));
        assertEquals(a.spellId(), TemplateRecognizer.shortlist(TemplateRecognizer.descriptor(a), descriptors, 1).getFirst());
        var result = recognizer.recognizeCalibrated(SpeechBenchmark.synthetic(16000, 1, 0), 16000, List.of(a, b),
                descriptors.keySet(), java.util.Map.of(a.spellId(), signature.calibration(), b.spellId(), TemplateRecognizer.calibrate(List.of(b, b, b))));
        assertTrue(result.accepted());
        assertEquals(a.spellId(), result.spellId());
        assertFalse(Set.of(b.spellId()).contains(result.spellId()), "Gameplay must reject this global winner when only ice is equipped");
        assertThrows(IllegalArgumentException.class, () -> TemplateRecognizer.calibrate(List.of(a, b)));
    }

    @Test void boundedShortlistMatchesExactRankingAndDeterministicTies() {
        float[] query = new float[208]; Arrays.fill(query, 17.3f);
        var bank = new java.util.LinkedHashMap<String, List<float[]>>();
        for (int i = 19; i >= 0; i--) {
            float[] descriptor = new float[208]; Arrays.fill(descriptor, i);
            bank.put("spell:" + i, List.of(descriptor));
        }
        assertEquals(List.of("spell:17", "spell:18", "spell:16", "spell:19", "spell:15"), TemplateRecognizer.shortlist(query, bank, 5));
        float[] zero = new float[208];
        assertEquals(List.of("spell:alpha", "spell:beta"), TemplateRecognizer.shortlist(zero,
                java.util.Map.of("spell:gamma", List.of(zero), "spell:beta", List.of(zero), "spell:alpha", List.of(zero)), 2));
    }

    @Test void dtwHandlesTimingVariationAndRejectsVeryDifferentDuration() {
        float[][] a = new float[30][26], b = new float[36][26];
        for (int i = 0; i < a.length; i++) Arrays.fill(a[i], i / 10);
        for (int i = 0; i < b.length; i++) Arrays.fill(b[i], i / 12);
        assertEquals(0, TemplateRecognizer.distance(a, b));
        assertEquals(Double.POSITIVE_INFINITY, TemplateRecognizer.distance(a, new float[100][26]));
    }

    @Test void genericAlignmentSettingsChangeDynamicsAndPenalizeExcessWarping() {
        float[][] a = new float[20][26], b = new float[20][26];
        for (float[] frame : a) { frame[0] = 1; frame[13] = 2; }
        assertTrue(TemplateRecognizer.distance(a, b, new DtwSettings(4, 0, .55, 1.8)) > TemplateRecognizer.distance(a, b));
        float[][] shorter = new float[20][26], stretched = new float[26][26];
        assertEquals(0, TemplateRecognizer.distance(shorter, stretched));
        assertTrue(TemplateRecognizer.distance(shorter, stretched, new DtwSettings(1, .01, .55, 1.8)) > 0);
        assertThrows(IllegalArgumentException.class, () -> new DtwSettings(Double.NaN, 0, .55, 1.8));
    }

    @Test void precomputedQueryRecognitionMatchesPcmPathExactly() {
        short[] pcm = SpeechBenchmark.synthetic(48000, 1, 0);
        var template = recognizer.createAcousticTemplate("addon:fire", pcm, 48000);
        var templates = List.of(template);
        var candidates = Set.of(template.spellId());
        var calibration = java.util.Map.of(template.spellId(), TemplateRecognizer.calibrate(List.of(template, template, template)));
        float[][] query = new MfccExtractor().extract(pcm, 48000);
        assertEquals(recognizer.recognizeCalibrated(pcm, 48000, templates, candidates, calibration),
                recognizer.recognizeCalibratedFeatures(query, templates, candidates, calibration));
        assertFalse(recognizer.recognizeCalibratedFeatures(query, templates, Set.of(), calibration).accepted());
    }

    @Test void decimatorSuppressesAboveNyquistAudio() {
        var extractor = new MfccExtractor();
        short[] low = sine(1000), high = sine(12000);
        assertTrue(rms(extractor.normalize(high, 48000)) < rms(extractor.normalize(low, 48000)) * 0.03);
        assertEquals(16000, extractor.normalize(low, 48000).length);
    }

    @Test void segmenterEndsOnSilenceAndCanStartAgain() {
        var segmenter = new StreamingVoiceSegmenter();
        short[] packet = Arrays.copyOf(SpeechBenchmark.synthetic(48000, 1, 0), 960);
        for (int i = 0; i < 20; i++) assertTrue(segmenter.accept(packet).isEmpty());
        java.util.Optional<short[]> result = java.util.Optional.empty();
        for (int i = 0; i < 11; i++) {
            var completed = segmenter.accept(new short[960]);
            if (completed.isPresent()) result = completed;
        }
        assertTrue(result.isPresent());
        assertTrue(result.get().length >= 6400);
        for (int i = 0; i < 20; i++) segmenter.accept(packet);
        assertTrue(segmenter.finish().isPresent());
        assertTrue(segmenter.finish().isEmpty());
    }

    @Test void pttGapFinishesButMuteCancellationDropsSpeech() {
        var segmenter = new StreamingVoiceSegmenter();
        short[] packet = Arrays.copyOf(SpeechBenchmark.synthetic(48000, 1, 0), 960);
        for (int i = 0; i < 20; i++) segmenter.accept(packet);
        assertTrue(segmenter.finish().isPresent());
        for (int i = 0; i < 20; i++) segmenter.accept(packet);
        segmenter.reset();
        assertTrue(segmenter.finish().isEmpty());
    }

    @Test void overlongSpeechIsDiscardedRatherThanSplitIntoCommands() {
        var segmenter = new StreamingVoiceSegmenter();
        short[] packet = Arrays.copyOf(SpeechBenchmark.synthetic(48000, 1, 0), 960);
        for (int i = 0; i < 170; i++) assertTrue(segmenter.accept(packet).isEmpty());
        assertTrue(segmenter.finish().isEmpty());
        for (int i = 0; i < 20; i++) segmenter.accept(packet);
        assertTrue(segmenter.finish().isPresent());
    }

    @Test void briefClickDoesNotBecomeSpeech() {
        var segmenter = new StreamingVoiceSegmenter();
        short[] packet = Arrays.copyOf(SpeechBenchmark.synthetic(48000, 1, 0), 960);
        segmenter.accept(packet);
        assertTrue(segmenter.finish().isEmpty());
    }

    private static short[] sine(double frequency) {
        short[] pcm = new short[48000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (12000 * Math.sin(2 * Math.PI * frequency * i / 48000));
        return pcm;
    }
    private static double rms(short[] pcm) {
        double sum = 0;
        for (int i = 20; i < pcm.length - 20; i++) sum += (double) pcm[i] * pcm[i];
        return Math.sqrt(sum / (pcm.length - 40));
    }
}
