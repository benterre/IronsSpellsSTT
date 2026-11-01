package dev.sts.client;

import dev.sts.spell.SpellBridge;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** A two-page book rendered with vanilla widgets; no external textures or UI framework. */
public class GrimoireScreen extends Screen {
    private static final int INK = 0xFF453222;
    private static final int FADED_INK = 0xFF826D51;
    private static final int TEAL = 0xFF387669;
    private static final int GOLD = 0xFFBA934D;
    private final VoiceSpellClient service = VoiceSpellClient.get();
    private String selectedId;
    private List<SpellBridge.SpellEntry> catalog = List.of();
    private List<SpellBridge.SpellEntry> filtered = List.of();
    private List<RecordingPreview> displayedPreviews = List.of();
    private int firstSpell;
    private int left;
    private int top;
    private int bookWidth;
    private int bookHeight;
    private int leftPageWidth;
    private int rightX;
    private int rightWidth;
    private int visibleSpells;
    private int waveformTop;
    private int waveformRow;
    private EditBox search;
    private Button recordButton;
    private Button listenButton;
    private final Button[] deleteButtons = new Button[10];

    public GrimoireScreen(String preferredSpellId) {
        super(Component.translatable("screen.sts.grimoire"));
        this.selectedId = preferredSpellId;
    }

