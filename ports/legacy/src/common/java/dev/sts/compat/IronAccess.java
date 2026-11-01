package dev.sts.compat;

import dev.sts.broadcast.StrongSpellBroadcasts;
import dev.sts.spell.UnpreparedCasting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Cached native API bindings for Iron's enum, mutable-container and slot-container generations.
 * No reflection runs in the PCM/recognizer path. Only native public mod APIs are reflected;
 * Minecraft calls remain ordinary mapped Java calls so production reobfuscation is preserved.
 */
public final class IronAccess {
    private static final String ROOT="io.redspace.ironsspellbooks.";
    public static final boolean CLASSIC=optionalType(ROOT+"api.spells.AbstractSpell")==null;
    public static final boolean LEGACY_ITEMS=optionalType(ROOT+"api.spells.ISpellContainer")==null;
    public static final boolean CAST_EVENT=optionalType(ROOT+"api.events.SpellOnCastEvent")!=null;
    private static final Class<?> SPELL_BASE=type(ROOT+(CLASSIC?"spells.AbstractSpell":"api.spells.AbstractSpell"));
    private static final Class<?> SPELLS=type(ROOT+(CLASSIC?"spells.SpellType":"api.registry.SpellRegistry"));
    private static final Class<?> CONTAINER=type(ROOT+(LEGACY_ITEMS?"capabilities.spellbook.SpellBookData":"api.spells.ISpellContainer"));
    private static final Class<?> DATA=type(ROOT+(LEGACY_ITEMS?"capabilities.spell.SpellData":"api.spells.SpellData"));
    private static final Class<?> MAGIC=type(ROOT+(CLASSIC?"capabilities.magic.PlayerMagicData":"api.magic.MagicData"));
    private static final Class<?> UTILS=type(ROOT+(CLASSIC?"util.Utils":"api.util.Utils"));
    private record Context(String slot,ResourceLocation spell) {}
    private static final Map<UUID,Context> CLASSIC_SLOTS=new HashMap<>();
    private record Signature(String name,List<Class<?>> arguments) {}
    private static final ClassValue<Map<Signature,Method>> METHODS=new ClassValue<>() {
        @Override protected Map<Signature,Method> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
    };
    private static final ClassValue<Map<String,Boolean>> AVAILABLE=new ClassValue<>() {
        @Override protected Map<String,Boolean> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
    };
    private static final Map<String,Optional<Class<?>>> OPTIONAL_TYPES=new ConcurrentHashMap<>();
    private IronAccess() {}
    public static void forget(UUID owner) { CLASSIC_SLOTS.remove(owner); }

