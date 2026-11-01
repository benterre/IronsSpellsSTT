package dev.sts.spell;

import io.redspace.ironsspellbooks.api.item.ISpellbook;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.magic.SpellSelectionManager;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.item.Scroll;
import dev.sts.config.CastOptions;
import dev.sts.config.StsOptions;
import dev.sts.config.BroadcastOptions;
import dev.sts.network.ClientBroadcastPolicy;
import dev.sts.network.ClientSpellState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The only STS boundary to Iron's spell registry and authoritative casting API. */
public final class SpellBridge {
    private SpellBridge() {
    }

    /**
     * Owned, enabled and learned spells available for enrollment. Inventory-only
     * spells remain visible after their items are removed. Casting eligibility
     * comes from the synchronized server policy and current equipment.
     * Addon spell IDs are never enumerated here.
     */
    public static List<SpellEntry> availableSpells(Player player) {
        if (player == null) {
            return List.of();
        }
        var castable = new LinkedHashSet<>(castableSpellIds(player));
        Map<ResourceLocation, SpellEntry> entries = new LinkedHashMap<>();
        for (var option : new SpellSelectionManager(player).getAllSpells()) {
            add(entries, player, option.spellData, castable.contains(option.spellData.getSpell().getSpellResource()));
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            addContainer(entries, player, player.getInventory().getItem(slot), false);
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            addContainer(entries, player, held, false);
        }
        for (var unlocked : unlockedLevels(player).entrySet()) {
            AbstractSpell spell = SpellRegistry.REGISTRY.get(unlocked.getKey());
            if (spell != null && spell.isEnabled() && spell.isLearned(player))
                add(entries, player, new SpellData(spell, unlocked.getValue()), castable.contains(unlocked.getKey()));
        }
        return entries.values().stream()
                .sorted(Comparator.comparing(entry -> entry.name().getString()))
                .toList();
    }

    public static List<ResourceLocation> castableSpellIds(Player player) {
        return castableSpellIds(player, options(player));
    }

