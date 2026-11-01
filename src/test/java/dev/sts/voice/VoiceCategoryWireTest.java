package dev.sts.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class VoiceCategoryWireTest {
    @Test void oldCategoryReproducesReportedEncoderFailure() throws Exception {
        Class<?> categoryClass = Class.forName("de.maxhenkel.voicechat.plugins.impl.VolumeCategoryImpl");
        Object legacy;
        try { legacy = categoryClass.getConstructor(String.class,String.class,String.class,String.class,String.class,int[][].class)
                .newInstance("sts_spells", "Strong spell voices", null, "Voice replay", null, null); }
        catch(NoSuchMethodException olderApi) { legacy=categoryClass.getConstructor(String.class,String.class,String.class,int[][].class)
                .newInstance("sts_spells","Strong spell voices","Voice replay",null); }
        final Object old=legacy;
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var error = assertThrows(InvocationTargetException.class, () -> categoryClass.getMethod("toBytes",FriendlyByteBuf.class).invoke(old,buffer));
            assertInstanceOf(io.netty.handler.codec.EncoderException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("max 16"));
        } finally { buffer.release(); }
    }
    @Test void shippingCategoryPassesActualSvcAddPacketRoundTrip() throws Exception {
        Object builder = Class.forName("de.maxhenkel.voicechat.plugins.impl.VolumeCategoryImpl$BuilderImpl").getConstructor().newInstance();
        VoicechatApi api = (VoicechatApi)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{VoicechatApi.class},
                (proxy, method, args) -> method.getName().equals("volumeCategoryBuilder") ? builder : null);
        VolumeCategory category = SpellVoiceCategory.create(api);
        Class<?> packetClass = Class.forName("de.maxhenkel.voicechat.net.AddCategoryPacket");
        Object packet = packetClass.getConstructor(category.getClass()).newInstance(category);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packetClass.getMethod("toBytes",FriendlyByteBuf.class).invoke(packet,buffer);
            assertTrue(buffer.readableBytes() < 256);
            Object decoded = packetClass.getMethod("fromBytes",FriendlyByteBuf.class).invoke(packetClass.getConstructor().newInstance(),buffer);
            VolumeCategory restored = (VolumeCategory)packetClass.getMethod("getCategory").invoke(decoded);
            assertEquals(category.getId(),restored.getId()); assertEquals("Spell voices",restored.getName());
            assertEquals(category.getDescription(),restored.getDescription()); assertEquals(0,buffer.readableBytes());
        } finally { buffer.release(); }
    }
    @Test void categoryWireBoundsAreCheckedBeforeRegistration() {
        var data = new HashMap<String,Object>(); data.put("getId","sts_spells"); data.put("getName","Spell voices");
        VolumeCategory category = (VolumeCategory)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{VolumeCategory.class},
                (proxy, method, args) -> data.get(method.getName()));
        assertDoesNotThrow(() -> SpellVoiceCategory.validate(category));
        data.put("getName","Strong spell voices"); assertThrows(IllegalArgumentException.class, () -> SpellVoiceCategory.validate(category));
        data.put("getName","Spell voices"); data.put("getDescription","x".repeat(32768));
        assertThrows(IllegalArgumentException.class, () -> SpellVoiceCategory.validate(category));
        data.remove("getDescription"); data.put("getId","INVALID"); assertThrows(IllegalArgumentException.class, () -> SpellVoiceCategory.validate(category));
        data.put("getId","sts_spells"); data.put("getIcon",new int[16][15]); assertThrows(IllegalArgumentException.class, () -> SpellVoiceCategory.validate(category));
    }
}
