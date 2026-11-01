package dev.sts.spell;

import dev.sts.config.CastOptions;
import dev.sts.config.StsOptions;
import dev.sts.config.BroadcastOptions;
import dev.sts.network.SyncSpellStatePayload;
import dev.sts.network.SyncBroadcastPolicyPayload;
import dev.sts.broadcast.StrongSpellBroadcasts;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

public final class SpellProgressionEvents {
    private record Policy(CastOptions cast, BroadcastOptions broadcast) {}
    private static final Map<UUID, Policy> LAST_POLICY = new HashMap<>();
    private SpellProgressionEvents() {}
    public static void tick(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UnpreparedCasting.guardMana(player);
        boolean changed = SpellKnowledge.observe(player);
        CastOptions options = StsOptions.get();
        Policy policy = new Policy(options, StsOptions.broadcast());
        if (changed || !policy.equals(LAST_POLICY.get(player.getUUID()))) sync(player);
    }
    public static void sync(ServerPlayer player) {
        CastOptions options = StsOptions.get();
        var broadcast = StsOptions.broadcast();
        LAST_POLICY.put(player.getUUID(), new Policy(options, broadcast));
        // Refuse invalid/oversized addon metadata before Netty's asynchronous encoder sees it.
        // Preserve player knowledge on disk and the last valid client snapshot.
        try {
            var snapshot = new SyncSpellStatePayload(player.getUUID(), options, SpellKnowledge.levels(player));
            PacketDistributor.sendToPlayer(player, snapshot);
        } catch (IllegalArgumentException invalid) {
            com.mojang.logging.LogUtils.getLogger().warn("STS skipped an invalid spell snapshot for {}: {}", player.getUUID(), invalid.getMessage());
        }
        PacketDistributor.sendToPlayer(player, new SyncBroadcastPolicyPayload(player.getUUID(), broadcast));
    }
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) { SpellKnowledge.observe(player); sync(player); }
    }
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_POLICY.remove(event.getEntity().getUUID());
        StrongSpellBroadcasts.discard(event.getEntity().getUUID());
    }
    public static void clonePlayer(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer original && event.getEntity() instanceof ServerPlayer replacement)
            SpellKnowledge.copy(original, replacement);
    }
    public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }
    public static void crafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && SpellKnowledge.observe(player, event.getCrafting())) sync(player);
    }
    public static void toss(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && SpellKnowledge.observe(player, event.getEntity().getItem())) sync(player);
    }
}
