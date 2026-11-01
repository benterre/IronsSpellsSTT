package dev.sts.network;

import dev.sts.config.CastOptions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/** Server -> client only. Clients never supply unlocks, levels, or policy. */
public record SyncSpellStatePayload(UUID owner, CastOptions options, Map<ResourceLocation, Integer> levels)
        implements CustomPacketPayload {
    public static final Type<SyncSpellStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("sts", "spell_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncSpellStatePayload> STREAM_CODEC =
            CustomPacketPayload.codec(SyncSpellStatePayload::write, SyncSpellStatePayload::read);
    public static final int MAX_SPELLS = 8192;
    public static final int MAX_WIRE_BYTES = 1_000_000;
    public SyncSpellStatePayload {
        if (owner == null || options == null || levels == null || levels.size() > MAX_SPELLS)
            throw new IllegalArgumentException("Invalid spell snapshot");
        int bytes = 19 + varIntBytes(levels.size());
        for (var entry : levels.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 1)
                throw new IllegalArgumentException("Invalid spell state entry");
            // ResourceLocation's allowed characters are ASCII in Minecraft 1.21.1.
            int length = entry.getKey().toString().length();
            if (length > 256) throw new IllegalArgumentException("Spell ID exceeds protocol limit");
            bytes += varIntBytes(length) + length + varIntBytes(entry.getValue());
            if (bytes > MAX_WIRE_BYTES) throw new IllegalArgumentException("Spell snapshot exceeds packet budget");
        }
        levels = Map.copyOf(levels);
    }
    private static int varIntBytes(int value) {
        int bytes = 1; while ((value >>>= 7) != 0) bytes++; return bytes;
    }
    private static SyncSpellStatePayload read(FriendlyByteBuf buffer) {
        UUID owner = buffer.readUUID();
        CastOptions options = new CastOptions(buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean());
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_SPELLS) throw new IllegalArgumentException("Invalid spell state size");
        Map<ResourceLocation, Integer> levels = new HashMap<>();
        for (int i = 0; i < size; i++) {
            ResourceLocation id = ResourceLocation.parse(buffer.readUtf(256));
            int level = buffer.readVarInt();
            if (level < 1 || levels.put(id, level) != null) throw new IllegalArgumentException("Invalid spell state entry");
        }
        return new SyncSpellStatePayload(owner, options, levels);
    }
    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(owner);
        buffer.writeBoolean(options.grimoirelessCastAnySpell());
        buffer.writeBoolean(options.grimoirelessCastAtMaxLevel());
        buffer.writeBoolean(options.castWithoutWand());
        buffer.writeVarInt(levels.size());
        levels.forEach((id, level) -> { buffer.writeUtf(id.toString(), 256); buffer.writeVarInt(level); });
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
