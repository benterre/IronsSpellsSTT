package dev.sts.compat;

import java.util.function.BiConsumer;
import java.util.function.Function;

/** Same bounded payload serializers on the legacy SimpleChannel transport. */
public record StreamCodec<B,T>(BiConsumer<B,T> encoder, Function<B,T> decoder) {
    public void encode(B buffer, T value) { encoder.accept(buffer,value); }
    public T decode(B buffer) { return decoder.apply(buffer); }
}
