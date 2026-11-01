package dev.sts.speech;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

/** Standalone benchmark: synthetic audio verifies cost, never speech-recognition accuracy. */
public final class SpeechBenchmark {
    private static volatile double resultSink;
    private SpeechBenchmark() {}

    public static void main(String[] args) {
        int iterations = args.length == 0 ? 150 : Integer.parseInt(args[0]);
        if (iterations < 1 || iterations > 10000) throw new IllegalArgumentException("Expected 1..10000 iterations");
        System.out.printf(Locale.ROOT, "Java %s, %s/%s, available processors=%d%n",
                System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors());
        System.out.println("Synthetic 1 s mono PCM48k; 10 templates/spell. Wall time includes MFCC extraction + matching.");
        var cpu = ManagementFactory.getThreadMXBean();
        if (cpu.isThreadCpuTimeSupported() && !cpu.isThreadCpuTimeEnabled()) cpu.setThreadCpuTimeEnabled(true);
        for (int spellCount : new int[] {1, 8, 32}) {
            var recognizer = new TemplateRecognizer();
            var templates = new ArrayList<SpeechTemplate>();
            var candidates = new HashSet<String>();
            long primitiveBytes = 0;
            for (int spell = 0; spell < spellCount; spell++) {
                String id = "synthetic:spell_" + spell;
                candidates.add(id);
                for (int take = 0; take < 10; take++) {
                    var template = recognizer.createAcousticTemplate(id, synthetic(16000, 1, spell), 16000);
                    templates.add(template);
                    primitiveBytes += (long) template.frameCount() * 26 * Float.BYTES + (long) template.retainedPcmCount() * Short.BYTES;
                }
            }
            short[] query = synthetic(48000, 1, 0);
            for (int i = 0; i < 60; i++) resultSink = recognizer.recognize(query, 48000, templates, candidates).distance();
            long[] elapsed = new long[iterations];
            long cpuStart = cpu.isThreadCpuTimeSupported() ? cpu.getCurrentThreadCpuTime() : -1;
            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                resultSink = recognizer.recognize(query, 48000, templates, candidates).distance();
                elapsed[i] = System.nanoTime() - start;
            }
            long cpuEnd = cpu.isThreadCpuTimeSupported() ? cpu.getCurrentThreadCpuTime() : -1;
            Arrays.sort(elapsed);
            double mean = Arrays.stream(elapsed).average().orElseThrow() / 1e6;
            System.out.printf(Locale.ROOT,
                    "candidates=%d templates=%d mean=%.3f ms p50=%.3f ms p95=%.3f ms cpuMean=%.3f ms templatePrimitive=%.3f MiB%n",
                    spellCount, templates.size(), mean, elapsed[iterations / 2] / 1e6,
                    elapsed[Math.min(iterations - 1, (int) Math.ceil(iterations * .95) - 1)] / 1e6,
                    cpuStart < 0 ? -1 : (cpuEnd - cpuStart) / (double) iterations / 1e6,
                    primitiveBytes / 1048576.0);
        }
    }

    static short[] synthetic(int sampleRate, double seconds, int spell) {
        short[] pcm = new short[(int) (sampleRate * seconds)];
        for (int i = 0; i < pcm.length; i++) {
            double time = i / (double) sampleRate;
            int syllable = Math.min(3, (int) (time / seconds * 4));
            double base = 180 + spell * 35 + syllable * 100;
            double envelope = Math.min(1, Math.min(time, seconds - time) * 30);
            double value = Math.sin(2 * Math.PI * base * time)
                    + .45 * Math.sin(2 * Math.PI * base * 2.4 * time)
                    + .2 * Math.sin(2 * Math.PI * (base * 4.8 + 80) * time);
            pcm[i] = (short) (6500 * envelope * value);
        }
        return pcm;
    }
}
