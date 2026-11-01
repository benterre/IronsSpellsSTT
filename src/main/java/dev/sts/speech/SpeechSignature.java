package dev.sts.speech;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Compact immutable global search index entry; carries no PCM or full frame sequence. */
public final class SpeechSignature {
    private final String spellId;
    private final List<float[]> descriptors;
    private final SpellCalibration calibration;

    public SpeechSignature(String spellId, List<float[]> descriptors, SpellCalibration calibration) {
        this.spellId = Objects.requireNonNull(spellId, "spellId");
        this.calibration = Objects.requireNonNull(calibration, "calibration");
        if (spellId.isBlank() || descriptors.isEmpty() || descriptors.size() > 10) throw new IllegalArgumentException("Invalid speech signature");
        var copied = new ArrayList<float[]>();
        for (float[] descriptor : descriptors) {
            if (descriptor.length != TemplateRecognizer.DESCRIPTOR_DIMENSIONS) throw new IllegalArgumentException("Invalid descriptor dimension");
            for (float value : descriptor) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite descriptor");
            copied.add(descriptor.clone());
        }
        this.descriptors = List.copyOf(copied);
    }

    public static SpeechSignature fromTemplates(Collection<SpeechTemplate> templates) {
        List<SpeechTemplate> examples = List.copyOf(templates);
        if (examples.isEmpty() || examples.size() > 10) throw new IllegalArgumentException("Expected one to ten enrollment templates");
        String id = examples.getFirst().spellId();
        if (examples.stream().anyMatch(template -> !id.equals(template.spellId()))) throw new IllegalArgumentException("Mixed spell identifiers");
        return new SpeechSignature(id, examples.stream().map(TemplateRecognizer::descriptor).toList(), TemplateRecognizer.calibrate(examples));
    }

    public String spellId() { return spellId; }
    public List<float[]> descriptors() { return descriptors.stream().map(float[]::clone).toList(); }
    public SpellCalibration calibration() { return calibration; }
    public double threshold() { return calibration.maxDistance(); }
}
