package dev.sts.voice;

import java.lang.reflect.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Loads the shipping adapter against each release's own API, never the compile-time API. */
public final class VoiceApiCompatibility {
    private VoiceApiCompatibility() {}
    public static void main(String[] args) throws Exception {
        Path jars = Path.of(args[0]), mainClasses = Path.of(args[1]), report = Path.of(args[2]);
        boolean apiOnly=Arrays.asList(args).contains("--api-only");
        boolean catalog=Arrays.asList(args).contains("--catalog");
        var lines = new ArrayList<String>(); lines.add("jar\tpassed\tdetail");
        int failed = 0, total = 0;
        try (var paths = Files.list(jars)) {
            for (Path jar : paths.filter(path -> path.getFileName().toString().startsWith("voicechat-")&&path.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                total++;
                try { check(jar, mainClasses,apiOnly); lines.add(jar.getFileName()+"\ttrue\t"+(apiOnly?"client capture/availability and playback API contract; wire/runtime test separate":"client capture; playback API; actual category packet encode/decode; old label failure reproduced")); }
                catch (Throwable error) {
                    failed++; Throwable cause = error instanceof InvocationTargetException invocation ? invocation.getTargetException() : error;
                    lines.add(jar.getFileName()+"\tfalse\t"+cause.toString().replace('\t',' ').replace('\n',' '));
                }
            }
        }
        Files.createDirectories(report.getParent()); Files.write(report, lines);
        System.out.printf("Actual-release voice API contracts: %d/%d passed. Report: %s%n", total-failed,total,report);
        if (failed != 0 && !catalog) throw new IllegalStateException("Voice API compatibility failures: "+failed);
    }
    private static void check(Path jar, Path mainClasses,boolean apiOnly) throws Exception {
        Path apiJar = jar;
        try (var zip = new ZipFile(jar.toFile())) {
            if (zip.getEntry("de/maxhenkel/voicechat/api/events/ClientSoundEvent.class") == null) {
                var nested = zip.stream().filter(entry -> entry.getName().matches(".*voicechat-api.*\\.jar")).findFirst().orElseThrow();
                apiJar = jar.getParent().resolve("../api-cache").normalize().resolve(jar.getFileName());
                Files.createDirectories(apiJar.getParent());
                try (var input = zip.getInputStream(nested)) { Files.copy(input,apiJar,StandardCopyOption.REPLACE_EXISTING); }
            }
        }
        try (var loader = new URLClassLoader(new URL[]{mainClasses.toUri().toURL(), apiJar.toUri().toURL(), jar.toUri().toURL()}, VoiceApiCompatibility.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("de.maxhenkel.voicechat.api.") || name.equals("dev.sts.voice.StsVoicechatClient")
                        || name.equals("dev.sts.voice.SpellVoiceCategory") || name.startsWith("de.maxhenkel.voicechat.plugins.impl.VolumeCategoryImpl")
                        || name.equals("de.maxhenkel.voicechat.net.AddCategoryPacket") || name.equals("de.maxhenkel.voicechat.net.Packet")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> registrationType = loader.loadClass("de.maxhenkel.voicechat.api.events.EventRegistration");
            Map<String,Consumer<Object>> callbacks = new HashMap<>();
            Object registration = Proxy.newProxyInstance(loader,new Class<?>[]{registrationType},(proxy,method,values)-> {
                if (method.getName().equals("registerEvent")) {
                    String event = ((Class<?>)values[0]).getSimpleName();
                    if (event.equals("ClientSoundEvent") && (values.length < 3 || !Integer.valueOf(Integer.MIN_VALUE).equals(values[2]))) throw new AssertionError("Audio priority changed");
                    callbacks.put(event,(Consumer<Object>)values[1]);
                }
                return null;
            });
            List<short[]> observed = new ArrayList<>(); List<Boolean> connected = new ArrayList<>();
            Class<?> adapter = loader.loadClass("dev.sts.voice.StsVoicechatClient");
            Method register = adapter.getDeclaredMethod("register",registrationType,Consumer.class,Consumer.class);
            register.setAccessible(true); register.invoke(null,registration,(Consumer<short[]>)observed::add,(Consumer<Boolean>)connected::add);
            boolean[] state = {false,false,false,false}; // mute, disable, disconnect, cancellation
            Class<?> apiType = loader.loadClass("de.maxhenkel.voicechat.api.VoicechatClientApi");
            Object api = Proxy.newProxyInstance(loader,new Class<?>[]{apiType},(proxy,method,values)-> switch(method.getName()) {
                case "isMuted" -> state[0]; case "isDisabled" -> state[1]; case "isDisconnected" -> state[2]; default -> null;
            });
            short[] pcm = new short[960]; Arrays.fill(pcm,(short)1234);
            java.util.function.Function<String,Object> event = name -> {
                try {
                    Class<?> eventType = loader.loadClass("de.maxhenkel.voicechat.api.events."+name);
                    return Proxy.newProxyInstance(loader,new Class<?>[]{eventType},(proxy,method,values)-> switch(method.getName()) {
                        case "getVoicechat" -> api; case "getRawAudio" -> pcm; case "isCancelled" -> state[3];
                        case "isConnected" -> !state[2]; case "isDisabled" -> name.equals("MicrophoneMuteEvent") ? state[0] : state[1]; default -> null;
                    });
                } catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
            };
            callbacks.get("ClientVoicechatInitializationEvent").accept(event.apply("ClientVoicechatInitializationEvent"));
            if (!connected.getLast()) throw new AssertionError("Initialization did not enable connected voice");
            callbacks.get("ClientSoundEvent").accept(event.apply("ClientSoundEvent"));
            if (observed.size()!=1 || observed.getFirst()!=pcm || pcm[0]!=1234) throw new AssertionError("Original audio altered");
            pcm[0]=42; callbacks.get("ClientSoundEvent").accept(event.apply("ClientSoundEvent"));
            if (observed.getLast()[0]!=42) throw new AssertionError("Earlier effect hidden");
            for (int i=0;i<4;i++) {
                state[i]=true; callbacks.get("ClientSoundEvent").accept(event.apply("ClientSoundEvent")); state[i]=false;
            }
            if (observed.size()!=2) throw new AssertionError("Unavailable/cancelled audio reached recognition");
            state[0]=true; callbacks.get("MicrophoneMuteEvent").accept(event.apply("MicrophoneMuteEvent"));
            if (connected.getLast()) throw new AssertionError("Mute did not invalidate pending speech");
            state[0]=false; state[1]=true; callbacks.get("VoicechatDisableEvent").accept(event.apply("VoicechatDisableEvent"));
            if (connected.getLast()) throw new AssertionError("Disable did not invalidate pending speech");
            state[1]=false; state[2]=true; callbacks.get("ClientVoicechatConnectionEvent").accept(event.apply("ClientVoicechatConnectionEvent"));
            if (connected.getLast()) throw new AssertionError("Disconnect did not invalidate pending speech");
            // Resolve every SVC call used by strong-spell playback against the release's own API.
            Class<?> serverApi = loader.loadClass("de.maxhenkel.voicechat.api.VoicechatServerApi");
            Class<?> serverLevel = loader.loadClass("de.maxhenkel.voicechat.api.ServerLevel");
            Class<?> serverPlayer = loader.loadClass("de.maxhenkel.voicechat.api.ServerPlayer");
            Class<?> position = loader.loadClass("de.maxhenkel.voicechat.api.Position");
            Class<?> channel = loader.loadClass("de.maxhenkel.voicechat.api.audiochannel.AudioChannel");
            Class<?> locational = loader.loadClass("de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel");
            Class<?> encoder = loader.loadClass("de.maxhenkel.voicechat.api.opus.OpusEncoder");
            Class<?> player = loader.loadClass("de.maxhenkel.voicechat.api.audiochannel.AudioPlayer");
            Class<?> category = loader.loadClass("de.maxhenkel.voicechat.api.VolumeCategory");
            Class<?> builder = loader.loadClass("de.maxhenkel.voicechat.api.VolumeCategory$Builder");
            serverApi.getMethod("createLocationalAudioChannel",UUID.class,serverLevel,position);
            serverApi.getMethod("createAudioPlayer",channel,encoder,short[].class);
            serverApi.getMethod("createEncoder"); serverApi.getMethod("fromServerLevel",Object.class);
            serverApi.getMethod("createPosition",double.class,double.class,double.class);
            serverApi.getMethod("volumeCategoryBuilder"); serverApi.getMethod("registerVolumeCategory",category);
            for (String name : List.of("setId","setName","setDescription")) builder.getMethod(name,String.class);
            builder.getMethod("build");
            locational.getMethod("setDistance",float.class); channel.getMethod("setCategory",String.class);
            channel.getMethod("setFilter",java.util.function.Predicate.class); channel.getMethod("flush");
            serverPlayer.getMethod("getServerLevel"); serverPlayer.getMethod("getPosition"); serverLevel.getMethod("getServerLevel");
            for (String name : List.of("getX","getY","getZ")) position.getMethod(name);
            encoder.getMethod("close"); encoder.getMethod("isClosed");
            player.getMethod("setOnStopped",Runnable.class);
            for (String name : List.of("startPlaying","stopPlaying","isStopped")) player.getMethod(name);
            loader.loadClass("de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent").getMethod("getVoicechat");
            loader.loadClass("de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent");
            if(apiOnly) return; // Different Minecraft buffer mappings need their matching loader runtime.
            Object nativeBuilder = loader.loadClass("de.maxhenkel.voicechat.plugins.impl.VolumeCategoryImpl$BuilderImpl").getConstructor().newInstance();
            Class<?> voiceApi = loader.loadClass("de.maxhenkel.voicechat.api.VoicechatApi");
            Object categoryApi = Proxy.newProxyInstance(loader,new Class<?>[]{voiceApi},(proxy,method,values) ->
                    method.getName().equals("volumeCategoryBuilder") ? nativeBuilder : null);
            Object shippingCategory = loader.loadClass("dev.sts.voice.SpellVoiceCategory").getMethod("create",voiceApi).invoke(null,categoryApi);
            Class<?> categoryImpl = shippingCategory.getClass();
            Class<?> packetClass = loader.loadClass("de.maxhenkel.voicechat.net.AddCategoryPacket");
            Object payload = packetClass.getConstructor(categoryImpl).newInstance(shippingCategory);
            var bytes = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                packetClass.getMethod("toBytes",net.minecraft.network.FriendlyByteBuf.class).invoke(payload,bytes);
                Object restoredPacket = packetClass.getMethod("fromBytes",net.minecraft.network.FriendlyByteBuf.class)
                        .invoke(packetClass.getConstructor().newInstance(),bytes);
                Object restored = packetClass.getMethod("getCategory").invoke(restoredPacket);
                if (!"Spell voices".equals(categoryImpl.getMethod("getName").invoke(restored)) || bytes.readableBytes()!=0)
                    throw new AssertionError("Category failed native wire round trip");
                bytes.clear();
                Object broken;
                try { broken = categoryImpl.getConstructor(String.class,String.class,String.class,String.class,String.class,int[][].class)
                        .newInstance("sts_spells","Strong spell voices",null,"Replay",null,null); }
                catch (NoSuchMethodException legacy) { broken = categoryImpl.getConstructor(String.class,String.class,String.class,int[][].class)
                        .newInstance("sts_spells","Strong spell voices","Replay",null); }
                boolean rejected = false;
                try { categoryImpl.getMethod("toBytes",net.minecraft.network.FriendlyByteBuf.class).invoke(broken,bytes); }
                catch (InvocationTargetException expected) { rejected = expected.getCause() instanceof io.netty.handler.codec.EncoderException; }
                if (!rejected) throw new AssertionError("Original long label did not reproduce the serializer failure");
            } finally { bytes.release(); }
        }
    }
}
