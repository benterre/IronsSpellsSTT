package dev.sts.spell;

import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.util.Utils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.HashMap;
import java.util.Map;

/** Persistent, server-observed unlocks. The client has no write path. */
public final class SpellKnowledge {
    private static final String KEY = "sts_unlocked_spell_levels";
    private SpellKnowledge() {}
    public static boolean observe(ServerPlayer player) {
        boolean changed = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            changed |= observe(player, player.getInventory().getItem(i));
        changed |= observe(player, Utils.getPlayerSpellbookStack(player));
        return changed;
    }
    public static boolean observe(ServerPlayer player, ItemStack stack) {
        if (!ISpellContainer.isSpellContainer(stack)) return false;
        CompoundTag levels = player.getPersistentData().getCompound(KEY);
        boolean changed = false;
        for (var slot : ISpellContainer.get(stack).getActiveSpells()) {
            SpellData data = slot.spellData();
            if (data == SpellData.EMPTY || data.getLevel() < 1 || data.getSpell() == SpellRegistry.none()) continue;
            String id = data.getSpell().getSpellResource().toString();
            int level = Math.min(data.getLevel(), data.getSpell().getMaxLevel());
            if (level > levels.getInt(id)) { levels.putInt(id, level); changed = true; }
        }
        if (changed) player.getPersistentData().put(KEY, levels);
        return changed;
    }
    public static Map<ResourceLocation, Integer> levels(ServerPlayer player) {
        CompoundTag stored = player.getPersistentData().getCompound(KEY);
        Map<ResourceLocation, Integer> result = new HashMap<>();
        for (String name : stored.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(name);
            if (id != null && stored.getInt(name) > 0) result.put(id, stored.getInt(name));
        }
        return Map.copyOf(result);
    }
    public static int level(ServerPlayer player, ResourceLocation id) {
        return player.getPersistentData().getCompound(KEY).getInt(id.toString());
    }
    public static void copy(ServerPlayer original, ServerPlayer replacement) {
        replacement.getPersistentData().put(KEY, original.getPersistentData().getCompound(KEY).copy());
    }
}
