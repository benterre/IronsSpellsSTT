package dev.sts.network;

import dev.sts.config.CastOptions;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ClientSpellStateTest {
    @Test void maximumSupportedSnapshotRoundTripsBelowClientboundBudget() {
        var levels = new java.util.HashMap<ResourceLocation,Integer>();
        for (int i=0;i<SyncSpellStatePayload.MAX_SPELLS;i++) levels.put(ResourceLocation.parse("addon:spell_"+i),Integer.MAX_VALUE);
        var payload = new SyncSpellStatePayload(UUID.randomUUID(),CastOptions.DEFAULT,levels);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
        try {
            SyncSpellStatePayload.STREAM_CODEC.encode(buffer,payload);
            assertTrue(buffer.readableBytes()<SyncSpellStatePayload.MAX_WIRE_BYTES);
            assertEquals(payload,SyncSpellStatePayload.STREAM_CODEC.decode(buffer)); assertEquals(0,buffer.readableBytes());
        } finally { buffer.release(); }
    }
    @Test void invalidOutboundSnapshotsAreRejectedBeforeNetty() {
        UUID owner = UUID.randomUUID();
        var id = ResourceLocation.parse("addon:spell");
        assertThrows(IllegalArgumentException.class, () -> new SyncSpellStatePayload(owner,CastOptions.DEFAULT,Map.of(id,0)));
        assertThrows(IllegalArgumentException.class, () -> new SyncSpellStatePayload(owner,CastOptions.DEFAULT,
                Map.of(ResourceLocation.parse("addon:"+"x".repeat(256)),1)));
        var tooMany = new java.util.HashMap<ResourceLocation,Integer>();
        for (int i = 0; i <= SyncSpellStatePayload.MAX_SPELLS; i++) tooMany.put(ResourceLocation.parse("addon:spell_"+i),1);
        assertThrows(IllegalArgumentException.class, () -> new SyncSpellStatePayload(owner,CastOptions.DEFAULT,tooMany));
        var oversized = new java.util.HashMap<ResourceLocation,Integer>();
        for (int i = 0; i < 5000; i++) oversized.put(ResourceLocation.parse("addon:"+"x".repeat(235)+i),Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> new SyncSpellStatePayload(owner,CastOptions.DEFAULT,oversized));
    }
    @Test void payloadCodecPreservesOwnerPolicyAndAddonLevels() {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            var payload = new SyncSpellStatePayload(UUID.randomUUID(),new CastOptions(false,true,true),
                    Map.of(ResourceLocation.fromNamespaceAndPath("custom_addon","invented_incantation"),7));
            SyncSpellStatePayload.STREAM_CODEC.encode(buffer,payload);
            assertEquals(payload,SyncSpellStatePayload.STREAM_CODEC.decode(buffer));
            assertEquals(0,buffer.readableBytes());
        } finally {buffer.release();}
    }
    @Test void serverSnapshotsAreImmutableAndCannotLeakAcrossPlayerSessions() {
        UUID owner=UUID.randomUUID();
        ResourceLocation id=ResourceLocation.fromNamespaceAndPath("addon", "custom_spell");
        var source=new java.util.HashMap<ResourceLocation,Integer>(); source.put(id,5);
        ClientSpellState.apply(new SyncSpellStatePayload(owner,new CastOptions(true,true,true),source));
        source.clear();
        assertEquals(5,ClientSpellState.get(owner).levels().get(id));
        assertTrue(ClientSpellState.get(owner).options().castWithoutWand());
        assertEquals(CastOptions.STRICT,ClientSpellState.get(UUID.randomUUID()).options());
        assertThrows(UnsupportedOperationException.class,()->ClientSpellState.get(owner).levels().clear());
        ClientSpellState.clear();
        assertTrue(ClientSpellState.get(owner).levels().isEmpty());
        assertEquals(CastOptions.STRICT,ClientSpellState.get(owner).options());
    }
}
