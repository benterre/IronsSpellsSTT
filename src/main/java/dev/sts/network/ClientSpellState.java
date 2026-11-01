package dev.sts.network;

import dev.sts.config.CastOptions;
import net.minecraft.resources.ResourceLocation;
import java.util.Map;
import java.util.UUID;

/** No Minecraft client classes: safe common-side storage of the server's session snapshot. */
public final class ClientSpellState {
    public record Snapshot(UUID owner, CastOptions options, Map<ResourceLocation, Integer> levels) {
        public Snapshot { levels = Map.copyOf(levels); }
    }
    private static volatile Snapshot snapshot = new Snapshot(null, CastOptions.STRICT, Map.of());
    private ClientSpellState() {}
    public static Snapshot get(UUID owner) {
        Snapshot current = snapshot;
        return owner.equals(current.owner()) ? current : new Snapshot(null, CastOptions.STRICT, Map.of());
    }
    public static void apply(SyncSpellStatePayload payload) {
        snapshot = new Snapshot(payload.owner(), payload.options(), payload.levels());
    }
    public static void clear() { snapshot = new Snapshot(null, CastOptions.STRICT, Map.of()); }
}
