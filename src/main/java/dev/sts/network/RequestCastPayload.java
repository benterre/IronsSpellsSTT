package dev.sts.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** A bounded spell ID is the entire recognition request; audio stays client-side. */
public record RequestCastPayload(ResourceLocation spellId) implements CustomPacketPayload {
    public static final Type<RequestCastPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("sts", "request_cast"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestCastPayload> STREAM_CODEC =
            CustomPacketPayload.codec(RequestCastPayload::write, RequestCastPayload::read);
    private static final int MAX_ID_LENGTH = 256;

    public RequestCastPayload {
        Objects.requireNonNull(spellId, "spellId");
        if (spellId.toString().length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("Spell ID exceeds protocol limit");
        }
    }

    private static RequestCastPayload read(FriendlyByteBuf buffer) {
        return new RequestCastPayload(ResourceLocation.parse(buffer.readUtf(MAX_ID_LENGTH)));
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(spellId.toString(), MAX_ID_LENGTH);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