    public static List<ResourceLocation> castableSpellIds(Player player, CastOptions options) {
        if (player == null || !options.permitsEquipment(hasEquippedSpellbook(player), hasCastingImplement(player))) {
            return List.of();
        }
        var ids = new LinkedHashSet<ResourceLocation>();
        if (options.allowsUnprepared()) {
            for (var entry : unlockedLevels(player).entrySet()) {
                var spell = SpellRegistry.REGISTRY.get(entry.getKey());
                if (spell != null && spell.isEnabled() && spell.isLearned(player)) ids.add(entry.getKey());
            }
        }
        if (!hasEquippedSpellbook(player)) return List.copyOf(ids);
        for (var option : new SpellSelectionManager(player).getAllSpells()) {
            if (isEligible(player, option.spellData)) {
                ids.add(option.spellData.getSpell().getSpellResource());
            }
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof Scroll && ISpellContainer.isSpellContainer(held)) {
                SpellData data = ISpellContainer.get(held).getSpellAtIndex(0);
                if (isEligible(player, data)) {
                    ids.add(data.getSpell().getSpellResource());
                }
            }
        }
        return List.copyOf(ids);
    }

    /** Only this small native set needs a persistent full-feature audio cache. */
    public static List<ResourceLocation> preparedSpellIds(Player player) {
        if (player == null || !hasEquippedSpellbook(player)) return List.of();
        return new SpellSelectionManager(player).getAllSpells().stream().map(option -> option.spellData)
                .filter(data -> isEligible(player, data)).map(data -> data.getSpell().getSpellResource()).distinct().toList();
    }

    private static CastOptions options(Player player) {
        return player != null && player.level().isClientSide ? ClientSpellState.get(player.getUUID()).options() : StsOptions.get();
    }

    private static Map<ResourceLocation, Integer> unlockedLevels(Player player) {
        return player instanceof ServerPlayer server ? SpellKnowledge.levels(server)
                : ClientSpellState.get(player.getUUID()).levels();
    }

    /** Predict exactly the server's chosen base level; the completion event confirms it again. */
    public static int voiceCastLevel(Player player, ResourceLocation id) {
        AbstractSpell spell = SpellRegistry.REGISTRY.get(id);
        if (player == null || spell == null || !spell.isEnabled() || !spell.isLearned(player)) return 0;
        CastOptions policy = options(player);
        boolean book = hasEquippedSpellbook(player);
        if (!policy.permitsEquipment(book, hasCastingImplement(player))) return 0;
        ItemStack equipped = book ? Utils.getPlayerSpellbookStack(player) : ItemStack.EMPTY;
        if (ISpellContainer.isSpellContainer(equipped))
            for (var slot : ISpellContainer.get(equipped).getActiveSpells())
                if (slot.spellData().getSpell().getSpellResource().equals(id)) return spell.getLevelFor(slot.spellData().getLevel(), player);
        int known = player instanceof ServerPlayer server ? SpellKnowledge.level(server, id)
                : ClientSpellState.get(player.getUUID()).levels().getOrDefault(id, 0);
        if (policy.allowsUnprepared() && known > 0) return policy.unpreparedLevel(known, spell.getMaxLevel());
        if (book) {
            for (var option : new SpellSelectionManager(player).getAllSpells())
                if (isEligible(player, option.spellData) && option.spellData.getSpell().getSpellResource().equals(id)) return spell.getLevelFor(option.spellData.getLevel(), player);
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = player.getItemInHand(hand);
                if (stack.getItem() instanceof Scroll && ISpellContainer.isSpellContainer(stack)) {
                    var data = ISpellContainer.get(stack).getSpellAtIndex(0);
                    if (isEligible(player, data) && data.getSpell().getSpellResource().equals(id)) return spell.getLevelFor(data.getLevel(), player);
                }
            }
        }
        return 0;
    }

    public static java.util.Set<String> broadcastableSpellIds(Player player) {
        if (player == null) return java.util.Set.of();
        BroadcastOptions policy = player.level().isClientSide ? ClientBroadcastPolicy.get(player.getUUID()) : StsOptions.broadcast();
        if (!policy.enabled()) return java.util.Set.of();
        var eligible = new java.util.HashSet<String>();
        for (ResourceLocation id : policy.spells()) {
            var spell = SpellRegistry.REGISTRY.get(id);
            int level = voiceCastLevel(player, id);
            if (spell != null && level > 0 && policy.includes(id, level, spell.getMaxLevel())) eligible.add(id.toString());
        }
        return java.util.Set.copyOf(eligible);
    }

    /** Public Iron marker, native imbued containers, or a data-pack item tag; no addon name list. */
    public static boolean hasCastingImplement(Player player) {
        if (player == null) return false;
        var tag = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("sts", "casting_implements"));
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty() || stack.getItem() instanceof Scroll || stack.getItem() instanceof ISpellbook) continue;
            if (stack.is(tag)) return true;
            var marker = BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "casting_implement"));
            if (marker != null && stack.has(marker)) return true;
            for (Class<?> type = stack.getItem().getClass(); type != null; type = type.getSuperclass())
                if (type.getName().equals("io.redspace.ironsspellbooks.item.CastingItem")) return true;
            if (ISpellContainer.isSpellContainer(stack)) {
                var container = ISpellContainer.get(stack);
                if (container.isSpellWheel() && !container.mustEquip()) return true;
            }
        }
        return false;
    }

    /**
     * Iron's API resolves Curios spellbook slot 0, not the hands or inventory.
     * Its public marker also recognizes addon books implementing the same API.
     */
    public static boolean hasEquippedSpellbook(Player player) {
        if (player == null) {
            return false;
        }
        ItemStack book = Utils.getPlayerSpellbookStack(player);
        return book != null && !book.isEmpty() && (book.getItem() instanceof ISpellbook
                || isNativeSpellbook(book.getItem().getClass()));
    }

    private static boolean isNativeSpellbook(Class<?> type) {
        // Preserve recognition of native SpellBook subclasses through this slot.
        for (; type != null; type = type.getSuperclass())
            if (type.getName().equals("io.redspace.ironsspellbooks.item.SpellBook")) return true;
        return false;
    }

    /** Useful for opening the grimoire directly from an inventory hover. */
    public static Optional<ResourceLocation> preferredSpell(Player player, ItemStack hovered) {
        if (player == null) {
            return Optional.empty();
        }
        Optional<ResourceLocation> spell = firstSpell(player, hovered);
        if (spell.isPresent()) {
            return spell;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            spell = firstSpell(player, player.getItemInHand(hand));
            if (spell.isPresent()) {
                return spell;
            }
        }
        SpellData selected = new SpellSelectionManager(player).getSelectedSpellData();
        return isEligible(player, selected) ? Optional.of(selected.getSpell().getSpellResource()) : Optional.empty();
    }

    public static List<ResourceLocation> registeredSpellIds() {
        return SpellRegistry.getEnabledSpells().stream().map(AbstractSpell::getSpellResource).sorted().toList();
    }

    /**
     * Must run on the server thread. The client supplies only an ID, never a spell
     * level, mana cost, cast source or inventory slot. Resolve all of those from
     * current server equipment and then let Iron's native casting path validate
     * mana, cooldowns, learning, preconditions, recasts and cancellable mod events.
     */
    public static boolean requestCast(ServerPlayer player, ResourceLocation id) {
        return requestCast(player, id, StsOptions.get());
    }

    public static boolean requestCast(ServerPlayer player, ResourceLocation id, CastOptions options) {
        SpellKnowledge.observe(player);
        boolean book = hasEquippedSpellbook(player);
        if (!options.permitsEquipment(book, hasCastingImplement(player)) || !player.isAlive() || player.isSpectator() || MagicData.getPlayerMagicData(player).isCasting()) {
            return false;
        }
        AbstractSpell requested = SpellRegistry.REGISTRY.get(id);
        if (requested == null || !requested.isEnabled() || !requested.isLearned(player)) {
            return false;
        }
        boolean prepared = book && ISpellContainer.isSpellContainer(Utils.getPlayerSpellbookStack(player))
                && ISpellContainer.get(Utils.getPlayerSpellbookStack(player)).getActiveSpells().stream()
                .anyMatch(slot -> slot.spellData().getSpell().getSpellResource().equals(id));
        if (!prepared && options.allowsUnprepared() && SpellKnowledge.level(player, id) > 0) {
            return UnpreparedCasting.initiate(player, requested,
                    options.unpreparedLevel(SpellKnowledge.level(player, id), requested.getMaxLevel()));
        }
        if (!book) return false;
        for (var option : new SpellSelectionManager(player).getAllSpells()) {
            if (prepared && !"spellbook".equals(option.slot) && !"spellbook_0".equals(option.slot)) continue;
            if (option.spellData.getSpell().getSpellResource().equals(id) && isEligible(player, option.spellData)) {
                // 3.15.1/3.15.2 label Curios book slot zero as spellbook_0, but
                // their quick-cast helper incorrectly classifies it as SWORD.
                // Keep the real book's native level and mana-consuming source.
                if (prepared && "spellbook_0".equals(option.slot)) {
                    return requested.attemptInitiateCast(ItemStack.EMPTY,
                            requested.getLevelFor(option.spellData.getLevel(), player), player.level(), player,
                            CastSource.SPELLBOOK, true, "spellbook");
                }
                return Utils.serverSideInitiateQuickCast(player, option.globalIndex);
            }
        }
        // Scroll.use keeps the real held stack as the casting item so native
        // completion consumes it, including subclasses' removeScrollAfterCast.
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof Scroll scroll && ISpellContainer.isSpellContainer(stack)) {
                SpellData data = ISpellContainer.get(stack).getSpellAtIndex(0);
                if (isEligible(player, data) && data.getSpell().getSpellResource().equals(id)) {
                    scroll.use(player.level(), player, hand);
                    return MagicData.getPlayerMagicData(player).isCasting();
                }
            }
        }
        player.displayClientMessage(Component.translatableWithFallback("message.sts.not_equipped", "Equip a spellbook and make this spell available in your spell wheel or a held scroll."), true);
        return false;
    }

    private static Optional<ResourceLocation> firstSpell(Player player, ItemStack stack) {
        if (!ISpellContainer.isSpellContainer(stack)) {
            return Optional.empty();
        }
        return ISpellContainer.get(stack).getActiveSpells().stream()
                .map(slot -> slot.spellData())
                .filter(data -> isEligible(player, data))
                .map(data -> data.getSpell().getSpellResource())
                .findFirst();
    }

    private static void addContainer(Map<ResourceLocation, SpellEntry> entries, Player player, ItemStack stack, boolean castable) {
        if (ISpellContainer.isSpellContainer(stack)) {
            for (var slot : ISpellContainer.get(stack).getActiveSpells()) {
                add(entries, player, slot.spellData(), castable);
            }
        }
    }

    private static boolean isEligible(Player player, SpellData data) {
        return data != null && data != SpellData.EMPTY && data.getLevel() > 0
                && data.getSpell() != SpellRegistry.none() && data.getSpell().isEnabled()
                && data.getSpell().isLearned(player);
    }

    private static void add(Map<ResourceLocation, SpellEntry> entries, Player player, SpellData data, boolean castable) {
        if (!isEligible(player, data)) {
            return;
        }
        AbstractSpell spell = data.getSpell();
        ResourceLocation id = spell.getSpellResource();
        SpellEntry existing = entries.get(id);
        int level = existing == null ? data.getLevel() : Math.max(data.getLevel(), existing.level());
        entries.put(id, new SpellEntry(id, spell.getDisplayName(player), level,
                castable || existing != null && existing.castable(), spell.getSpellIconResource()));
    }

    public record SpellEntry(ResourceLocation id, Component name, int level, boolean castable, ResourceLocation icon) {
    }
}
