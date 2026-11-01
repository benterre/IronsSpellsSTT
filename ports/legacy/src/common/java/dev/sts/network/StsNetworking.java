package dev.sts.network;

import dev.sts.broadcast.StrongSpellBroadcasts;
import dev.sts.compat.CustomPacketPayload;
import dev.sts.spell.SpellBridge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Optional;

/** Bounded, directional legacy transport; the same payload constructors enforce wire limits. */
public final class StsNetworking {
    private static final String VERSION="3", LAST_REQUEST_TICK="sts_last_voice_request";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation("sts","main"),() -> VERSION,VERSION::equals,VERSION::equals);
    private StsNetworking() {}
    public static void register() {
        CHANNEL.registerMessage(0,RequestCastPayload.class,(packet,buffer) -> RequestCastPayload.STREAM_CODEC.encode(buffer,packet),
                RequestCastPayload.STREAM_CODEC::decode,(packet,context) -> {
                    var ctx=context.get(); ctx.enqueueWork(() -> { var player=ctx.getSender(); if (player != null && allowRequest(player)) SpellBridge.requestCast(player,packet.spellId()); }); ctx.setPacketHandled(true);
                },Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1,SyncSpellStatePayload.class,(packet,buffer) -> SyncSpellStatePayload.STREAM_CODEC.encode(buffer,packet),
                SyncSpellStatePayload.STREAM_CODEC::decode,(packet,context) -> {
                    var ctx=context.get(); ctx.enqueueWork(() -> ClientSpellState.apply(packet)); ctx.setPacketHandled(true);
                },Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2,SyncBroadcastPolicyPayload.class,(packet,buffer) -> SyncBroadcastPolicyPayload.STREAM_CODEC.encode(buffer,packet),
                SyncBroadcastPolicyPayload.STREAM_CODEC::decode,(packet,context) -> {
                    var ctx=context.get(); ctx.enqueueWork(() -> ClientBroadcastPolicy.apply(packet)); ctx.setPacketHandled(true);
                },Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3,RequestBroadcastCastPayload.class,(packet,buffer) -> RequestBroadcastCastPayload.STREAM_CODEC.encode(buffer,packet),
                RequestBroadcastCastPayload.STREAM_CODEC::decode,(packet,context) -> {
                    var ctx=context.get(); ctx.enqueueWork(() -> { var player=ctx.getSender(); if (player != null && allowRequest(player)) StrongSpellBroadcasts.request(player,packet.spellId(),packet.clip()); }); ctx.setPacketHandled(true);
                },Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
    public static void sendToServer(CustomPacketPayload packet) { CHANNEL.sendToServer(packet); }
    public static void sendToPlayer(ServerPlayer player,CustomPacketPayload packet) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),packet); }
    private static boolean allowRequest(ServerPlayer player) {
        long now=player.serverLevel().getGameTime(); var data=player.getPersistentData();
        if (data.contains(LAST_REQUEST_TICK)) { long last=data.getLong(LAST_REQUEST_TICK); if (now >= last && now-last < 4) return false; }
        data.putLong(LAST_REQUEST_TICK,now); return true;
    }
}
