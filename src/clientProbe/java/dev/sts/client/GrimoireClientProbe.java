package dev.sts.client;

import com.mojang.logging.LogUtils;
import dev.sts.spell.SpellBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Development source set only. Does not enter a world, contact a server or read a microphone. */
@Mod(value = "sts", dist = Dist.CLIENT)
public final class GrimoireClientProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final long started = System.nanoTime();
    private int stage;
    private int renderedFrames;
    private boolean capturing;

    public GrimoireClientProbe() {
        if (Boolean.getBoolean("sts.clientProbe")) {
            NeoForge.EVENT_BUS.addListener(this::rendered);
        }
    }

    private void rendered(RenderFrameEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(60)) {
            LOGGER.error("STS_CLIENT_PROBE_TIMEOUT stage={} screen={} overlay={}", stage,
                    mc.screen == null ? "null" : mc.screen.getClass().getSimpleName(),
                    mc.getOverlay() == null ? "null" : mc.getOverlay().getClass().getSimpleName());
            mc.stop();
            return;
        }
        if (stage == 0) {
            if ((mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)
                    && mc.getOverlay() == null) {
                mc.options.guiScale().set(Integer.getInteger("sts.probeScale", 3));
                mc.resizeDisplay();
                HotkeyClientProbe.check(mc);
                LOGGER.info("STS_HOTKEY_PROBE_PASS hovered item; private and registered search; typed g; empty slot; unhandled post event");
                mc.setScreen(new GrimoireScreen(null));
                stage = 1;
                renderedFrames = 0;
            }
            return;
        }
        if (capturing || !(mc.screen instanceof GrimoireScreen) || ++renderedFrames < 20) {
            return;
        }
        capturing = true;
        String file = stage == 1 ? "sts-grimoire-empty.png" : "sts-grimoire-ten-takes.png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> mc.execute(() -> {
            if (!Files.isRegularFile(mc.gameDirectory.toPath().resolve("screenshots").resolve(file))) {
                LOGGER.error("STS_CLIENT_PROBE_SCREENSHOT_FAILED {}", message.getString());
                mc.stop();
                return;
            }
            LOGGER.info("STS_CLIENT_PROBE_SCREENSHOT {}", file);
            if (stage == 1) {
                mc.setScreen(new FixtureScreen());
                stage = 2;
                renderedFrames = 0;
                capturing = false;
            } else {
                LOGGER.info("STS_CLIENT_PROBE_PASS empty page and ten-take page rendered at GUI scale {}",
                        mc.options.guiScale().get());
                mc.stop();
            }
        }));
    }

    private static final class FixtureScreen extends GrimoireScreen {
        private static final List<RecordingPreview> FIXTURE_PREVIEWS = createPreviews();
        private int previewRequests;

        private FixtureScreen() {
            super("irons_spellbooks:fireball");
        }

        @Override
        protected List<SpellBridge.SpellEntry> loadCatalog() {
            String[] names = {"Fireball", "Healing Circle", "Lightning Bolt", "Teleport", "Cone of Cold",
                    "Evasion", "Blood Slash", "Summon Vex", "Black Hole", "Flaming Barrage",
                    "Spectral Hammer of the Ancients", "Ignis"};
            List<SpellBridge.SpellEntry> result = new ArrayList<>();
            for (int i = 0; i < names.length; i++) {
                String path = i == 0 ? "fireball" : "probe_spell_" + i;
                result.add(new SpellBridge.SpellEntry(ResourceLocation.fromNamespaceAndPath(
                        i == names.length - 1 ? "example_addon" : "irons_spellbooks", path),
                        Component.literal(names[i]), i % 4 + 1, i % 3 != 2,
                        ResourceLocation.parse("minecraft:textures/item/book.png")));
            }
            return result;
        }

        @Override
        protected int takeCount(String spellId) {
            return spellId.endsWith(":fireball") ? FIXTURE_PREVIEWS.size() : 0;
        }

        @Override
        protected List<RecordingPreview> waveforms(String spellId) {
            // Simulate a worker's disk-read delay while metadata already reports ten takes.
            // A GUI cache keyed only by the take count would remain blank here.
            if (++previewRequests < 10) {
                return List.of();
            }
            return spellId.endsWith(":fireball") ? FIXTURE_PREVIEWS : List.of();
        }

        private static List<RecordingPreview> createPreviews() {
            List<RecordingPreview> previews = new ArrayList<>();
            for (int take = 0; take < 10; take++) {
                short[] pcm = new short[11_200 + take * 800];
                for (int i = 0; i < pcm.length; i++) {
                    double progress = i / (double) pcm.length;
                    double envelope = Math.pow(Math.sin(progress * Math.PI), 2)
                            * (0.4 + 0.6 * Math.abs(Math.sin(progress * Math.PI * 3)));
                    double carrier = Math.sin(i * 2 * Math.PI * (130 + take * 5) / 16_000)
                            + 0.5 * Math.sin(i * 2 * Math.PI * 670 / 16_000);
                    pcm[i] = (short) (carrier * envelope * 12_000);
                }
                previews.add(RecordingPreview.from(pcm));
            }
            return List.copyOf(previews);
        }
    }
}
