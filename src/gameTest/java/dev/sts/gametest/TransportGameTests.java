package dev.sts.gametest;

import dev.sts.broadcast.*;
import dev.sts.config.*;
import dev.sts.network.*;
import dev.sts.spell.SpellProgressionEvents;
import dev.sts.voice.SpellVoiceCategory;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@GameTestHolder("sts")
@PrefixGameTestTemplate(false)
public final class TransportGameTests {
    static void roundTrip(GameTestHelper helper, ClientboundCustomPayloadPacket packet) {
        var protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(helper.getLevel().registryAccess(),net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE));
        var buffer = Unpooled.buffer();
        try {
            protocol.codec().encode(buffer, packet);
            var decoded = (ClientboundCustomPayloadPacket)protocol.codec().decode(buffer);
            helper.assertTrue(decoded.payload().type().equals(packet.payload().type())
                    && decoded.payload().getClass().equals(packet.payload().getClass()) && buffer.readableBytes() == 0,
                    "Clientbound packet did not round trip through its registered codec");
        } finally { buffer.release(); }
    }
    @GameTest(template="empty", timeoutTicks=100)
    public static void fullClientboundCodecsCoverCategoryAndStsSnapshots(GameTestHelper helper) throws Exception {
        UUID owner = UUID.randomUUID(); ResourceLocation id = ResourceLocation.parse("addon:spell");
        roundTrip(helper,new ClientboundCustomPayloadPacket(new SyncSpellStatePayload(owner,CastOptions.DEFAULT,Map.of(id,7))));
        roundTrip(helper,new ClientboundCustomPayloadPacket(new SyncBroadcastPolicyPayload(owner,new BroadcastOptions(true,true,Set.of(id)))));
        // Exercise the complete serverbound envelope at the largest valid audio/ID combination.
        var serverbound = GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(helper.getLevel().registryAccess(),net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE));
        var maxId = ResourceLocation.parse("addon:"+"x".repeat(250));
        var upload = new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(new RequestBroadcastCastPayload(maxId,AdpcmCodec.encode(new short[VoiceClip.MAX_SAMPLES])));
        var requestBytes=Unpooled.buffer();
        try {
            serverbound.codec().encode(requestBytes,upload);
            helper.assertTrue(requestBytes.readableBytes()<32767,"Voice upload exceeds the serverbound packet budget");
            var restored=(net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket)serverbound.codec().decode(requestBytes);
            helper.assertTrue(restored.payload() instanceof RequestBroadcastCastPayload data && data.spellId().equals(maxId)
                    && data.clip().samples()==VoiceClip.MAX_SAMPLES && requestBytes.readableBytes()==0,"Full audio request did not round trip");
        } finally { requestBytes.release(); }
        if (net.neoforged.fml.ModList.get().isLoaded("voicechat")) {
            var api = (de.maxhenkel.voicechat.api.VoicechatApi)Class.forName("de.maxhenkel.voicechat.plugins.impl.VoicechatServerApiImpl").getMethod("instance").invoke(null);
            var category = SpellVoiceCategory.create(api);
            Class<?> packetType = Class.forName("de.maxhenkel.voicechat.net.AddCategoryPacket");
            roundTrip(helper,new ClientboundCustomPayloadPacket((CustomPacketPayload)packetType.getConstructor(category.getClass()).newInstance(category)));
            Class<?> categoryType = category.getClass();
            Object oldCategory;
            try { oldCategory = categoryType.getConstructor(String.class,String.class,String.class,String.class,String.class,int[][].class)
                    .newInstance("sts_spells","Strong spell voices",null,"Replay",null,null); }
            catch (NoSuchMethodException legacy) { oldCategory = categoryType.getConstructor(String.class,String.class,String.class,int[][].class)
                    .newInstance("sts_spells","Strong spell voices","Replay",null); }
            boolean rejected = false;
            try { roundTrip(helper,new ClientboundCustomPayloadPacket((CustomPacketPayload)packetType.getConstructor(categoryType).newInstance(oldCategory))); }
            catch (io.netty.handler.codec.EncoderException expected) { rejected = true; }
            helper.assertTrue(rejected, "Real Minecraft codec did not reproduce original category failure");
        }
        helper.succeed();
    }
    @GameTest(template="empty", batch="sts_real_broadcast", timeoutTicks=300)
    public static void blackHoleCompletesWithRealSvcAndEncodedClientboundPackets(GameTestHelper helper) {
        if (!net.neoforged.fml.ModList.get().isLoaded("voicechat")) { helper.succeed(); return; }
        boolean enabled=StsOptions.BROADCAST.get(), maximum=StsOptions.BROADCAST_MAX.get(); var ids=StsOptions.BROADCAST_SPELLS.get();
        AtomicInteger categories = new AtomicInteger(); AtomicReference<Throwable> failed = new AtomicReference<>();
        var validator = new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                try {
                    if (message instanceof ClientboundCustomPayloadPacket packet) {
                        roundTrip(helper,packet);
                        if (packet.payload().type().id().equals(ResourceLocation.parse("voicechat:add_category"))) categories.incrementAndGet();
                    }
                    context.write(message,promise);
                } catch (RuntimeException | AssertionError error) { failed.set(error); promise.setFailure(error); }
            }
        };
        var player=SpellBridgeGameTests.mockSurvivalPlayer(helper,validator);
        Runnable cleanup=() -> { StrongSpellBroadcasts.discard(player.getUUID()); StsOptions.BROADCAST.set(enabled); StsOptions.BROADCAST_MAX.set(maximum); StsOptions.BROADCAST_SPELLS.set(ids); SpellBridgeGameTests.removePlayer(player); };
        try {
            // Synthetic clients have no SVC login request. Mark only this fixture's UUID compatible
            // so native SVC sends its category through the same path used by a real client.
            Class<?> svc = Class.forName("de.maxhenkel.voicechat.Voicechat");
            Object events = svc.getField("SERVER").get(null);
            var versionsField = events.getClass().getDeclaredField("clientCompatibilities"); versionsField.setAccessible(true);
            @SuppressWarnings("unchecked") var versions = (Map<UUID,Integer>)versionsField.get(events);
            versions.put(player.getUUID(),svc.getField("COMPATIBILITY_VERSION").getInt(null));
            var spell=SpellRegistry.REGISTRY.get(ResourceLocation.parse("irons_spellbooks:black_hole"));
            helper.assertTrue(spell != null && StrongSpellBroadcasts.available(), "Native SVC/Black Hole is unavailable");
            StsOptions.BROADCAST.set(true); StsOptions.BROADCAST_MAX.set(true); StsOptions.BROADCAST_SPELLS.set(java.util.List.of(spell.getSpellId()));
            player.getAttribute(AttributeRegistry.MAX_MANA).setBaseValue(3000); player.getAttribute(AttributeRegistry.MANA_REGEN).setBaseValue(0); player.setInvulnerable(true);
            MagicData.getPlayerMagicData(player).setMana(3000); MagicData.getPlayerMagicData(player).getSyncedData().learnSpell(spell);
            ItemStack book=new ItemStack(ItemRegistry.WIMPY_SPELL_BOOK.get()); var spells=ISpellContainer.create(1,true,true).mutableCopy();
            spells.addSpellAtIndex(spell,spell.getMaxLevel(),0,true); SpellBridgeGameTests.setContainer(book,spells.toImmutable()); Utils.setPlayerSpellbookStack(player,book);
            short[] voice=new short[4000]; for (int i=0;i<voice.length;i++) voice[i]=(short)(7000*Math.sin(i*2*Math.PI*220/16000));
            helper.assertTrue(StrongSpellBroadcasts.request(player,spell.getSpellResource(),AdpcmCodec.encode(voice)), "Native Black Hole failed to initiate");
            helper.runAfterDelay(160,() -> {
                try {
                    helper.assertTrue(failed.get()==null, "Native clientbound packet failed: "+failed.get());
                    com.mojang.logging.LogUtils.getLogger().info("STS_BLACK_HOLE_CHECK casting={} categoryPackets={} pending={} playing={} connected={}", MagicData.getPlayerMagicData(player).isCasting(), categories.get(), StrongSpellBroadcasts.pendingCount(), StrongSpellBroadcasts.playbackCount(), player.connection.getConnection().isConnected());
                    helper.assertTrue(player.connection.getConnection().isConnected(), "Black Hole disconnected the synthetic client");
                    helper.assertTrue(!MagicData.getPlayerMagicData(player).isCasting() && categories.get()==1, "Black Hole did not complete and register its native category exactly once");
                    helper.assertTrue(StrongSpellBroadcasts.pendingCount()==0, "Black Hole replay remained pending");
                    com.mojang.logging.LogUtils.getLogger().info("STS_BLACK_HOLE_WIRE_PASS native completed cast; category encoded/decoded; client remained connected");
                    helper.succeed();
                } finally { cleanup.run(); }
            });
        } catch (ReflectiveOperationException error) { cleanup.run(); throw new IllegalStateException("SVC test handshake fixture changed", error); }
        catch (RuntimeException | AssertionError error) { cleanup.run(); throw error; }
    }
    @GameTest(template="empty", timeoutTicks=100)
    public static void invalidProgressionMetadataIsNotEnqueuedToNetty(GameTestHelper helper) {
        var player=SpellBridgeGameTests.mockSurvivalPlayer(helper);
        try {
            String id="addon:"+"x".repeat(256); var saved=new net.minecraft.nbt.CompoundTag(); saved.putInt(id,5);
            player.getPersistentData().put("sts_unlocked_spell_levels",saved);
            SpellProgressionEvents.sync(player);
            helper.assertTrue(player.connection.getConnection().isConnected(), "Invalid local metadata disconnected client");
            helper.assertTrue(player.getPersistentData().getCompound("sts_unlocked_spell_levels").getInt(id)==5, "Invalid wire metadata erased player progression");
        } finally { SpellBridgeGameTests.removePlayer(player); }
        helper.succeed();
    }
}
