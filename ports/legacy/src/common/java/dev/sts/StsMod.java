package dev.sts;

import dev.sts.broadcast.StrongSpellBroadcasts;
import dev.sts.config.StsOptions;
import dev.sts.network.StsNetworking;
import dev.sts.spell.SpellProgressionEvents;
import dev.sts.spell.UnpreparedCasting;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.common.MinecraftForge;

@Mod(StsMod.MOD_ID)
public final class StsMod {
    public static final String MOD_ID = "sts";
    public StsMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON,StsOptions.SPEC,"sts/options.toml");
        StsNetworking.register();
        MinecraftForge.EVENT_BUS.addListener(SpellProgressionEvents::tick);
        MinecraftForge.EVENT_BUS.addListener(SpellProgressionEvents::login);
        MinecraftForge.EVENT_BUS.addListener(SpellProgressionEvents::logout);
        MinecraftForge.EVENT_BUS.addListener(SpellProgressionEvents::clonePlayer);
        MinecraftForge.EVENT_BUS.addListener(SpellProgressionEvents::respawn);
        MinecraftForge.EVENT_BUS.addListener(SpellProgressionEvents::crafted);
        MinecraftForge.EVENT_BUS.addListener(SpellProgressionEvents::toss);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,SpellOnCastEvent.class,UnpreparedCasting::onCast);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,SpellOnCastEvent.class,StrongSpellBroadcasts::onCast);
        MinecraftForge.EVENT_BUS.addListener(StrongSpellBroadcasts::tick);
        MinecraftForge.EVENT_BUS.addListener(StrongSpellBroadcasts::stopped);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            try { Class.forName("dev.sts.client.StsClient").getMethod("init",IEventBus.class).invoke(null,modBus); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot initialize STS client",error); }
        }
    }
}
