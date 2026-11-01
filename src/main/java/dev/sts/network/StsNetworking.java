package dev.sts.network;

import dev.sts.spell.SpellBridge;
import dev.sts.broadcast.StrongSpellBroadcasts;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Common-side networking: safe to load in a dedicated server without client classes. */
public final class StsNetworking {
    private static final String LAST_REQUEST_TICK = "sts_last_voice_request";
    private static final int MIN_REQUEST_INTERVAL_TICKS = 4;

    private StsNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("3");
        registrar.playToServer(RequestCastPayload.TYPE, RequestCastPayload.STREAM_CODEC, StsNetworking::handleCast);
        registrar.playToClient(SyncSpellStatePayload.TYPE, SyncSpellStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientSpellState.apply(payload)));
        registrar.playToClient(SyncBroadcastPolicyPayload.TYPE, SyncBroadcastPolicyPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientBroadcastPolicy.apply(payload)));
        registrar.playToServer(RequestBroadcastCastPayload.TYPE, RequestBroadcastCastPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player && allowRequest(player))
                        StrongSpellBroadcasts.request(player, payload.spellId(), payload.clip());
                }));
    }

    private static void handleCast(RequestCastPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!allowRequest(player)) return;
            SpellBridge.requestCast(player, payload.spellId());
        });
    }
    private static boolean allowRequest(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        var data = player.getPersistentData();
        if (data.contains(LAST_REQUEST_TICK)) {
            long last = data.getLong(LAST_REQUEST_TICK);
            if (now >= last && now - last < MIN_REQUEST_INTERVAL_TICKS) return false;
        }
        data.putLong(LAST_REQUEST_TICK, now);
        return true;
    }
}
