package dev.sts.client;

import com.mojang.logging.LogUtils;
import dev.sts.network.RequestCastPayload;
import dev.sts.network.RequestBroadcastCastPayload;
import dev.sts.broadcast.AdpcmCodec;
import dev.sts.broadcast.VoiceClip;
import dev.sts.spell.SpellBridge;
import dev.sts.speech.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One bounded worker owns DSP and disk I/O; Minecraft owns inventory and casting. */
public final class VoiceSpellClient implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long IDLE_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private final ArrayBlockingQueue<Runnable> work = new ArrayBlockingQueue<>(128);
    private final Set<String> pendingLoads = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingPreviews = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingMutations = ConcurrentHashMap.newKeySet();
    private final Set<String> unavailableSpells = ConcurrentHashMap.newKeySet();
    private final StreamingVoiceSegmenter segmenter = new StreamingVoiceSegmenter();
    private final TemplateRecognizer recognizer = new TemplateRecognizer();
    private final MfccExtractor queryExtractor = new MfccExtractor();
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicBoolean resetPending = new AtomicBoolean();
    private final Object contextLock = new Object();
    private final Thread worker;
    private volatile Context context = new Context(0, null, Set.of(), Set.of(), Set.of(), false);
    private volatile Map<String, List<SpeechTemplate>> bank = Map.of();
    private volatile Map<String, List<float[]>> globalDescriptors = Map.of();
    private volatile Map<String, SpellCalibration> calibration = Map.of();
    private volatile boolean indexReady;
    private volatile String viewedSpell;
    private volatile String recordingSpell;
    private volatile PreviewPage preview = new PreviewPage(null, null, List.of());
    private volatile String status = "Connect Simple Voice Chat to train or cast";
    private volatile boolean enabled = true;
    private volatile boolean connected;
    private volatile boolean running = true;
    private volatile boolean discardUntilGap;
    private volatile long lastIncoming;
    private long lastFrame;
    private long segmentEpoch;
    private long lastCast;
    private VoiceLibrary library;
    private String libraryOwner;

    private record Context(long epoch, String playerId, Set<String> candidates, Set<String> cached, Set<String> broadcast, boolean cast) {}
    private record PreviewPage(String owner, String id, List<RecordingPreview> takes) {}
    private static final class Holder { static final VoiceSpellClient INSTANCE = new VoiceSpellClient(); }
    public static VoiceSpellClient get() { return Holder.INSTANCE; }

    private VoiceSpellClient() {
        worker = new Thread(this::runWorker, "STS voice recognition");
        worker.setDaemon(true);
        worker.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "STS shutdown"));
    }

    /** Capture live game state on its owning thread; publish an immutable worker context. */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        String id = mc.player == null ? null : mc.player.getUUID().toString();
        Set<String> ids = mc.player == null ? Set.of() : Set.copyOf(
                SpellBridge.castableSpellIds(mc.player).stream().map(ResourceLocation::toString).toList());
        Set<String> cached = mc.player == null ? Set.of() : Set.copyOf(
                SpellBridge.preparedSpellIds(mc.player).stream().map(ResourceLocation::toString).toList());
        synchronized (contextLock) {
            boolean changedPlayer = !Objects.equals(id, context.playerId());
            boolean changedCandidates = !cached.equals(context.cached());
            if (changedPlayer) {
                epoch.incrementAndGet();
                recordingSpell = viewedSpell = null;
                bank = Map.of();
                globalDescriptors = Map.of();
                calibration = Map.of();
                indexReady = false;
                preview = new PreviewPage(null, null, List.of());
                pendingLoads.clear();
                pendingPreviews.clear();
                pendingMutations.clear();
                unavailableSpells.clear();
                work.clear();
                resetPending.set(true);
            }
            boolean canCast = enabled && connected && mc.player != null && mc.screen == null && !ids.isEmpty();
            if (context.cast() && !canCast) {
                epoch.incrementAndGet();
                resetPending.set(true);
            }
            Set<String> broadcast = canCast ? SpellBridge.broadcastableSpellIds(mc.player) : Set.of();
            context = new Context(epoch.get(), id, ids, cached, broadcast, canCast);
            if (!(mc.screen instanceof GrimoireScreen)) {
                viewedSpell = null;
                preview = new PreviewPage(null, null, List.of());
            }
            if (changedPlayer) submit(() -> openLibrary(id));
            if (changedCandidates) submit(() -> {
                if (!Objects.equals(id, context.playerId())) return;
                for (String spell : cached) loadSpell(spell);
                pruneBank();
            });
        }
    }

    public void audioConnected(boolean active) {
        connected = active;
        if (!active) { resetAudio(); status = "Simple Voice Chat disconnected, muted, or disabled"; }
        else status = "Ready: speak a trained incantation";
    }

    /** SVC retains the original frame; copy before returning to its microphone thread. */
    public void acceptAudio(short[] mono48000) {
        long arrived = System.nanoTime();
        long previous = lastIncoming;
        lastIncoming = arrived;
        if (discardUntilGap) {
            if (arrived - previous <= IDLE_NANOS) return;
            discardUntilGap = false;
            resetPending.set(true);
        }
        Context captured = context;
        if (!connected || captured.playerId() == null || (!captured.cast() && recordingSpell == null)) return;
        if (!indexReady) return;
        if (captured.candidates().isEmpty() && recordingSpell == null) return;
        if (mono48000 == null || mono48000.length == 0 || mono48000.length > 48_000) return;
        short[] copy = mono48000.clone();
        if (!work.offer(() -> {
            if (context.epoch() != captured.epoch() || !connected) return;
            if (segmentEpoch != captured.epoch()) {
                segmenter.reset();
            } else if (lastFrame != 0 && arrived - lastFrame > IDLE_NANOS) {
                segmenter.finish().ifPresent(this::processUtterance);
            }
            segmentEpoch = captured.epoch();
            lastFrame = arrived;
            segmenter.accept(copy).ifPresent(this::processUtterance);
        })) {
            resetAudio();
            discardUntilGap = true;
            status = "Audio queue busy; phrase discarded";
        }
    }

    public void resetAudio() {
        synchronized (contextLock) {
            Context old = context;
            context = new Context(epoch.incrementAndGet(), old.playerId(), old.candidates(), old.cached(), Set.of(), false);
            recordingSpell = null;
            resetPending.set(true);
        }
    }

    public void startRecording(String spellId) {
        if (!ModList.get().isLoaded("voicechat") || !connected || context.playerId() == null) {
            status = "Connect and unmute Simple Voice Chat first";
            return;
        }
        if (!indexReady) { status = "Indexing local spell profiles; try again shortly"; return; }
        if (pendingMutations.contains(context.playerId() + "/" + spellId)) {
            status = "Updating this spell's recordings; try again shortly";
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || SpellBridge.availableSpells(mc.player).stream()
                .noneMatch(spell -> spell.id().toString().equals(spellId))) {
            status = "This spell is no longer available";
            return;
        }
        requestPreview(spellId);
        if (!Objects.equals(preview.id(), spellId) || !Objects.equals(preview.owner(), context.playerId())) {
            status = "Loading this spell's recordings; try again shortly";
            return;
        }
        if (unavailableSpells.contains(spellId)) { status = "Cannot read this file; original preserved"; return; }
        if (count(spellId) >= VoiceProfileStore.MAX_TAKES) { status = "Delete a take before recording another"; return; }
        synchronized (contextLock) {
            if (!connected) return;
            resetAudio();
            recordingSpell = spellId;
            status = "Speak one short incantation (up to 3 seconds)";
        }
    }

    public void cancelRecording() { resetAudio(); status = "Recording cancelled"; }
    public String recordingSpell() { return recordingSpell; }
    public String status() { return status; }
    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) {
        enabled = value;
        resetAudio();
        status = value ? "Voice casting enabled" : "Voice casting disabled";
    }
    public int count(String id) {
        requestPreview(id);
        PreviewPage current = preview;
        return Objects.equals(current.id(), id) && Objects.equals(current.owner(), context.playerId())
                ? current.takes().size() : 0;
    }
    public List<RecordingPreview> waveforms(String id) {
        requestPreview(id);
        PreviewPage current = preview;
        return Objects.equals(current.id(), id) && Objects.equals(current.owner(), context.playerId())
                ? current.takes() : List.of();
    }
    public void deleteRecording(String id, int index) {
        String owner = context.playerId();
        String mutation = owner + "/" + id;
        if (owner == null || !pendingMutations.add(mutation)) return;
        if (!work.offer(() -> {
            try {
                VoiceLibrary source = library;
                if (source == null || !Objects.equals(owner, libraryOwner)
                        || !Objects.equals(owner, context.playerId()) || unavailableSpells.contains(id)) return;
                List<short[]> updated = new ArrayList<>(source.load(id));
                if (index < 0 || index >= updated.size()) return;
                updated.remove(index);
                if (saveSpell(id, updated)) { refreshSpell(owner, id, updated); status = "Recording deleted"; }
            } catch (IOException ex) { profileError(owner, id, ex); }
            finally { pendingMutations.remove(mutation); }
        })) pendingMutations.remove(mutation);
    }

    private void requestPreview(String id) {
        String owner = context.playerId();
        if (id == null || owner == null) return;
        synchronized (contextLock) {
            if (!Objects.equals(viewedSpell, id)) {
                viewedSpell = id;
                preview = new PreviewPage(null, null, List.of());
            }
        }
        PreviewPage current = preview;
        if (Objects.equals(current.id(), id) && Objects.equals(current.owner(), owner)) return;
        String key = owner + "/" + id;
        if (!pendingPreviews.add(key)) return;
        if (!work.offer(() -> {
            try {
                VoiceLibrary source = library;
                if (source == null || !Objects.equals(owner, libraryOwner)
                        || !Objects.equals(owner, context.playerId()) || !Objects.equals(viewedSpell, id)) return;
                publishPreview(owner, id, source.load(id));
            } catch (IOException ex) { profileError(owner, id, ex); }
            finally { pendingPreviews.remove(key); }
        })) pendingPreviews.remove(key);
    }

    private void publishPreview(String owner, String id, List<short[]> takes) {
        List<RecordingPreview> thumbnails = takes.stream().map(RecordingPreview::from).toList();
        synchronized (contextLock) {
            if (Objects.equals(owner, context.playerId()) && Objects.equals(viewedSpell, id)) {
                preview = new PreviewPage(owner, id, thumbnails);
            } else if (Objects.equals(preview.owner(), owner) && Objects.equals(preview.id(), id)) {
                preview = new PreviewPage(null, null, List.of());
            }
        }
    }

    private void profileError(String owner, String id, Exception ex) {
        synchronized (contextLock) {
            if (Objects.equals(owner, context.playerId())) {
                unavailableSpells.add(id);
                publishSpell(owner, id, List.of());
                publishPreview(owner, id, List.of());
                status = "Cannot load this spell's recordings; original preserved";
            }
        }
        LOGGER.warn("STS could not read a spell profile", ex);
    }

    private void requestLoad(String id) {
        String owner = context.playerId();
        if (id == null || owner == null || bank.containsKey(id)) return;
        String key = owner + "/" + id;
        if (!pendingLoads.add(key)) return;
        if (!work.offer(() -> {
            try { if (Objects.equals(owner, context.playerId())) loadSpell(id); }
            finally { pendingLoads.remove(key); }
        })) pendingLoads.remove(key);
    }

    private void openLibrary(String id) {
        library = null;
        libraryOwner = id;
        if (id == null || !Objects.equals(context.playerId(), id)) return;
        Path voices = FMLPaths.CONFIGDIR.get().resolve("sts/voices");
        library = new VoiceLibrary(voices.resolve(id));
        // Development import tool writes a legacy bank. Keep its original as an archive.
        Path imported = voices.resolve(id + ".bin");
        if (Files.exists(imported)) {
            try {
                for (var entry : VoiceProfileStore.load(imported).entrySet()) library.save(entry.getKey(), entry.getValue());
                Files.move(imported, imported.resolveSibling(id + ".bin.imported"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                LOGGER.warn("STS could not import voice bank; original preserved", ex);
                status = "Voice bank import failed; original preserved";
            }
        }
        try {
            Map<String, List<float[]>> descriptors = new HashMap<>();
            Map<String, SpellCalibration> bounds = new HashMap<>();
            for (String spell : library.spellIds()) {
                if (!Objects.equals(id, context.playerId())) return;
                Optional<SpeechSignature> saved = library.loadSignature(spell);
                SpeechSignature signature;
                if (saved.isPresent()) signature = saved.get();
                else {
                    List<SpeechTemplate> examples = library.loadAcousticTemplates(spell);
                    signature = SpeechSignature.fromTemplates(examples);
                    try { library.saveSignature(signature); }
                    catch (IOException cacheWriteFailure) { LOGGER.warn("STS could not save optional signature cache", cacheWriteFailure); }
                }
                descriptors.put(spell, signature.descriptors());
                bounds.put(spell, signature.calibration());
            }
            synchronized (contextLock) {
                if (!Objects.equals(id, context.playerId())) return;
                globalDescriptors = Map.copyOf(descriptors);
                calibration = Map.copyOf(bounds);
                indexReady = true;
            }
        } catch (IOException | IllegalArgumentException ex) {
            status = "Cannot index voice profiles; casting paused, files preserved";
            LOGGER.warn("STS could not index the voice library", ex);
            return;
        }
        for (String spell : context.cached()) loadSpell(spell);
    }

    private void loadSpell(String id) {
        if (bank.containsKey(id) || library == null || !Objects.equals(libraryOwner, context.playerId())) return;
        String owner = libraryOwner;
        try {
            List<SpeechTemplate> loaded = library.loadAcousticTemplates(id);
            synchronized (contextLock) {
                if (Objects.equals(owner, context.playerId())) {
                    unavailableSpells.remove(id);
                    publishSpell(owner, id, loaded);
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            profileError(owner, id, ex);
        }
    }

    private void publishSpell(String owner, String id, List<SpeechTemplate> templates) {
        synchronized (contextLock) {
            if (!Objects.equals(owner, context.playerId())) return;
            Map<String, List<SpeechTemplate>> updated = new HashMap<>(bank);
            updated.put(id, List.copyOf(templates));
            bank = Map.copyOf(updated);
            pruneBank();
        }
    }

    private void pruneBank() {
        synchronized (contextLock) {
            Set<String> keep = new HashSet<>(context.cached());
            Map<String, List<SpeechTemplate>> updated = new HashMap<>(bank);
            updated.keySet().retainAll(keep);
            bank = Map.copyOf(updated);
        }
    }

    private void processUtterance(short[] pcm16000) {
        Context captured = context;
        if (captured.epoch() != segmentEpoch || !connected) return;
        String recording = recordingSpell;
        if (recording != null) {
            try {
                List<short[]> updated = new ArrayList<>(library.load(recording));
                if (updated.size() >= VoiceProfileStore.MAX_TAKES) {
                    status = "Delete a take before recording another";
                    finishRecording(captured, recording);
                    return;
                }
                recognizer.createAcousticTemplate(recording, pcm16000, 16_000);
                updated.add(pcm16000);
                if (context.epoch() != captured.epoch()) return;
                if (saveSpell(recording, updated)) {
                    refreshSpell(captured.playerId(), recording, updated);
                    status = "Recorded take " + updated.size() + "/10";
                }
            } catch (IllegalArgumentException ex) { status = "Recording too short or quiet; try again"; }
            catch (IOException ex) { profileError(captured.playerId(), recording, ex); }
            finishRecording(captured, recording);
            return;
        }
        if (!captured.cast() || System.nanoTime() - lastCast < TimeUnit.MILLISECONDS.toNanos(800)) return;
        if (captured.cached().stream().anyMatch(unavailableSpells::contains)) {
            status = "An equipped spell profile is unreadable; voice casting paused";
            return;
        }
        Map<String, List<SpeechTemplate>> loaded = bank;
        if (!loaded.keySet().containsAll(captured.cached())) {
            for (String id : captured.cached()) requestLoad(id);
            status = "Loading equipped spell recordings; phrase discarded";
            return;
        }
        List<SpeechTemplate> candidates = new ArrayList<>(loaded.values().stream().flatMap(List::stream).toList());
        Set<String> globalCandidates = new HashSet<>(loaded.keySet());
        float[][] queryFeatures;
        float[] descriptor;
        try {
            queryFeatures = queryExtractor.extract(pcm16000, 16_000);
            descriptor = TemplateRecognizer.descriptor(queryFeatures);
            for (String id : TemplateRecognizer.shortlist(descriptor, globalDescriptors, 8)) {
                globalCandidates.add(id);
                if (!loaded.containsKey(id)) {
                    List<SpeechTemplate> examples = library.loadAcousticTemplates(id);
                    if (examples.isEmpty() && globalDescriptors.containsKey(id)) {
                        status = "An indexed spell profile is missing; phrase discarded";
                        return;
                    }
                    candidates.addAll(examples);
                }
                if (captured.epoch() != context.epoch()) return;
            }
        } catch (IOException | IllegalArgumentException ex) {
            status = "Cannot compare voice profiles; phrase discarded";
            LOGGER.warn("STS could not compare the voice shortlist", ex);
            return;
        }
        RecognitionResult result = recognizer.recognizeCalibratedFeatures(queryFeatures, candidates, globalCandidates, calibration);
        if (result.accepted() && !captured.candidates().contains(result.spellId())) {
            status = "Recognized an unavailable spell; no cast";
            return;
        }
        status = result.accepted() ? "Recognized " + result.spellId() : "No match: " + result.reason();
        if (!result.accepted()) return;
        lastCast = System.nanoTime();
        String recognizedId = result.spellId();
        // Reuse the already segmented phrase. No additional microphone buffer or continuous upload.
        VoiceClip replay = captured.broadcast().contains(recognizedId) ? AdpcmCodec.encode(pcm16000) : null;
          ResourceLocation id = ResourceLocation.parse(recognizedId);
          // Construct/validate on the guarded voice worker, before handing anything to the render thread.
          var ordinaryRequest = new RequestCastPayload(id);
          var broadcastRequest = replay == null ? null : new RequestBroadcastCastPayload(id, replay);
        Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (context.epoch() == captured.epoch() && enabled && connected && mc.screen == null && mc.player != null
                    && SpellBridge.castableSpellIds(mc.player).contains(ResourceLocation.parse(recognizedId))) {
                  if (broadcastRequest != null && context.broadcast().contains(recognizedId))
                    PacketDistributor.sendToServer(broadcastRequest);
                  else PacketDistributor.sendToServer(ordinaryRequest);
            }
        });
    }

    private void finishRecording(Context captured, String recording) {
        synchronized (contextLock) {
            if (context.epoch() == captured.epoch() && Objects.equals(recordingSpell, recording)) recordingSpell = null;
        }
    }

    private void refreshSpell(String owner, String id, List<short[]> takes) {
        List<SpeechTemplate> features = takes.stream()
                .map(pcm -> recognizer.createAcousticTemplate(id, pcm, 16_000)).toList();
        SpeechSignature signature = features.isEmpty() ? null : SpeechSignature.fromTemplates(features);
        if (!features.isEmpty()) {
            try { library.saveAcousticTemplates(id, features); }
            catch (IOException cacheWriteFailure) { LOGGER.warn("STS could not save optional acoustic cache", cacheWriteFailure); }
        }
        synchronized (contextLock) {
            if (!Objects.equals(owner, context.playerId())) return;
            Map<String, List<float[]>> descriptors = new HashMap<>(globalDescriptors);
            Map<String, SpellCalibration> bounds = new HashMap<>(calibration);
            if (signature == null) { descriptors.remove(id); bounds.remove(id); }
            else { descriptors.put(id, signature.descriptors()); bounds.put(id, signature.calibration()); }
            globalDescriptors = Map.copyOf(descriptors);
            calibration = Map.copyOf(bounds);
        }
        if (signature != null) {
            try { library.saveSignature(signature); }
            catch (IOException cacheWriteFailure) { LOGGER.warn("STS could not save optional signature cache", cacheWriteFailure); }
        }
        if (context.cached().contains(id)) {
            publishSpell(owner, id, features);
        }
        publishPreview(owner, id, takes);
    }

    private boolean saveSpell(String id, List<short[]> takes) {
        VoiceLibrary target = library;
        if (target == null || !Objects.equals(libraryOwner, context.playerId()) || unavailableSpells.contains(id)) {
            status = "Voice profile unavailable; changes were not saved";
            return false;
        }
        try { target.save(id, takes); return true; }
        catch (IOException ex) { status = "Could not save recordings; see log"; LOGGER.warn("STS could not save a spell profile", ex); return false; }
    }


    private void submit(Runnable command) {
        if (!work.offer(command)) status = "Voice worker busy; try again";
    }

    private void runWorker() {
        while (running) {
            try {
                if (resetPending.getAndSet(false)) { segmenter.reset(); lastFrame = 0; }
                Runnable command = work.poll(50, TimeUnit.MILLISECONDS);
                if (command != null) command.run();
                if (lastFrame != 0 && work.isEmpty() && System.nanoTime() - lastFrame > IDLE_NANOS) {
                    segmenter.finish().ifPresent(this::processUtterance);
                    lastFrame = 0;
                }
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
            catch (RuntimeException ex) { segmenter.reset(); LOGGER.warn("STS discarded a failed phrase", ex); status = "Voice processing failed; see log"; }
        }
    }

    @Override public void close() {
        running = false;
        worker.interrupt();
        try { worker.join(2000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
