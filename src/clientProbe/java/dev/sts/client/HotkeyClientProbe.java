package dev.sts.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Exercises real widgets and the registered post-event listener without joining a world. */
final class HotkeyClientProbe {
    static void check(Minecraft mc) {
        Fixture screen = new Fixture(mc);
        require(!StsClient.hoveredShortcut(screen, GLFW.GLFW_KEY_G, 0).isEmpty(), "Hovered item shortcut denied");
        var pre = new ScreenEvent.KeyPressed.Pre(screen, GLFW.GLFW_KEY_G, 0, 0);
        NeoForge.EVENT_BUS.post(pre);
        require(pre.isCanceled(), "JEI-style unfocused hovered-item shortcut was not handled early");
        screen.search.setFocused(true);
        require(screen.getFocused() == null && screen.children().isEmpty(), "Private search fixture is registered as a child");
        require(ContainerTextFocus.isTyping(screen), "Private creative-style search was missed");
        var typingPre = new ScreenEvent.KeyPressed.Pre(screen, GLFW.GLFW_KEY_G, 0, 0);
        NeoForge.EVENT_BUS.post(typingPre);
        require(!typingPre.isCanceled(), "STS stole key before focused search handling");
        require(StsClient.hoveredShortcut(screen, GLFW.GLFW_KEY_G, 0).isEmpty(), "Private search lost G shortcut");
        var post = new ScreenEvent.KeyPressed.Post(screen, GLFW.GLFW_KEY_G, 0, 0);
        NeoForge.EVENT_BUS.post(post);
        require(!post.isCanceled(), "Post event stole typing key");
        require(screen.search.charTyped('g', 0) && screen.search.getValue().equals("g"), "G was not entered in search");
        screen.search.setFocused(false);
        require(StsClient.hoveredShortcut(screen, GLFW.GLFW_KEY_H, 0).isEmpty(), "Wrong key triggered shortcut");
        screen.hover(null);
        require(StsClient.hoveredShortcut(screen, GLFW.GLFW_KEY_G, 0).isEmpty(), "No hovered item triggered shortcut");
        screen.hover(screen.getMenu().getSlot(0)); screen.getMenu().getSlot(0).set(ItemStack.EMPTY);
        require(StsClient.hoveredShortcut(screen, GLFW.GLFW_KEY_G, 0).isEmpty(), "Empty slot triggered shortcut");
        screen.getMenu().getSlot(0).set(new ItemStack(Items.BOOK));
        screen.registerSearch(); screen.setFocused(screen.search); screen.search.setFocused(true);
        require(StsClient.hoveredShortcut(screen, GLFW.GLFW_KEY_G, 0).isEmpty(), "Registered search lost shortcut");
    }
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private static final class Menu extends AbstractContainerMenu {
        Menu() { super(null, 0); var items = new SimpleContainer(1); items.setItem(0, new ItemStack(Items.BOOK)); addSlot(new Slot(items, 0, 0, 0)); }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }
    private static final class Fixture extends AbstractContainerScreen<Menu> {
        private final EditBox search;
        Fixture(Minecraft mc) {
            super(new Menu(), new Inventory(null), Component.literal("Shortcut probe"));
            search = new EditBox(mc.font, 0, 0, 100, 20, Component.literal("Search")); hover(menu.getSlot(0));
        }
        void hover(Slot slot) { hoveredSlot = slot; }
        void registerSearch() { addRenderableWidget(search); }
        @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {}
    }
}
