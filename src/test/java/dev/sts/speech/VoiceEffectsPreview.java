package dev.sts.speech;

import dev.sts.broadcast.AdpcmCodec;
import dev.sts.broadcast.VoiceClip;
import dev.sts.broadcast.VoiceEffects;
import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Development-only audition of the shipping effect chain; source recordings are read-only. */
public final class VoiceEffectsPreview {
    private record Take(String name, String source) {}
    private static final List<Take> TAKES = List.of(
            new Take("Fireball", "fireball_01"), new Take("Firebolt", "firebolt_01"),
            new Take("Heal", "heal_01"), new Take("Teleport", "teleport_01"),
            new Take("Frostwave", "frostwave_01"), new Take("Lightning Bolt", "lightningBolt_01"),
            new Take("Blood Step", "bloodStep_01"), new Take("Gust", "gust_01"),
            new Take("Shield", "shield_01"), new Take("Magic Missile", "magicMissile_01"));
    private VoiceEffectsPreview() {}

    public static void main(String[] args) throws Exception {
        Path incoming = Path.of(args.length > 0 ? args[0] : "recordings/incoming").toRealPath();
        Path output = Path.of(args.length > 1 ? args[1] : "recordings/audition-reverb-v2").toAbsolutePath().normalize();
        Files.createDirectories(output);
        output = output.toRealPath();
        if (output.startsWith(incoming) || incoming.startsWith(output))
            throw new IllegalArgumentException("Audition output must be separate from source recordings");
        StringBuilder report = new StringBuilder("source\tsource_sha256\tinput_seconds\tpitched_seconds\ttail_seconds\toutput_seconds\tpeak\trms_dbfs\tfinal_100ms_rms_dbfs\n");
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="en"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>STS — stronger reverb audition v2</title>
                <style>
                  body{background:#14121b;color:#eee9dc;font:17px system-ui;max-width:900px;margin:48px auto;padding:0 24px}
                  h1{font-size:30px}p{line-height:1.6;color:#c5bece}section{border:1px solid #494052;border-radius:12px;padding:16px 24px;margin:20px 0}
                  .players{display:flex;flex-wrap:wrap;gap:20px}label{display:grid;gap:10px;font-size:14px}audio{max-width:100%}
                </style>
                <h1>STS — stronger reverb audition v2</h1>
                <p>One take from each of ten spells. Original files are unchanged; the dry comparisons remove leading and trailing silence.
                The new clips use the same IMA ADPCM round trip and Java effects as the mod, with much stronger diffuse reverb,
                loudness normalization and a soft limiter. The voice plays 20% slower than the first previews; resampling
                also lowers its pitch further. Exactly 0.5 seconds of decay follow the slowed voice, fading smoothly to silence.</p>
                <p>These are local 48 kHz WAV previews before Simple Voice Chat's Opus encoding, positional attenuation and volume settings.
                This preset was approved for STS 0.3.0. Start at a comfortable playback volume.</p>
                """);
        int count = 0;
        for (Take take : TAKES) {
            Path source = incoming.resolve(take.source() + ".wav");
            short[] pcm = SpeechDatasetPrepare.trim(SpeechDatasetPrepare.readMono16k(source));
            if (pcm.length < 1 || pcm.length > VoiceClip.MAX_SAMPLES)
                throw new IllegalArgumentException("Trimmed recording exceeds the runtime bound: " + source.getFileName());
            short[] effect = VoiceEffects.render(AdpcmCodec.decode(AdpcmCodec.encode(pcm)));
            if (effect.length == 0) throw new IllegalArgumentException("Recording is silent: " + source.getFileName());
            String dry = take.source() + "_dry.wav", processed = take.source() + "_reverb.wav";
            writeWav(output.resolve(dry), pcm, VoiceClip.SAMPLE_RATE);
            writeWav(output.resolve(processed), effect, VoiceEffects.OUTPUT_RATE);
            int peak = 0;
            for (short sample : effect) peak = Math.max(peak, Math.abs((int)sample));
            double pitched = Math.ceil(pcm.length / VoiceEffects.PITCH_RATIO) / VoiceClip.SAMPLE_RATE;
            double seconds = effect.length / (double)VoiceEffects.OUTPUT_RATE;
            if (Math.abs(seconds - pitched - 0.5) > 1e-9 || peak > 31128 || effect[effect.length - 1] != 0)
                throw new IllegalStateException("Audition output failed signal bounds: " + processed);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            report.append(String.format(Locale.ROOT, "%s.wav\t%s\t%.6f\t%.6f\t0.500000\t%.6f\t%d\t%.2f\t%.2f%n",
                    take.source(), hash, pcm.length / 16000.0, pitched, seconds, peak,
                    rmsDb(effect, 0), rmsDb(effect, effect.length - VoiceEffects.OUTPUT_RATE / 10)));
            html.append(String.format(Locale.ROOT, """
                    <section><h2>%s</h2><div class="players">
                    <label>Dry comparison<audio controls preload="none" src="%s"></audio></label>
                    <label>Stronger reverb + slower voice · %.2f s<audio controls preload="none" src="%s"></audio></label>
                    """, take.name(), dry, seconds, processed));
            if (Files.isRegularFile(output.resolveSibling("audition-reverb-v1").resolve(processed)))
                html.append(String.format(Locale.ROOT,
                        "<label>Previous preview<audio controls preload=\"none\" src=\"../audition-reverb-v1/%s\"></audio></label>%n", processed));
            html.append("</div></section>\n");
            System.out.printf(Locale.ROOT, "Audition %d/%d: %s — %.3f s voice, %.3f s pitched voice + 0.500 s reverb tail%n",
                    ++count, TAKES.size(), take.name(), pcm.length / 16000.0, pitched);
        }
        html.append("</html>\n");
        Files.writeString(output.resolve("index.html"), html);
        Files.writeString(output.resolve("signal-report.tsv"), report);
        Files.writeString(output.resolve("README.txt"), String.format(Locale.ROOT,
                "STS stronger reverb audition v2%nTen source takes; all source recordings opened read-only.%n"
                + "Runtime chain: 16 kHz mono -> IMA ADPCM encode/decode -> resampling ratio %.3f -> damped Schroeder reverb -> loudness normalization/soft limiter -> 48 kHz mono PCM16 WAV.%n"
                + "Speed is %.0f%% of the previous pitch-lowered preset; resampling also lowers pitch further (effective pitch ratio %.3f relative to the original).%n"
                + "Reverb: eight parallel damped comb filters and four serial allpass diffusers; wet gain %.2f; approximate RT60 %.2f s; appended tail exactly 0.5 s with a final 50 ms fade.%n"
                + "Dry comparisons contain the same trimmed input before ADPCM or effects. SVC Opus encoding, positional attenuation and volume settings are not simulated.%n"
                + "Open index.html to compare. signal-report.tsv records source hashes, durations, peaks and decay levels.%n",
                VoiceEffects.PITCH_RATIO, VoiceEffects.PLAYBACK_SPEED_RATIO * 100, VoiceEffects.PITCH_RATIO,
                VoiceEffects.REVERB_WET_GAIN, VoiceEffects.REVERB_DECAY_SECONDS));
        System.out.println("Ten full-effects WAVs and dry comparisons written: " + output);
    }

    private static double rmsDb(short[] pcm, int start) {
        double sum = 0;
        for (int i = start; i < pcm.length; i++) sum += pcm[i] * (double)pcm[i];
        return 20 * Math.log10(Math.max(1e-12, Math.sqrt(sum / (pcm.length - start)) / 32768));
    }

    private static void writeWav(Path path, short[] pcm, int sampleRate) throws Exception {
        byte[] bytes = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) { bytes[i * 2] = (byte)pcm[i]; bytes[i * 2 + 1] = (byte)(pcm[i] >> 8); }
        try (var stream = new AudioInputStream(new ByteArrayInputStream(bytes), new AudioFormat(sampleRate, 16, 1, true, false), pcm.length)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }
}
