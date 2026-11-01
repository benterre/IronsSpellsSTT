package dev.sts.compat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Local marker only; legacy payloads travel through Forge's registered, directional channel. */
public interface CustomPacketPayload {
    record Type<T extends CustomPacketPayload>(ResourceLocation id) {}
    Type<? extends CustomPacketPayload> type();
    static <T> StreamCodec<FriendlyByteBuf,T> codec(BiConsumer<T,FriendlyByteBuf> writer, Function<FriendlyByteBuf,T> reader) {
        return new StreamCodec<>((buffer,value) -> writer.accept(value,buffer),reader);
    }
}
