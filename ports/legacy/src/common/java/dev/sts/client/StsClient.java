package dev.sts.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sts.network.ClientSpellState;
import dev.sts.network.ClientBroadcastPolicy;
import dev.sts.spell.SpellBridge;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import org.lwjgl.glfw.GLFW;

/** Legacy client boundary; explicit side gating avoids loading client classes on a dedicated server. */
public final class StsClient {
    public static final KeyMapping OPEN_GRIMOIRE = new KeyMapping("key.sts.grimoire",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_G,"key.categories.sts");
    private StsClient() {}
    public static void init(IEventBus modBus) {
        if (Boolean.getBoolean("sts.clientProbe")) {
            try { Class.forName("dev.sts.client.GrimoireClientProbe").getConstructor().newInstance(); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot initialize STS client probe",error); }
        }
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(OPEN_GRIMOIRE));
        MinecraftForge.EVENT_BUS.addListener(StsClient::tick);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,ScreenEvent.KeyPressed.Pre.class,StsClient::inventoryShortcut);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,ScreenEvent.KeyPressed.Post.class,StsClient::inventoryShortcut);
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            ClientSpellState.clear(); ClientBroadcastPolicy.clear(); VoiceSpellClient.get().resetAudio();
        });
    }
    private static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        VoiceSpellClient.get().tick();
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_GRIMOIRE.consumeClick()) if (mc.player != null && mc.screen == null) open(ItemStack.EMPTY);
    }
    private static void inventoryShortcut(ScreenEvent.KeyPressed event) {
        ItemStack hovered = hoveredShortcut(event.getScreen(),event.getKeyCode(),event.getScanCode());
        if (hovered.isEmpty()) return;
        open(hovered); event.setCanceled(true);
    }
    static ItemStack hoveredShortcut(net.minecraft.client.gui.screens.Screen screen,int key,int scan) {
        if (!(screen instanceof AbstractContainerScreen<?> inventory)
                || !OPEN_GRIMOIRE.isActiveAndMatches(InputConstants.getKey(key,scan)) || ContainerTextFocus.isTyping(screen)) return ItemStack.EMPTY;
        var slot = inventory.getSlotUnderMouse();
        return slot != null && slot.hasItem() ? slot.getItem() : ItemStack.EMPTY;
    }
    private static void open(ItemStack hovered) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String preferred = SpellBridge.preferredSpell(mc.player,hovered).map(Object::toString).orElse(null);
        if (mc.screen instanceof AbstractContainerScreen<?>) mc.player.closeContainer();
        mc.setScreen(new GrimoireScreen(preferred));
    }
}
