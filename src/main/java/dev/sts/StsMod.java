package dev.sts;

import dev.sts.network.StsNetworking;
import dev.sts.config.StsOptions;
import dev.sts.spell.SpellProgressionEvents;
import dev.sts.spell.UnpreparedCasting;
import dev.sts.broadcast.StrongSpellBroadcasts;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(StsMod.MOD_ID)
public final class StsMod {
    public static final String MOD_ID = "sts";

    public StsMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, StsOptions.SPEC, "sts/options.toml");
        modBus.addListener(StsNetworking::register);
        NeoForge.EVENT_BUS.addListener(SpellProgressionEvents::tick);
        NeoForge.EVENT_BUS.addListener(SpellProgressionEvents::login);
        NeoForge.EVENT_BUS.addListener(SpellProgressionEvents::logout);
        NeoForge.EVENT_BUS.addListener(SpellProgressionEvents::clonePlayer);
        NeoForge.EVENT_BUS.addListener(SpellProgressionEvents::respawn);
        NeoForge.EVENT_BUS.addListener(SpellProgressionEvents::crafted);
        NeoForge.EVENT_BUS.addListener(SpellProgressionEvents::toss);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, SpellOnCastEvent.class, UnpreparedCasting::onCast);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, SpellOnCastEvent.class, StrongSpellBroadcasts::onCast);
        NeoForge.EVENT_BUS.addListener(StrongSpellBroadcasts::tick);
        NeoForge.EVENT_BUS.addListener(StrongSpellBroadcasts::stopped);
    }
}
