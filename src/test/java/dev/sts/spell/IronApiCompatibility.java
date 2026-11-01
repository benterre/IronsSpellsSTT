package dev.sts.spell;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Static API audit: reads each release's own class files without resolving Minecraft/client types. */
public final class IronApiCompatibility {
    private static final String ROOT="io/redspace/ironsspellbooks/";
    private record Api(String parent,List<String> interfaces,Set<String> methods) {}
    private IronApiCompatibility() {}
    public static void main(String[] args) throws Exception {
        var report=new ArrayList<String>();report.add("jar\tapi\tcontract\tdetail");int total=0,passed=0;
        try(var files=Files.list(Path.of(args[0]))) {
            for(var file:files.filter(p -> p.getFileName().toString().startsWith("irons_spellbooks-")&&p.toString().endsWith(".jar")).sorted().toList()) {
                total++;boolean modern;
                try(var zip=new ZipFile(file.toFile())) {
                    modern=zip.getEntry(ROOT+"api/spells/AbstractSpell.class")!=null;
                    var cache=new HashMap<String,Api>();var missing=new ArrayList<String>();
                    if(modern) {
                        need(zip,cache,missing,"api/spells/AbstractSpell","getSpellResource/0|getSpellId/0","getMaxLevel/0","isEnabled/0","getManaCost/1|getManaCost/2","getLevelFor/2|getLevel/2","getEffectiveCastTime/2","getDisplayName/1|getDisplayName/0","getSpellIconResource/0","attemptInitiateCast/7|attemptInitiateCast/6","castSpell/5");
                        if(zip.getEntry(ROOT+"api/spells/ISpellContainer.class")!=null) need(zip,cache,missing,"api/spells/ISpellContainer","get/1","isSpellContainer/1","getActiveSpells/0","getSpellAtIndex/1","mustEquip/0");
                        else {
                            need(zip,cache,missing,"capabilities/spell/SpellData","getSpellData/1","hasSpellData/1","getSpell/0","getLevel/0");
                            need(zip,cache,missing,"capabilities/spellbook/SpellBookData","getSpellBookData/1","getActiveInscribedSpells/0");
                        }
                        need(zip,cache,missing,"api/magic/MagicData","getPlayerMagicData/1","isCasting/0","getCastingSpellId/0","getCastingSpellLevel/0","getCastSource/0","getMana/0","setMana/1");
                        if(zip.getEntry(ROOT+"api/magic/SpellSelectionManager.class")!=null) need(zip,cache,missing,"api/magic/SpellSelectionManager","getAllSpells/0","getSelectedSpellData/0");
                        need(zip,cache,missing,"api/registry/SpellRegistry","getEnabledSpells/0");
                        need(zip,cache,missing,"api/util/Utils","serverSideCancelCast/2");
                        if(zip.getEntry(ROOT+"api/events/SpellOnCastEvent.class")!=null) need(zip,cache,missing,"api/events/SpellOnCastEvent","getSpellId/0","getSpellLevel/0","getManaCost/0","setManaCost/1");
                    } else {
                        need(zip,cache,missing,"spells/AbstractSpell","getSpellType/0","getRawLevel/0|getLevel/0","getManaCost/0","getEffectiveCastTime/1","attemptInitiateCast/5","castSpell/4");
                        need(zip,cache,missing,"spells/SpellType","values/0","getId/0","getSpellForType/1","getMaxLevel/0","isEnabled/0","getDisplayName/0");
                        need(zip,cache,missing,"capabilities/spell/SpellData","getSpellData/1","hasSpellData/1","getSpell/0","getLevel/0");
                        need(zip,cache,missing,"capabilities/spellbook/SpellBookData","getSpellBookData/1","getActiveInscribedSpells/0");
                        need(zip,cache,missing,"capabilities/magic/PlayerMagicData","getPlayerMagicData/1","isCasting/0","getCastingSpell/0","getCastingSpellLevel/0","getCastSource/0","getMana/0","setMana/1");
                        need(zip,cache,missing,"util/Utils","serverSideCancelCast/2");
                    }
                    if(missing.isEmpty()) passed++;
                    report.add(file.getFileName()+"\t"+(modern?"registry":"classic")+"\t"+missing.isEmpty()+"\t"+(missing.isEmpty()?"Native public method surface present; runtime/client test separate":String.join("; ",missing)));
                }
            }
        }
        var destination=Path.of(args[1]);Files.createDirectories(destination.getParent());Files.write(destination,report);
        System.out.printf("Iron native API surfaces: %d/%d present. This is a static contract audit, not a runtime pass.%n",passed,total);
    }
    private static void need(ZipFile zip,Map<String,Api> cache,List<String> missing,String path,String... methods) throws IOException {
        for(String method:methods) {
            boolean available=false;for(String alternative:method.split("\\|")) if(has(zip,cache,ROOT+path,alternative,new HashSet<>())) { available=true;break; }
            if(!available) missing.add(path+"."+method);
        }
    }
    private static boolean has(ZipFile zip,Map<String,Api> cache,String type,String method,Set<String> visited) throws IOException {
        if(type==null||!visited.add(type)) return false;
        Api api=cache.get(type);
        if(api==null) {
            var entry=zip.getEntry(type+".class");if(entry==null) return false;
            try(var input=new DataInputStream(zip.getInputStream(entry))) { api=read(input);cache.put(type,api); }
        }
        if(api.methods.contains(method)||has(zip,cache,api.parent,method,visited)) return true;
        for(String parent:api.interfaces) if(has(zip,cache,parent,method,visited)) return true;
        return false;
    }
    private static Api read(DataInputStream in) throws IOException {
        if(in.readInt()!=0xcafebabe) throw new IOException("Invalid class file");in.readInt();
        Object[] pool=new Object[in.readUnsignedShort()];
        for(int i=1;i<pool.length;i++) {
            int tag=in.readUnsignedByte();
            switch(tag) {
                case 1 -> pool[i]=in.readUTF();
                case 7 -> pool[i]=in.readUnsignedShort();
                case 3,4,9,10,11,12,17,18 -> in.skipNBytes(4);
                case 5,6 -> {in.skipNBytes(8);i++;}
                case 8,16,19,20 -> in.skipNBytes(2);
                case 15 -> in.skipNBytes(3);
                default -> throw new IOException("Unknown constant tag "+tag);
            }
        }
        in.readUnsignedShort();in.readUnsignedShort();int superclass=in.readUnsignedShort();
        String parent=superclass==0?null:(String)pool[(Integer)pool[superclass]];
        var interfaces=new ArrayList<String>();int count=in.readUnsignedShort();
        for(int i=0;i<count;i++) interfaces.add((String)pool[(Integer)pool[in.readUnsignedShort()]]);
        count=in.readUnsignedShort();for(int i=0;i<count;i++) {in.skipNBytes(6);attributes(in);}
        var methods=new HashSet<String>();count=in.readUnsignedShort();
        for(int i=0;i<count;i++) {
            int flags=in.readUnsignedShort();String name=(String)pool[in.readUnsignedShort()],descriptor=(String)pool[in.readUnsignedShort()];
            if((flags&1)!=0) methods.add(name+"/"+arity(descriptor));attributes(in);
        }
        return new Api(parent,interfaces,methods);
    }
    private static int arity(String descriptor) {
        int arguments=0;
        for(int i=1;descriptor.charAt(i)!=')';arguments++) {
            while(descriptor.charAt(i)=='[') i++;
            if(descriptor.charAt(i)=='L') i=descriptor.indexOf(';',i)+1;else i++;
        }
        return arguments;
    }
    private static void attributes(DataInputStream in) throws IOException {
        int count=in.readUnsignedShort();for(int i=0;i<count;i++) {in.readUnsignedShort();in.skipNBytes(Integer.toUnsignedLong(in.readInt()));}
    }
}
