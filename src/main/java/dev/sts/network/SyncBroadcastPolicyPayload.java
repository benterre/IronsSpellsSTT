package dev.sts.network;

import dev.sts.config.BroadcastOptions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.HashSet;
import java.util.UUID;

public record SyncBroadcastPolicyPayload(UUID owner, BroadcastOptions options) implements CustomPacketPayload {
    public SyncBroadcastPolicyPayload {
        if (owner == null || options == null || options.spells().size() > 256
                || options.spells().stream().anyMatch(id -> id.toString().length() > 256))
            throw new IllegalArgumentException("Broadcast policy exceeds protocol bounds");
    }
    public static final Type<SyncBroadcastPolicyPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("sts", "broadcast_policy"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncBroadcastPolicyPayload> STREAM_CODEC = CustomPacketPayload.codec(SyncBroadcastPolicyPayload::write, SyncBroadcastPolicyPayload::read);
    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(owner); buffer.writeBoolean(options.enabled()); buffer.writeBoolean(options.maxLevelOnly());
        buffer.writeVarInt(options.spells().size());
        options.spells().forEach(id -> buffer.writeUtf(id.toString(), 256));
    }
    private static SyncBroadcastPolicyPayload read(FriendlyByteBuf buffer) {
        UUID owner = buffer.readUUID(); boolean enabled = buffer.readBoolean(), maximum = buffer.readBoolean();
        int size = buffer.readVarInt();
        if (size < 0 || size > 256) throw new IllegalArgumentException("Invalid broadcast spell count");
        var spells = new HashSet<ResourceLocation>();
        for (int i = 0; i < size; i++) if (!spells.add(ResourceLocation.parse(buffer.readUtf(256)))) throw new IllegalArgumentException("Duplicate broadcast ID");
        return new SyncBroadcastPolicyPayload(owner, new BroadcastOptions(enabled, maximum, spells));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