    @Override
    protected void init() {
        String query = search == null ? "" : search.getValue();
        bookWidth = Math.min(520, width - 16);
        bookHeight = Math.min(332, height - 16);
        left = (width - bookWidth) / 2;
        top = (height - bookHeight) / 2;
        leftPageWidth = Math.max(108, bookWidth * 36 / 100);
        rightX = left + leftPageWidth + 16;
        rightWidth = bookWidth - leftPageWidth - 30;
        visibleSpells = Math.max(1, (bookHeight - 101) / 19);
        waveformTop = top + 65;
        waveformRow = Math.max(10, (bookHeight - 127) / 10);
        catalog = loadCatalog();
        search = addRenderableWidget(new EditBox(font, left + 15, top + 37,
                leftPageWidth - 22, 18, Component.translatable("screen.sts.search")));
        search.setMaxLength(96);
        search.setHint(Component.translatable("screen.sts.search"));
        search.setResponder(this::filterSpells);
        search.setValue(query);
        filterSpells(query);
        if (catalog.stream().noneMatch(spell -> spell.id().toString().equals(selectedId))) {
            selectedId = catalog.isEmpty() ? null : catalog.getFirst().id().toString();
        }
        int selectedPosition = -1;
        for (int i = 0; i < filtered.size(); i++) {
            if (filtered.get(i).id().toString().equals(selectedId)) {
                selectedPosition = i;
                break;
            }
        }
        if (selectedPosition >= 0) {
            firstSpell = Math.max(0, Math.min(selectedPosition, filtered.size() - visibleSpells));
        }
        int bottomY = top + bookHeight - 30;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> scrollSpells(-visibleSpells))
                .bounds(left + 15, bottomY, 22, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> scrollSpells(visibleSpells))
                .bounds(left + leftPageWidth - 29, bottomY, 22, 20).build());
        recordButton = addRenderableWidget(Button.builder(Component.translatable("screen.sts.record"), b -> {
            if (service.recordingSpell() != null) {
                service.cancelRecording();
            } else if (selectedId != null) {
                service.startRecording(selectedId);
            }
        }).bounds(rightX, bottomY, Math.max(70, rightWidth * 48 / 100), 20).build());
        listenButton = addRenderableWidget(Button.builder(Component.empty(), b ->
                service.setEnabled(!service.enabled()))
                .bounds(rightX + rightWidth * 51 / 100, bottomY, rightWidth * 49 / 100, 20).build());
        for (int i = 0; i < 10; i++) {
            final int index = i;
            deleteButtons[i] = addRenderableWidget(Button.builder(Component.literal("x"), b -> {
                if (selectedId != null) {
                    service.deleteRecording(selectedId, index);
                }
            }).bounds(rightX + rightWidth - 17, waveformTop + i * waveformRow, 17,
                    Math.min(18, waveformRow - 1)).build());
        }
        updateWidgets();
    }

    private void filterSpells(String query) {
        String needle = query.toLowerCase(Locale.ROOT).trim();
        filtered = catalog.stream().filter(spell -> needle.isEmpty()
                || spell.name().getString().toLowerCase(Locale.ROOT).contains(needle)
                || spell.id().toString().contains(needle)).toList();
        firstSpell = 0;
    }

    private void scrollSpells(int amount) {
        firstSpell = Math.max(0, Math.min(Math.max(0, filtered.size() - visibleSpells), firstSpell + amount));
    }

    private SpellBridge.SpellEntry selectedSpell() {
        return catalog.stream().filter(spell -> spell.id().toString().equals(selectedId)).findFirst().orElse(null);
    }

    @Override
    public void tick() {
        updateWidgets();
    }

    private void updateWidgets() {
        String recording = service.recordingSpell();
        recordButton.setMessage(Component.translatable(recording == null ? "screen.sts.record" : "screen.sts.cancel"));
        recordButton.active = recording != null || selectedId != null && takeCount(selectedId) < 10;
        listenButton.setMessage(Component.translatable(service.enabled() ? "screen.sts.listening_on" : "screen.sts.listening_off"));
        for (int i = 0; i < deleteButtons.length; i++) {
            deleteButtons[i].visible = selectedId != null;
            deleteButtons[i].active = selectedId != null && recording == null && i < takeCount(selectedId);
        }
    }

    private void refreshWaveforms() {
        // The service loads only this page on its worker and returns bounded previews.
        // Fetch the latest snapshot each frame: asynchronous disk reads can complete
        // without changing the already-known recording count.
        displayedPreviews = selectedId == null ? List.of() : waveforms(selectedId);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xB8141021);
        drawBook(graphics);
        graphics.drawCenteredString(font, title, left + leftPageWidth / 2 + 5, top + 17, GOLD);
        graphics.drawString(font, Component.translatable("screen.sts.owned"), left + 16, top + 61, FADED_INK, false);
        for (int row = 0; row < visibleSpells && firstSpell + row < filtered.size(); row++) {
            SpellBridge.SpellEntry spell = filtered.get(firstSpell + row);
            int y = top + 76 + row * 19;
            boolean selected = spell.id().toString().equals(selectedId);
            boolean hovered = mouseX >= left + 12 && mouseX < left + leftPageWidth
                    && mouseY >= y - 2 && mouseY < y + 16;
            if (selected || hovered) {
                graphics.fill(left + 12, y - 2, left + leftPageWidth + 1, y + 16,
                        selected ? 0xFFE1CAA0 : 0xFFEBD9B7);
            }
            graphics.fill(left + 15, y + 3, left + 18, y + 7, spell.castable() ? TEAL : FADED_INK);
            graphics.drawString(font, font.plainSubstrByWidth(spell.name().getString(), leftPageWidth - 34),
                    left + 23, y + 1, INK, false);
            if (hovered) {
                graphics.renderTooltip(font, Component.literal(spell.id() + " · level " + spell.level()
                        + (spell.castable() ? " · equipped" : " · equip to cast")), mouseX, mouseY);
            }
        }
        if (filtered.isEmpty()) {
            graphics.drawWordWrap(font, Component.translatable(catalog.isEmpty()
                    ? "screen.sts.no_spells" : "screen.sts.no_matches"), left + 16, top + 81,
                    leftPageWidth - 26, FADED_INK);
        }
        graphics.drawCenteredString(font, Component.literal(filtered.isEmpty() ? "0 / 0"
                        : (firstSpell + 1) + "–" + Math.min(filtered.size(), firstSpell + visibleSpells) + " / " + filtered.size()),
                left + leftPageWidth / 2 + 4, top + bookHeight - 24, INK);
        drawSpellPage(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The translucent backdrop and parchment are drawn together in render().
    }

    private void drawBook(GuiGraphics graphics) {
        graphics.fill(left - 3, top + 4, left + bookWidth + 4, top + bookHeight + 5, 0xFF09090F);
        graphics.fill(left, top, left + bookWidth, top + bookHeight, 0xFF48344B);
        graphics.fill(left + 5, top + 5, left + bookWidth - 5, top + bookHeight - 5, GOLD);
        graphics.fill(left + 7, top + 7, left + bookWidth - 7, top + bookHeight - 7, 0xFFF4E5C4);
        graphics.fill(left + leftPageWidth + 5, top + 7, left + leftPageWidth + 8, top + bookHeight - 7, 0xFFAD9370);
        graphics.fill(left + leftPageWidth + 8, top + 7, left + leftPageWidth + 11, top + bookHeight - 7, 0xFFD8C198);
        graphics.fill(left + 13, top + 30, left + leftPageWidth - 4, top + 31, GOLD);
        graphics.fill(rightX, top + 30, rightX + rightWidth, top + 31, GOLD);
    }

    private void drawSpellPage(GuiGraphics graphics) {
        SpellBridge.SpellEntry spell = selectedSpell();
        if (spell == null) {
            graphics.drawWordWrap(font, Component.translatable("screen.sts.choose_spell"), rightX,
                    top + 43, rightWidth, INK);
            return;
        }
        graphics.drawString(font, font.plainSubstrByWidth(spell.name().getString(), rightWidth), rightX, top + 17, INK, false);
        graphics.drawString(font, Component.translatable("screen.sts.takes", takeCount(selectedId)),
                rightX, top + 38, INK, false);
        graphics.drawString(font, Component.translatable(spell.castable() ? "screen.sts.equipped" : "screen.sts.train_only", spell.level()),
                rightX, top + 50, FADED_INK, false);
        refreshWaveforms();
        for (int take = 0; take < 10; take++) {
            int y = waveformTop + take * waveformRow;
            int waveLeft = rightX + 17;
            int waveRight = rightX + rightWidth - 53;
            int center = y + (waveformRow - 2) / 2;
            graphics.drawString(font, String.format(Locale.ROOT, "%02d", take + 1), rightX, center - 4, FADED_INK, false);
            graphics.fill(waveLeft, y, waveRight, y + waveformRow - 2, 0xFFE5D5B3);
            graphics.fill(waveLeft, center, waveRight, center + 1, 0xFFBCAA86);
            if (take < displayedPreviews.size()) {
                RecordingPreview preview = displayedPreviews.get(take);
                int binCount = preview.binCount();
                int halfHeight = Math.max(2, (waveformRow - 4) / 2);
                for (int x = waveLeft; x < waveRight && binCount > 0; x++) {
                    int bin = Math.min(binCount - 1, (x - waveLeft) * binCount / Math.max(1, waveRight - waveLeft));
                    int amplitude = Math.max(1, Math.round(preview.peak(bin) * halfHeight));
                    graphics.fill(x, center - amplitude, x + 1, center + amplitude + 1, TEAL);
                }
                graphics.drawString(font, String.format(Locale.ROOT, "%.1fs", preview.sampleCount() / 16000d),
                        rightX + rightWidth - 49, center - 4, INK, false);
            }
        }
        int statusY = top + bookHeight - 57;
        graphics.drawString(font, font.plainSubstrByWidth(service.status(), rightWidth), rightX, statusY, TEAL, false);
        graphics.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.sts.record_hint").getString(), rightWidth),
                rightX, statusY + 12, FADED_INK, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= left + 12 && mouseX < left + leftPageWidth + 1) {
            int row = (int) (mouseY - top - 74) / 19;
            if (mouseY >= top + 74 && row >= 0 && row < visibleSpells && firstSpell + row < filtered.size()) {
                if (service.recordingSpell() != null) {
                    service.cancelRecording();
                }
                selectedId = filtered.get(firstSpell + row).id().toString();
                displayedPreviews = List.of();
                updateWidgets();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < left + leftPageWidth + 8) {
            scrollSpells(scrollY > 0 ? -3 : 3);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!search.isFocused() && StsClient.OPEN_GRIMOIRE.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode))) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        displayedPreviews = List.of();
        if (service.recordingSpell() != null) {
            service.cancelRecording();
        }
    }

    /** Data hooks let the development-only visual probe exercise a populated page. */
    protected List<SpellBridge.SpellEntry> loadCatalog() {
        return SpellBridge.availableSpells(minecraft.player);
    }

    protected int takeCount(String spellId) {
        return service.count(spellId);
    }

    protected List<RecordingPreview> waveforms(String spellId) {
        return service.waveforms(spellId);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
