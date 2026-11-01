package dev.sts.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sts.spell.SpellBridge;
import dev.sts.network.ClientSpellState;
import dev.sts.network.ClientBroadcastPolicy;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import org.lwjgl.glfw.GLFW;

/** Loaded by the physical client entrypoint only; never referenced by server event handlers. */
@Mod(value = "sts", dist = Dist.CLIENT)
public final class StsClient {
    public static final KeyMapping OPEN_GRIMOIRE = new KeyMapping("key.sts.grimoire",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.sts");

    public StsClient(IEventBus modBus) {
        init(modBus);
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(StsClient::registerKeys);
        NeoForge.EVENT_BUS.addListener(StsClient::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ScreenEvent.KeyPressed.Pre.class, StsClient::inventoryShortcut);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ScreenEvent.KeyPressed.Post.class, StsClient::inventoryShortcut);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            ClientSpellState.clear();
            ClientBroadcastPolicy.clear();
            VoiceSpellClient.get().resetAudio();
        });
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_GRIMOIRE);
    }

    private static void tick(ClientTickEvent.Post event) {
        VoiceSpellClient.get().tick();
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_GRIMOIRE.consumeClick()) {
            if (mc.player != null && mc.screen == null) {
                open(ItemStack.EMPTY);
            }
        }
    }

    private static void inventoryShortcut(ScreenEvent.KeyPressed event) {
        ItemStack hovered = hoveredShortcut(event.getScreen(), event.getKeyCode(), event.getScanCode());
        if (hovered.isEmpty()) return;
        open(hovered);
        ((net.neoforged.bus.api.ICancellableEvent) event).setCanceled(true);
    }

    static ItemStack hoveredShortcut(net.minecraft.client.gui.screens.Screen screen, int key, int scan) {
        if (!(screen instanceof AbstractContainerScreen<?> inventory)
                || !OPEN_GRIMOIRE.isActiveAndMatches(InputConstants.getKey(key, scan))
                || ContainerTextFocus.isTyping(screen)) return ItemStack.EMPTY;
        var slot = inventory.getSlotUnderMouse();
        return slot != null && slot.hasItem() ? slot.getItem() : ItemStack.EMPTY;
    }

    private static void open(ItemStack hovered) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        String preferred = SpellBridge.preferredSpell(mc.player, hovered)
                .map(Object::toString).orElse(null);
        if (mc.screen instanceof AbstractContainerScreen<?>) {
            mc.player.closeContainer();
        }
        mc.setScreen(new GrimoireScreen(preferred));
    }
}
