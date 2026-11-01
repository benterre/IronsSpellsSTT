package dev.sts.network;

import dev.sts.broadcast.VoiceClip;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One bounded upload only for an eligible recognized incantation, not ongoing voice traffic. */
public record RequestBroadcastCastPayload(ResourceLocation spellId, VoiceClip clip) implements CustomPacketPayload {
    public static final Type<RequestBroadcastCastPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("sts", "broadcast_cast"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestBroadcastCastPayload> STREAM_CODEC = CustomPacketPayload.codec(RequestBroadcastCastPayload::write, RequestBroadcastCastPayload::read);
    public RequestBroadcastCastPayload {
        if (spellId == null || clip == null || spellId.toString().length() > 256) throw new IllegalArgumentException("Invalid broadcast request");
    }
    private void write(FriendlyByteBuf buffer) { buffer.writeUtf(spellId.toString(), 256); buffer.writeVarInt(clip.samples()); buffer.writeByteArray(clip.encoded()); }
    private static RequestBroadcastCastPayload read(FriendlyByteBuf buffer) {
        ResourceLocation id = ResourceLocation.parse(buffer.readUtf(256)); int samples = buffer.readVarInt();
        if (samples < 1 || samples > VoiceClip.MAX_SAMPLES) throw new IllegalArgumentException("Invalid clip sample count");
        return new RequestBroadcastCastPayload(id, new VoiceClip(samples, buffer.readByteArray(VoiceClip.MAX_BYTES)));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
