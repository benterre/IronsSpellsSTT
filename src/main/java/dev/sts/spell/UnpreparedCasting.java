package dev.sts.spell;

import dev.sts.config.CastOptions;
import dev.sts.config.StsOptions;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.config.ServerConfigs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;

/** Native SPELLBOOK mana/cooldown semantics, without fabricating or consuming an item. */
public final class UnpreparedCasting {
    public static final String SLOT = "sts_voice";
    private record Initiation(ServerPlayer player,String spell) {}
    private static final ThreadLocal<Initiation> INITIATING=new ThreadLocal<>();
    private UnpreparedCasting() {}
    public static boolean initiate(ServerPlayer player, AbstractSpell spell, int level) {
        if (!affordable(player, spell, level)) {
            player.displayClientMessage(Component.translatableWithFallback("message.sts.unprepared_mana",
                    "Not enough mana: unprepared spells cost 150% at the chosen level."), true);
            return false;
        }
        Initiation previous=INITIATING.get();
        INITIATING.set(new Initiation(player,spell.getSpellId()));
        try { return spell.attemptInitiateCast(ItemStack.EMPTY, level, player.level(), player, CastSource.SPELLBOOK, true, SLOT); }
        finally { if(previous==null) INITIATING.remove();else INITIATING.set(previous); }
    }
    private static boolean affordable(ServerPlayer player, AbstractSpell spell, int level) {
        var magic = MagicData.getPlayerMagicData(player);
        return magic.getPlayerRecasts().hasRecastForSpell(spell.getSpellId())
                || player.isCreative() && !ServerConfigs.CREATIVE_MANA_COST.get()
                || magic.getMana() >= CastOptions.surcharge(spell.getManaCost(level));
    }
    public static void onCast(SpellOnCastEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && (initiating(player,event.getSpellId()) || active(player)
                && event.getSpellId().equals(MagicData.getPlayerMagicData(player).getCastingSpellId())))
            event.setManaCost(CastOptions.surcharge(event.getManaCost()));
    }
    /** Instant spells can finish inside attemptInitiateCast without entering a persistent casting state. */
    public static boolean initiating(ServerPlayer player,String spell) {
        Initiation context=INITIATING.get();return context!=null&&context.player==player&&context.spell.equals(spell);
    }
    public static boolean active(ServerPlayer player) {
        var magic = MagicData.getPlayerMagicData(player);
        return magic.isCasting() && magic.getCastSource() == CastSource.SPELLBOOK && SLOT.equals(magic.getCastingEquipmentSlot());
    }
    /** Cancel before another native pulse if mana was drained during the cast. */
    public static void guardMana(ServerPlayer player) {
        if (!active(player)) return;
        var magic = MagicData.getPlayerMagicData(player);
        var spell = SpellRegistry.REGISTRY.get(ResourceLocation.parse(magic.getCastingSpellId()));
        var options = StsOptions.get();
        if (spell == null || !options.allowsUnprepared()
                || !options.permitsEquipment(SpellBridge.hasEquippedSpellbook(player), SpellBridge.hasCastingImplement(player))
                || !affordable(player, spell, magic.getCastingSpellLevel())) Utils.serverSideCancelCast(player, false);
    }
}
