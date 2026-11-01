package dev.sts.network;

import dev.sts.broadcast.AdpcmCodec;
import dev.sts.broadcast.VoiceClip;
import dev.sts.config.BroadcastOptions;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BroadcastPayloadTest {
    @Test void invalidOutboundBroadcastPoliciesAreRejectedBeforeNetty() {
        var owner = UUID.randomUUID();
        var entries = new java.util.HashSet<ResourceLocation>();
        for (int i = 0; i < 257; i++) entries.add(ResourceLocation.parse("addon:spell_"+i));
        assertThrows(IllegalArgumentException.class, () -> new SyncBroadcastPolicyPayload(owner,new BroadcastOptions(true,true,entries)));
        assertThrows(IllegalArgumentException.class, () -> new SyncBroadcastPolicyPayload(owner,
                new BroadcastOptions(true,true,Set.of(ResourceLocation.parse("addon:"+"x".repeat(256))))));
    }
    private static final ResourceLocation SPELL = ResourceLocation.parse("addon:invented_spell");
    private static RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY); }
    @Test void maximumClipFitsServerboundPayloadAndRoundTrips() {
        var data = buffer();
        try {
            var clip = AdpcmCodec.encode(new short[VoiceClip.MAX_SAMPLES]);
            var maximumId = ResourceLocation.parse("addon:"+"x".repeat(250));
            RequestBroadcastCastPayload.STREAM_CODEC.encode(data, new RequestBroadcastCastPayload(maximumId, clip));
            assertTrue(data.readableBytes() < 32_767);
            var decoded = RequestBroadcastCastPayload.STREAM_CODEC.decode(data);
            assertEquals(maximumId, decoded.spellId());
            assertEquals(clip.samples(), decoded.clip().samples());
            assertArrayEquals(clip.encoded(), decoded.clip().encoded());
            assertEquals(0, data.readableBytes());
        } finally { data.release(); }
    }
    @Test void malformedClipCannotAllocateUnboundedAudio() {
        var data = buffer();
        try {
            data.writeUtf(SPELL.toString()); data.writeVarInt(VoiceClip.MAX_SAMPLES + 1);
            assertThrows(IllegalArgumentException.class, () -> RequestBroadcastCastPayload.STREAM_CODEC.decode(data));
            data.clear(); data.writeUtf(SPELL.toString()); data.writeVarInt(100); data.writeVarInt(VoiceClip.MAX_BYTES + 1);
            assertThrows(io.netty.handler.codec.DecoderException.class, () -> RequestBroadcastCastPayload.STREAM_CODEC.decode(data));
        } finally { data.release(); }
    }
    @Test void policyRoundTripsAndClearsAcrossSessions() {
        var data = buffer(); UUID owner = UUID.randomUUID();
        var policy = new BroadcastOptions(true, true, Set.of(SPELL));
        try {
            var source = new SyncBroadcastPolicyPayload(owner, policy);
            SyncBroadcastPolicyPayload.STREAM_CODEC.encode(data, source);
            var decoded = SyncBroadcastPolicyPayload.STREAM_CODEC.decode(data);
            assertEquals(source, decoded);
            ClientBroadcastPolicy.apply(decoded);
            assertEquals(policy, ClientBroadcastPolicy.get(owner));
            assertEquals(BroadcastOptions.DISABLED, ClientBroadcastPolicy.get(UUID.randomUUID()));
            assertFalse(policy.includes(SPELL, 4, 5)); assertTrue(policy.includes(SPELL, 5, 5));
            assertFalse(policy.includes(ResourceLocation.parse("addon:other"), 5, 5));
            assertTrue(new BroadcastOptions(true, false, Set.of(SPELL)).includes(SPELL, 1, 5));
            ClientBroadcastPolicy.clear();
            assertEquals(BroadcastOptions.DISABLED, ClientBroadcastPolicy.get(owner));
        } finally { data.release(); ClientBroadcastPolicy.clear(); }
    }
}
