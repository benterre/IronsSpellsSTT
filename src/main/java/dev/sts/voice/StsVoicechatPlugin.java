package dev.sts.voice;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import dev.sts.broadcast.StrongSpellBroadcasts;
import dev.sts.broadcast.BroadcastBackend;

/** SVC discovers this optional plugin. The dedicated server never loads the client bridge. */
@ForgeVoicechatPlugin
public final class StsVoicechatPlugin implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return "sts";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class,
                event -> StrongSpellBroadcasts.setBackend(new SvcBroadcastBackend(event.getVoicechat())));
        registration.registerEvent(VoicechatServerStoppedEvent.class,
                event -> StrongSpellBroadcasts.setBackend(BroadcastBackend.NONE));
        if (FMLEnvironment.dist == Dist.CLIENT) {
            StsVoicechatClient.register(registration);
        }
    }
}
