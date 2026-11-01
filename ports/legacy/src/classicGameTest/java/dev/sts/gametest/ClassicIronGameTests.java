package dev.sts.gametest;

import com.mojang.authlib.GameProfile;
import dev.sts.broadcast.*;
import dev.sts.compat.IronAccess;
import dev.sts.compat.IronAccess.*;
import dev.sts.config.CastOptions;
import dev.sts.config.StsOptions;
import dev.sts.spell.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;

/** Runs against the enum-based Iron engine, including its actual completion mixin. */
@GameTestHolder("sts")
@PrefixGameTestTemplate(false)
public final class ClassicIronGameTests {
    private static final String ROOT="io.redspace.ironsspellbooks.";
    private static Class<?> type(String name) { try { return Class.forName(ROOT+name); } catch(ClassNotFoundException error) { throw new IllegalStateException(error); } }
    private static boolean has(Object target,String method,int count) { return Arrays.stream((target instanceof Class<?> c?c:target.getClass()).getMethods()).anyMatch(m -> m.getName().equals(method)&&m.getParameterCount()==count); }
    private static Class<?> utils() { return type(IronAccess.CLASSIC?"util.Utils":"api.util.Utils"); }
    private static Object invoke(Object target,String method,Object... args) { return IronAccess.call(target,method,args); }
    private static Object field(String type,String name) {
        try { return type(type).getField(name).get(null); } catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static Object construct(Class<?> clazz,Object... args) {
        try { return Arrays.stream(clazz.getConstructors()).filter(c -> {
            if(c.getParameterCount()!=args.length) return false;
            var types=c.getParameterTypes();
            for(int i=0;i<args.length;i++) if(args[i]!=null && !(types[i]==int.class&&args[i] instanceof Integer) && !types[i].isInstance(args[i])) return false;
            return true;
        }).findFirst().orElseThrow().newInstance(args); }
        catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static void check(GameTestHelper helper,boolean condition,String message) { if(!condition) helper.fail(message); }
    private static AbstractSpell firebolt() { return SpellRegistry.REGISTRY.get().getValue(new ResourceLocation("irons_spellbooks:fireball")); }
    private static ItemStack item(String name) { return new ItemStack((Item)((Supplier<?>)field("registries.ItemRegistry",name)).get()); }
    private static Object raw(AbstractSpell spell,int level) { return IronAccess.CLASSIC?invoke(invoke(spell.nativeSpell,"getSpellType"),"getSpellForType",level):spell.nativeSpell; }
    private static ItemStack scroll(AbstractSpell spell,int level) {
        var stack=item("SCROLL");
        if(!IronAccess.LEGACY_ITEMS) invoke(type("api.spells.ISpellContainer"),"createScrollContainer",spell.nativeSpell,level,stack);
        else if(IronAccess.CLASSIC) invoke(type("capabilities.spell.SpellData"),"setSpellData",stack,raw(spell,level));
        else invoke(type("capabilities.spell.SpellData"),"setSpellData",stack,spell.nativeSpell,level);
        return stack;
    }
    private static void book(ServerPlayer player,AbstractSpell spell,int level) {
        var stack=item("WIMPY_SPELL_BOOK");
        if(IronAccess.LEGACY_ITEMS) {
            var data=construct(type("capabilities.spellbook.SpellBookData"),1);
            if(spell!=null) {
                if(IronAccess.CLASSIC) invoke(data,"addSpell",raw(spell,level),stack);else invoke(data,"addSpell",spell.nativeSpell,level,stack);
            }
            invoke(type("capabilities.spellbook.SpellBookData"),"setSpellBookData",stack,data);
        } else {
            var data=invoke(type("api.spells.ISpellContainer"),"create",1,true,true);
            if(spell!=null) invoke(data,"addSpellAtIndex",spell.nativeSpell,level,0,true,stack);
            invoke(data,"save",stack);
        }
        if(has(utils(),"setPlayerSpellbookStack",2)) invoke(utils(),"setPlayerSpellbookStack",player,stack);
        else player.setItemInHand(InteractionHand.MAIN_HAND,stack);
    }
    private static ServerPlayer player(GameTestHelper helper) {
        var server=helper.getLevel().getServer();var profile=new GameProfile(UUID.randomUUID(),"sts-classic");
        if(server.getProfileCache()==null) {
            var cache=new net.minecraft.server.players.GameProfileCache((names,agent,callback) -> {},new java.io.File("sts-classic-profiles.json"));
            try {
                Field direct=Arrays.stream(net.minecraft.server.MinecraftServer.class.getDeclaredFields()).filter(f -> f.getType()==net.minecraft.server.players.GameProfileCache.class).findFirst().orElse(null);
                if(direct!=null) { direct.setAccessible(true);direct.set(server,cache); }
                else {
                    Field services=Arrays.stream(net.minecraft.server.MinecraftServer.class.getDeclaredFields()).filter(f -> f.getType().getName().equals("net.minecraft.server.Services")).findFirst().orElseThrow();
                    services.setAccessible(true);Object old=services.get(server);Class<?> clazz=old.getClass();
                    var components=clazz.getRecordComponents();var values=new Object[components.length];
                    for(int i=0;i<components.length;i++) values[i]=components[i].getType()==net.minecraft.server.players.GameProfileCache.class?cache:components[i].getAccessor().invoke(old);
                    services.set(server,construct(clazz,values));
                }
            } catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        server.getProfileCache().add(profile);
        ServerPlayer player;
        Constructor<?> constructor=ServerPlayer.class.getConstructors()[0];
        if(constructor.getParameterCount()==3) player=(ServerPlayer)construct(ServerPlayer.class,server,helper.getLevel(),profile);
        else player=(ServerPlayer)construct(ServerPlayer.class,server,helper.getLevel(),profile,null);
        var connection=new Connection(PacketFlow.SERVERBOUND);var channel=new EmbeddedChannel(connection);
        channel.attr(io.netty.util.AttributeKey.<String>valueOf("fml:netversion")).set(net.minecraftforge.network.NetworkConstants.NETVERSION);
        server.getPlayerList().placeNewPlayer(connection,player);player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.setNoGravity(true);var pos=helper.absolutePos(new net.minecraft.core.BlockPos(1,2,1));player.teleportTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
        var attribute=(Attribute)((Supplier<?>)field(IronAccess.CLASSIC?"registries.AttributeRegistry":"api.registry.AttributeRegistry","MAX_MANA")).get();player.getAttribute(attribute).setBaseValue(3000);
        return player;
    }
    private static Object nativeMagic(ServerPlayer player) { return invoke(type(IronAccess.CLASSIC?"capabilities.magic.PlayerMagicData":"api.magic.MagicData"),"getPlayerMagicData",player); }
    private static void remove(ServerPlayer player) { StrongSpellBroadcasts.discard(player.getUUID());invoke(nativeMagic(player),"resetCastingState");player.server.getPlayerList().remove(player); }
    private static void cooldowns(ServerPlayer player) { var data=invoke(nativeMagic(player),"getPlayerCooldowns");if(has(data,"clearCooldowns",0)) invoke(data,"clearCooldowns");else ((Map<?,?>)invoke(data,"getSpellCooldowns")).clear(); }
    private static void complete(ServerPlayer player,AbstractSpell spell,int level) {
        Object raw=raw(spell,level), source=field(IronAccess.CLASSIC?"spells.CastSource":"api.spells.CastSource","SPELLBOOK");
        if(IronAccess.CLASSIC) {
            invoke(raw,"castSpell",player.getCommandSenderWorld(),player,source,true);
            invoke(raw,"onServerCastComplete",player.getCommandSenderWorld(),player,nativeMagic(player),false);
        } else {
            invoke(raw,"castSpell",player.getCommandSenderWorld(),level,player,source,true);
            invoke(raw,"onServerCastComplete",player.getCommandSenderWorld(),level,player,nativeMagic(player),false);
        }
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void instantUnpreparedSpellAlsoPaysSurcharge(GameTestHelper helper) {
        var player=player(helper);
        try {
            var spell=SpellRegistry.REGISTRY.get().getValue(new ResourceLocation(IronAccess.CLASSIC?"irons_spellbooks:firebolt":"irons_spellbooks:evasion"));
            book(player,null,0);SpellKnowledge.observe(player,scroll(spell,1));var magic=MagicData.getPlayerMagicData(player);magic.setMana(3000);
            int expected=3000-CastOptions.surcharge(spell.getManaCost(1,player));
            check(helper,SpellBridge.requestCast(player,spell.getSpellResource()),"Instant spell rejected");
            // Some engines finish INSTANT spells inline; others use their next native tick.
            // Complete that native path immediately here so older mandatory minimum regen
            // does not change the measured charge. A separate test exercises actual ticks.
            if(magic.isCasting()) complete(player,spell,1);
            check(helper,magic.getMana()==expected,"Instant native completion charge: mana="+magic.getMana()+", expected="+expected);
            remove(player);helper.succeed();
        } catch(RuntimeException|Error error) { remove(player);throw error; }
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void catalogAndAuthoritativeEquipment(GameTestHelper helper) {
        var player=player(helper);
        try {
            var spell=firebolt();check(helper,spell!=null,"Native spell registry missing");
            check(helper,SpellBridge.registeredSpellIds().size()==SpellRegistry.getEnabledSpells().size(),"Registry enumeration dropped native spells");
            check(helper,!SpellBridge.requestCast(player,new ResourceLocation("sts:forged")),"Forged spell accepted");
            player.getInventory().setItem(9,scroll(spell,3));SpellKnowledge.observe(player);
            check(helper,!SpellBridge.requestCast(player,spell.getSpellResource(),CastOptions.STRICT),"Inventory scroll bypassed book gate");
            book(player,spell,2);check(helper,SpellBridge.hasEquippedSpellbook(player),"Native held book was not recognized");
            var magic=MagicData.getPlayerMagicData(player);magic.setMana(0);
            check(helper,!SpellBridge.requestCast(player,spell.getSpellResource(),CastOptions.STRICT),"Zero mana cast accepted");
            magic.setMana(3000);check(helper,SpellBridge.requestCast(player,spell.getSpellResource(),CastOptions.STRICT),"Prepared native cast rejected");
            check(helper,magic.getCastingSpellLevel()==2&&!UnpreparedCasting.active(player),"Prepared level/source changed");
            complete(player,spell,2);check(helper,magic.getMana()==3000-spell.getManaCost(2,player),"Prepared mana cost changed");
            check(helper,!SpellBridge.requestCast(player,spell.getSpellResource(),CastOptions.STRICT),"Native cooldown bypassed");
        } finally { remove(player); }
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void unlockUpgradeAndSurchargeCompletion(GameTestHelper helper) {
        var player=player(helper);
        try {
            var spell=firebolt();book(player,null,0);var scroll=scroll(spell,5);player.getInventory().setItem(9,scroll);SpellKnowledge.observe(player);
            check(helper,SpellKnowledge.level(player,spell.getSpellResource())==Math.min(5,spell.getMaxLevel()),"Inventory upgrade not persisted");
            player.getInventory().setItem(9,ItemStack.EMPTY);var magic=MagicData.getPlayerMagicData(player);
            int cost=CastOptions.surcharge(spell.getManaCost(1,player));magic.setMana(cost-1);
            check(helper,!SpellBridge.requestCast(player,spell.getSpellResource()),"Underfunded surcharge accepted");
            magic.setMana(cost);check(helper,SpellBridge.requestCast(player,spell.getSpellResource()),"Remembered spell rejected");
            check(helper,magic.getCastingSpellLevel()==1&&UnpreparedCasting.active(player),"Unprepared level/source wrong");
            complete(player,spell,1);check(helper,magic.getMana()==0,"Native completion did not charge exactly 150%");
            check(helper,scroll.getCount()==1,"Unprepared cast consumed unlock scroll");
            cooldowns(player);magic.setMana(3000);
            check(helper,SpellBridge.requestCast(player,spell.getSpellResource(),new CastOptions(false,true,false)),"Max-level request rejected");
            check(helper,magic.getCastingSpellLevel()==Math.min(5,spell.getMaxLevel()),"Highest encountered level was not used");
        } finally { remove(player); }
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void cancellationManaDrainAndImplementGate(GameTestHelper helper) {
        var player=player(helper);
        try {
            var spell=firebolt();SpellKnowledge.observe(player,scroll(spell,1));book(player,null,0);var magic=MagicData.getPlayerMagicData(player);magic.setMana(3000);
            check(helper,SpellBridge.requestCast(player,spell.getSpellResource()),"Cancellation cast rejected");Utils.serverSideCancelCast(player,false);
            check(helper,magic.getMana()==3000,"Cancellation charged before completion");
            cooldowns(player);check(helper,SpellBridge.requestCast(player,spell.getSpellResource()),"Mana-drain fixture rejected");magic.setMana(0);UnpreparedCasting.guardMana(player);
            check(helper,!magic.isCasting(),"Mana drain did not cancel");cooldowns(player);magic.setMana(3000);
            player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            if(has(utils(),"setPlayerSpellbookStack",2)) invoke(utils(),"setPlayerSpellbookStack",player,ItemStack.EMPTY);
            check(helper,!SpellBridge.requestCast(player,spell.getSpellResource()),"Empty hand bypassed equipment policy");
            var staff=new ItemStack(Items.STICK);
            if(!IronAccess.LEGACY_ITEMS) invoke(type("api.spells.ISpellContainer"),"createImbuedContainer",spell.nativeSpell,1,staff);
            else if(IronAccess.CLASSIC) invoke(type("capabilities.spell.SpellData"),"setSpellData",staff,raw(spell,1));
            else invoke(type("capabilities.spell.SpellData"),"setSpellData",staff,spell.nativeSpell,1);
            player.setItemInHand(InteractionHand.MAIN_HAND,staff);
            check(helper,SpellBridge.hasCastingImplement(player),"Native imbued item was not recognized");
            check(helper,SpellBridge.requestCast(player,spell.getSpellResource()),"Implement unprepared cast rejected");
        } finally { remove(player); }
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void nativeTickCompletionAndVoiceCodec(GameTestHelper helper) throws Exception {
        var player=player(helper);var spell=firebolt();book(player,spell,1);var magic=MagicData.getPlayerMagicData(player);magic.setMana(3000);
        try {
            NativeVoiceProbe.check(helper);
            check(helper,SpellBridge.requestCast(player,spell.getSpellResource(),CastOptions.STRICT),"Native ticking cast rejected");
            helper.runAfterDelay(spell.getEffectiveCastTime(1,player)+5,() -> {
                try { check(helper,!magic.isCasting(),"Native server ticks did not finish cast");helper.succeed(); }
                finally { remove(player); }
            });
        } catch(Exception|Error error) { remove(player);throw error; }
    }
    private static final class Probe implements BroadcastBackend {
        int plays;
        public boolean available() { return true; }
        public Playback play(net.minecraft.server.level.ServerLevel world,net.minecraft.world.phys.Vec3 position,short[] audio) {
            plays++;return new Playback() { public void stop() {} public boolean stopped() { return false; } };
        }
    }
    @GameTest(template="empty",batch="classic_replay",timeoutTicks=200)
    public static void replayRequiresNativeCompletion(GameTestHelper helper) {
        var player=player(helper);var spell=firebolt();book(player,spell,1);MagicData.getPlayerMagicData(player).setMana(3000);
        boolean enabled=StsOptions.BROADCAST.get(),maximum=StsOptions.BROADCAST_MAX.get();var ids=StsOptions.BROADCAST_SPELLS.get();
        var probe=new Probe();var previous=StrongSpellBroadcasts.setBackend(probe);
        Runnable cleanup=() -> { StrongSpellBroadcasts.clear();StrongSpellBroadcasts.setBackend(previous);StsOptions.BROADCAST.set(enabled);StsOptions.BROADCAST_MAX.set(maximum);StsOptions.BROADCAST_SPELLS.set(ids);remove(player); };
        try {
            StsOptions.BROADCAST.set(false);check(helper,!StrongSpellBroadcasts.workerAllocated(),"Disabled replay allocated worker");
            StsOptions.BROADCAST.set(true);StsOptions.BROADCAST_MAX.set(false);StsOptions.BROADCAST_SPELLS.set(List.of(spell.getSpellId()));
            short[] tone=new short[4000];for(int i=0;i<tone.length;i++) tone[i]=(short)(8000*Math.sin(i*.1));
            check(helper,StrongSpellBroadcasts.request(player,spell.getSpellResource(),AdpcmCodec.encode(tone)),"Replay cast rejected");
            check(helper,probe.plays==0,"Replay began at initiation");
            helper.runAfterDelay(spell.getEffectiveCastTime(1,player)+15,() -> {
                try { check(helper,probe.plays==1,"Native completion did not replay exactly once");helper.succeed(); }
                finally { cleanup.run(); }
            });
        } catch(RuntimeException|Error error) { cleanup.run();throw error; }
    }
}