    private static Class<?> optionalType(String name) {
        try { return Class.forName(name,false,IronAccess.class.getClassLoader()); }
        catch (ClassNotFoundException ignored) { return null; }
    }
    private static Class<?> type(String name) {
        Class<?> value=optionalType(name);
        if(value==null) throw new IllegalStateException("Unsupported Iron API: missing "+name);
        return value;
    }
    private static Class<?> boxed(Class<?> type) {
        if(!type.isPrimitive()) return type;
        if(type==int.class) return Integer.class;
        if(type==float.class) return Float.class;
        if(type==double.class) return Double.class;
        if(type==long.class) return Long.class;
        if(type==boolean.class) return Boolean.class;
        return type;
    }
    private static boolean compatible(Class<?>[] parameters,Object[] args) {
        if(parameters.length!=args.length) return false;
        for(int i=0;i<args.length;i++) {
            if(args[i]==null) { if(parameters[i].isPrimitive()) return false; }
            else if(!boxed(parameters[i]).isInstance(args[i]) && !(args[i] instanceof Number && Number.class.isAssignableFrom(boxed(parameters[i])))) return false;
        }
        return true;
    }
    private static Class<?> owner(Object target) {
        if(target instanceof Class<?> clazz) return clazz;
        // Inspect the public API, not arbitrary spell subclasses: some subclasses declare
        // client-only parameter types that cannot even be resolved on a dedicated server.
        return SPELL_BASE.isInstance(target)?SPELL_BASE:target.getClass();
    }
    public static Object call(Object target,String name,Object... args) {
        Class<?> owner=owner(target);
        var key=new Signature(name,Arrays.stream(args).map(a -> a==null?Void.class:a.getClass()).toList());
        Method method=METHODS.get(owner).computeIfAbsent(key,k -> Arrays.stream(owner.getMethods())
                .filter(m -> m.getName().equals(name) && compatible(m.getParameterTypes(),args))
                .findFirst().orElseThrow(() -> new IllegalStateException("Unsupported Iron API: "+owner.getName()+"."+name+k.arguments)));
        Object[] converted=args.clone(); Class<?>[] parameters=method.getParameterTypes();
        for(int i=0;i<args.length;i++) if(args[i] instanceof Number n) {
            Class<?> p=boxed(parameters[i]);
            if(p==Integer.class) converted[i]=n.intValue(); else if(p==Float.class) converted[i]=n.floatValue();
            else if(p==Double.class) converted[i]=n.doubleValue(); else if(p==Long.class) converted[i]=n.longValue();
        }
        try { return method.invoke(target instanceof Class<?>?null:target,converted); }
        catch (InvocationTargetException error) {
            if(error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Iron API call failed: "+name,error.getCause());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static boolean has(Object target,String name,int count) {
        Class<?> owner=owner(target);
        return AVAILABLE.get(owner).computeIfAbsent(name+"/"+count,key -> Arrays.stream(owner.getMethods()).anyMatch(m -> m.getName().equals(name)&&m.getParameterCount()==count));
    }
    private static Object field(Object target,String name) {
        try { return (target instanceof Class<?> c?c:target.getClass()).getField(name).get(target instanceof Class<?>?null:target); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Unsupported Iron field: "+name,error); }
    }
    private static Object construct(Class<?> clazz,Object... args) {
        try { return Arrays.stream(clazz.getConstructors()).filter(c -> compatible(c.getParameterTypes(),args)).findFirst().orElseThrow().newInstance(args); }
        catch (InvocationTargetException error) { throw new IllegalStateException(error.getCause()); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static int integer(Object value) { return ((Number)value).intValue(); }
    public static int rawLevel(Object spell) { return integer(call(spell,has(spell,"getRawLevel",0)?"getRawLevel":"getLevel")); }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value); }
    private static boolean named(Object value,String className) {
        return OPTIONAL_TYPES.computeIfAbsent(className,key -> Optional.ofNullable(optionalType(ROOT+key))).map(type -> type.isInstance(value)).orElse(false);
    }
    public static boolean isBook(ItemStack stack) { return named(stack.getItem(),"item.SpellBook")||named(stack.getItem(),"api.item.ISpellbook"); }
    public static boolean isScroll(ItemStack stack) { return named(stack.getItem(),"item.Scroll"); }
    public static boolean creativeCharges() {
        Class<?> config=optionalType(ROOT+"config.ServerConfigs");
        if(config==null) return false;
        try { return bool(call(field(config,"CREATIVE_MANA_COST"),"get")); }
        catch (IllegalStateException absent) { return false; }
    }
    private static ResourceLocation id(Object value) {
        String text=value.toString();
        return new ResourceLocation(text.indexOf(':')<0?"irons_spellbooks:"+text:text);
    }
    private static Object source(CastSource value) {
        Class<?> clazz=type(ROOT+(CLASSIC?"spells.CastSource":"api.spells.CastSource"));
        return field(clazz,value.name());
    }
    public enum CastSource { SPELLBOOK,SCROLL,SWORD,NONE }

    public static final class AbstractSpell {
        public final Object nativeSpell;
        private AbstractSpell(Object value) { nativeSpell=value; }
        private Object kind() { return call(nativeSpell,"getSpellType"); }
        private Object at(int level) { return CLASSIC?call(kind(),"getSpellForType",level):nativeSpell; }
        public ResourceLocation getSpellResource() { return id(CLASSIC?call(kind(),"getId"):call(nativeSpell,has(nativeSpell,"getSpellResource",0)?"getSpellResource":"getSpellId")); }
        public String getSpellId() { return getSpellResource().toString(); }
        public int getMaxLevel() { return integer(call(CLASSIC?kind():nativeSpell,"getMaxLevel")); }
        public boolean isEnabled() { return bool(call(CLASSIC?kind():nativeSpell,"isEnabled")); }
        public boolean isLearned(Player player) { return CLASSIC||!has(nativeSpell,"isLearned",1)||bool(call(nativeSpell,"isLearned",player)); }
        public int getManaCost(int level) { return getManaCost(level,null); }
        public int getManaCost(int level,Player player) { return integer(CLASSIC?call(at(level),"getManaCost"):has(nativeSpell,"getManaCost",1)?call(nativeSpell,"getManaCost",level):call(nativeSpell,"getManaCost",level,player)); }
        public int getLevelFor(int level,Player player) { return CLASSIC?(has(nativeSpell,"getLevel",1)?integer(call(at(level),"getLevel",player)):level):integer(call(nativeSpell,has(nativeSpell,"getLevelFor",2)?"getLevelFor":"getLevel",level,player)); }
        public int getEffectiveCastTime(int level,Player player) { return integer(CLASSIC?call(at(level),"getEffectiveCastTime",player):call(nativeSpell,"getEffectiveCastTime",level,player)); }
        public Component getDisplayName(Player player) { return (Component)(CLASSIC?call(kind(),"getDisplayName"):has(nativeSpell,"getDisplayName",1)?call(nativeSpell,"getDisplayName",player):call(nativeSpell,"getDisplayName")); }
        public ResourceLocation getSpellIconResource() {
            return CLASSIC?new ResourceLocation(getSpellResource().getNamespace(),"textures/spell_icons/"+getSpellResource().getPath()+".png"):(ResourceLocation)call(nativeSpell,"getSpellIconResource");
        }
        public boolean attemptInitiateCast(ItemStack item,int level,Level world,Player player,CastSource castSource,boolean trigger,String slot) {
            if(CLASSIC||!has(nativeSpell,"attemptInitiateCast",7)) {
                CLASSIC_SLOTS.put(player.getUUID(),new Context(slot,getSpellResource()));
                boolean accepted;
                try { accepted=bool(CLASSIC?call(at(level),"attemptInitiateCast",item,world,player,source(castSource),trigger):call(nativeSpell,"attemptInitiateCast",item,level,world,player,source(castSource),trigger)); }
                catch (RuntimeException error) { CLASSIC_SLOTS.remove(player.getUUID()); throw error; }
                if(!accepted||!bool(call(call(MAGIC,"getPlayerMagicData",player),"isCasting"))) CLASSIC_SLOTS.remove(player.getUUID());
                return accepted;
            }
            if(CAST_EVENT) return bool(call(nativeSpell,"attemptInitiateCast",item,level,world,player,source(castSource),trigger,slot));
            CLASSIC_SLOTS.put(player.getUUID(),new Context(slot,getSpellResource()));
            try {
                boolean accepted=bool(call(nativeSpell,"attemptInitiateCast",item,level,world,player,source(castSource),trigger,slot));
                if(!accepted||!bool(call(call(MAGIC,"getPlayerMagicData",player),"isCasting"))) CLASSIC_SLOTS.remove(player.getUUID());
                return accepted;
            } catch(RuntimeException error) { CLASSIC_SLOTS.remove(player.getUUID());throw error; }
        }
    }
    public static final class SpellRegistry {
        public static final Supplier<SpellRegistry> REGISTRY=SpellRegistry::new;
        private static Map<ResourceLocation,AbstractSpell> classicSpells;
        private static Map<ResourceLocation,AbstractSpell> classicSpells() {
            if(classicSpells==null) {
                var values=new LinkedHashMap<ResourceLocation,AbstractSpell>();
                for(Object kind:(Object[])call(SPELLS,"values")) {
                    if(kind.toString().equals("NONE_SPELL")) continue;
                    var spell=new AbstractSpell(call(kind,"getSpellForType",1));
                    values.put(spell.getSpellResource(),spell);
                }
                classicSpells=Map.copyOf(values);
            }
            return classicSpells;
        }
        public AbstractSpell getValue(ResourceLocation wanted) {
            if(CLASSIC) return classicSpells().get(wanted);
            Object registry=field(SPELLS,"REGISTRY"); if(registry instanceof Supplier<?> supplier) registry=supplier.get();
            Object value=call(registry,has(registry,"getValue",1)?"getValue":"get",wanted);
            return value==null?null:new AbstractSpell(value);
        }
        public static List<AbstractSpell> getEnabledSpells() {
            var spells=new ArrayList<AbstractSpell>();
            if(CLASSIC) {
                return classicSpells().values().stream().filter(AbstractSpell::isEnabled).toList();
            } else for(Object spell:(Iterable<?>)call(SPELLS,"getEnabledSpells")) spells.add(new AbstractSpell(spell));
            return spells;
        }
        public static AbstractSpell none() { return null; }
    }
    public static final class SpellData {
        public static final SpellData EMPTY=new SpellData(null,0);
        private final AbstractSpell spell; private final int level;
        public SpellData(AbstractSpell spell,int level) { this.spell=spell;this.level=level; }
        static SpellData wrap(Object nativeData) {
            if(nativeData==null) return EMPTY;
            Object value=call(nativeData,"getSpell"); int level=integer(call(nativeData,"getLevel"));
            if(value==null||level<1) return EMPTY;
            var spell=new AbstractSpell(value);
            if(spell.getSpellResource().getPath().equals("none")) return EMPTY;
            return new SpellData(spell,level);
        }
        public AbstractSpell getSpell() { return spell; }
        public int getLevel() { return level; }
    }
    public record Slot(SpellData spellData) {}
    public static final class ISpellContainer {
        private final ItemStack stack;
        private ISpellContainer(ItemStack stack) { this.stack=stack; }
        public static boolean isSpellContainer(ItemStack stack) {
            if(stack==null||stack.isEmpty()) return false;
            return LEGACY_ITEMS?isBook(stack)||bool(call(DATA,"hasSpellData",stack)):bool(call(CONTAINER,"isSpellContainer",stack));
        }
        public static ISpellContainer get(ItemStack stack) { return new ISpellContainer(stack); }
        public List<Slot> getActiveSpells() {
            var result=new ArrayList<Slot>();
            if(LEGACY_ITEMS) {
                if(isBook(stack)) {
                    Object book=call(CONTAINER,"getSpellBookData",stack);
                    if(book!=null) for(Object raw:(Iterable<?>)call(book,"getActiveInscribedSpells"))
                        if(raw!=null) result.add(new Slot(CLASSIC?new SpellData(new AbstractSpell(raw),rawLevel(raw)):SpellData.wrap(raw)));
                } else if(bool(call(DATA,"hasSpellData",stack))) result.add(new Slot(SpellData.wrap(call(DATA,"getSpellData",stack))));
            } else {
                Object container=call(CONTAINER,"get",stack);
                for(Object value:(Iterable<?>)call(container,"getActiveSpells")) {
                    Object data=has(value,"spellData",0)?call(value,"spellData"):value;
                    SpellData wrapped=SpellData.wrap(data); if(wrapped!=SpellData.EMPTY) result.add(new Slot(wrapped));
                }
            }
            return result;
        }
        public SpellData getSpellAtIndex(int index) {
            if(LEGACY_ITEMS) { var spells=getActiveSpells(); return index>=0&&index<spells.size()?spells.get(index).spellData():SpellData.EMPTY; }
            return SpellData.wrap(call(call(CONTAINER,"get",stack),"getSpellAtIndex",index));
        }
        public boolean isSpellWheel() {
            if(LEGACY_ITEMS) return !isScroll(stack);
            Object value=call(CONTAINER,"get",stack);
            return bool(call(value,has(value,"isSpellWheel",0)?"isSpellWheel":"spellWheel"));
        }
        public boolean mustEquip() { return LEGACY_ITEMS?isBook(stack):bool(call(call(CONTAINER,"get",stack),"mustEquip")); }
    }
    public static final class SpellSelectionManager {
        private final Player player;
        public record Option(SpellData spellData,String slot,int globalIndex) {}
        // Fields preserve the public shape consumed by the shared bridge.
        public static final class Selection {
            public final SpellData spellData; public final String slot; public final int globalIndex;
            Selection(SpellData spell,String slot,int index) { spellData=spell;this.slot=slot;globalIndex=index; }
        }
        public SpellSelectionManager(Player player) { this.player=player; }
        public List<Selection> getAllSpells() {
            var result=new ArrayList<Selection>();
            if(CLASSIC||optionalType(ROOT+"api.magic.SpellSelectionManager")==null) {
                for(var slot:ISpellContainer.get(Utils.getPlayerSpellbookStack(player)).getActiveSpells())
                    result.add(new Selection(slot.spellData(),"spellbook",result.size()));
                for(InteractionHand hand:InteractionHand.values()) {
                    ItemStack stack=player.getItemInHand(hand);
                    if(isBook(stack)||isScroll(stack)||!ISpellContainer.isSpellContainer(stack)) continue;
                    for(var slot:ISpellContainer.get(stack).getActiveSpells()) result.add(new Selection(slot.spellData(),hand.name(),result.size()));
                }
            } else {
                Object selection=construct(type(ROOT+"api.magic.SpellSelectionManager"),player);
                for(Object option:(Iterable<?>)call(selection,"getAllSpells")) result.add(new Selection(SpellData.wrap(field(option,"spellData")),(String)field(option,"slot"),integer(field(option,"globalIndex"))));
            }
            return result;
        }
        public SpellData getSelectedSpellData() {
            if(CLASSIC||optionalType(ROOT+"api.magic.SpellSelectionManager")==null) return getAllSpells().stream().map(s -> s.spellData).findFirst().orElse(SpellData.EMPTY);
            return SpellData.wrap(call(construct(type(ROOT+"api.magic.SpellSelectionManager"),player),"getSelectedSpellData"));
        }
    }
    public static final class Utils {
        public static ItemStack getPlayerSpellbookStack(Player player) {
            if(has(UTILS,"getPlayerSpellbookStack",1)) return (ItemStack)call(UTILS,"getPlayerSpellbookStack",player);
            for(InteractionHand hand:InteractionHand.values()) if(isBook(player.getItemInHand(hand))) return player.getItemInHand(hand);
            return ItemStack.EMPTY;
        }
        public static boolean serverSideInitiateQuickCast(ServerPlayer player,int index) {
            if(optionalType(ROOT+"api.magic.SpellSelectionManager")!=null&&has(UTILS,"serverSideInitiateQuickCast",2)) return bool(call(UTILS,"serverSideInitiateQuickCast",player,index));
            var selections=new SpellSelectionManager(player).getAllSpells();
            if(index<0||index>=selections.size()) return false;
            var option=selections.get(index); boolean book=option.slot.equals("spellbook");
            ItemStack item=book?ItemStack.EMPTY:player.getItemInHand(InteractionHand.valueOf(option.slot));
            var spell=option.spellData.getSpell();int level=CLASSIC?option.spellData.getLevel():spell.getLevelFor(option.spellData.getLevel(),player);
            return spell.attemptInitiateCast(item,level,player.getCommandSenderWorld(),player,book?CastSource.SPELLBOOK:CastSource.SWORD,true,option.slot);
        }
        public static void serverSideCancelCast(ServerPlayer player,boolean trigger) { call(UTILS,"serverSideCancelCast",player,trigger); CLASSIC_SLOTS.remove(player.getUUID()); }
    }
    public static final class MagicData {
        private final Player player; private final Object value;
        private MagicData(Player player,Object value) { this.player=player;this.value=value; }
        public static MagicData getPlayerMagicData(Player player) { return new MagicData(player,call(MAGIC,"getPlayerMagicData",player)); }
        public boolean isCasting() {
            boolean casting=bool(call(value,"isCasting"));
            if(!casting) CLASSIC_SLOTS.remove(player.getUUID());
            return casting;
        }
        public String getCastingSpellId() {
            Object id=call(value,"getCastingSpellId");
            if(CLASSIC) return new AbstractSpell(call(value,"getCastingSpell")).getSpellId();
            return id.toString();
        }
        public int getCastingSpellLevel() { return integer(call(value,"getCastingSpellLevel")); }
        public float getMana() { return ((Number)call(value,"getMana")).floatValue(); }
        public void setMana(float amount) { call(value,"setMana",amount); }
        public CastSource getCastSource() {
            try { return CastSource.valueOf(call(value,"getCastSource").toString()); } catch (IllegalArgumentException unknown) { return CastSource.NONE; }
        }
        public String getCastingEquipmentSlot() { return !has(value,"getCastingEquipmentSlot",0)?Optional.ofNullable(CLASSIC_SLOTS.get(player.getUUID())).map(Context::slot).orElse(""):(String)call(value,"getCastingEquipmentSlot"); }
        public Recasts getPlayerRecasts() { return new Recasts(has(value,"getPlayerRecasts",0)?call(value,"getPlayerRecasts"):null); }
    }
    public record Recasts(Object nativeRecasts) {
        public boolean hasRecastForSpell(String id) { return nativeRecasts!=null&&bool(call(nativeRecasts,"hasRecastForSpell",id)); }
    }
    public static final class SpellOnCastEvent {
        private final Object nativeEvent; private final ServerPlayer player; private final String id; private final int level;
        private int cost;
        SpellOnCastEvent(Object event) {
            nativeEvent=event; player=call(event,"getEntity") instanceof ServerPlayer server?server:null;
            id=(String)call(event,"getSpellId");level=integer(call(event,"getSpellLevel"));cost=integer(call(event,"getManaCost"));
        }
        SpellOnCastEvent(ServerPlayer player,AbstractSpell spell,int level,int cost) { nativeEvent=null;this.player=player;id=spell.getSpellId();this.level=level;this.cost=cost; }
        public net.minecraft.world.entity.LivingEntity getEntity() { return player; }
        public String getSpellId() { return id; }
        public int getSpellLevel() { return level; }
        public int getManaCost() { return cost; }
        public void setManaCost(int value) { cost=value;if(nativeEvent!=null) call(nativeEvent,"setManaCost",value); }
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    public static void registerHooks() {
        if(!CAST_EVENT) return; // Optional pseudo mixins handle native engines without a completion event.
        Class<? extends Event> event=type(ROOT+"api.events.SpellOnCastEvent").asSubclass(Event.class);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,(Class)event,(java.util.function.Consumer<Event>)value -> {
            var cast=new SpellOnCastEvent(value);UnpreparedCasting.onCast(cast);StrongSpellBroadcasts.onCast(cast);
        });
    }
    /** Returns false only for an STS unprepared native pulse that can no longer be funded. */
    public static boolean beforeClassicCast(Object raw,ServerPlayer player) {
        Context context=CLASSIC_SLOTS.get(player.getUUID());
        if(context==null||!UnpreparedCasting.SLOT.equals(context.slot)) return true;
        return beforeNativeCast(raw,rawLevel(raw),player);
    }
    public static boolean beforeNativeCast(Object raw,int level,ServerPlayer player) {
        Context context=CLASSIC_SLOTS.get(player.getUUID());
        if(context==null||!UnpreparedCasting.SLOT.equals(context.slot)) return true;
        var spell=new AbstractSpell(raw);
        if(!context.spell.equals(spell.getSpellResource())) return true;
        int base=spell.getManaCost(level,player);
        int total=dev.sts.config.CastOptions.surcharge(base);
        var magic=MagicData.getPlayerMagicData(player);
        if(player.isCreative()&&!creativeCharges()) return true;
        if(magic.getMana()<total) { Utils.serverSideCancelCast(player,false);return false; }
        // Iron's native cast charges the base, synchronizes mana and applies its cooldown.
        magic.setMana(magic.getMana()-(total-base));return true;
    }
    public static void afterClassicCast(Object raw,ServerPlayer player) {
        if(!CLASSIC_SLOTS.containsKey(player.getUUID())&&StrongSpellBroadcasts.pendingCount()==0) return;
        afterNativeCast(raw,rawLevel(raw),player);
    }
    public static void afterNativeCast(Object raw,int level,ServerPlayer player) {
        Context context=CLASSIC_SLOTS.get(player.getUUID());
        if(context!=null&&context.spell.equals(new AbstractSpell(raw).getSpellResource())&&!call(raw,"getCastType").toString().equals("CONTINUOUS")) CLASSIC_SLOTS.remove(player.getUUID());
        if(StrongSpellBroadcasts.pendingCount()==0) return;
        var spell=new AbstractSpell(raw);
        StrongSpellBroadcasts.onCast(new SpellOnCastEvent(player,spell,level,spell.getManaCost(level,player)));
    }
}
