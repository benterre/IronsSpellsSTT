package dev.sts.voice;

import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophoneMuteEvent;
import de.maxhenkel.voicechat.api.events.VoicechatDisableEvent;
import dev.sts.client.VoiceSpellClient;

import java.util.function.Consumer;

/**
 * Observes the existing SVC microphone: no second microphone, decoder, audio sender or socket.
 * Recognition receives a copy in the service; original frames continue through SVC unchanged.
 */
final class StsVoicechatClient {
    private StsVoicechatClient() {
    }

    static void register(EventRegistration registration) {
        register(registration, frame -> VoiceSpellClient.get().acceptAudio(frame),
                active -> VoiceSpellClient.get().audioConnected(active));
    }

    /** Explicit sinks permit an offline API contract test without starting Minecraft or a microphone. */
    static void register(EventRegistration registration, Consumer<short[]> audioSink, Consumer<Boolean> connectionSink) {
        registration.registerEvent(ClientSoundEvent.class, event -> audio(event, audioSink), Integer.MIN_VALUE);
        registration.registerEvent(ClientVoicechatInitializationEvent.class,
                event -> connectionSink.accept(isAvailable(event.getVoicechat())));
        registration.registerEvent(ClientVoicechatConnectionEvent.class,
                event -> connectionSink.accept(event.isConnected()
                        && !event.getVoicechat().isDisabled() && !event.getVoicechat().isMuted()));
        registration.registerEvent(MicrophoneMuteEvent.class,
                event -> connectionSink.accept(!event.isDisabled()
                        && !event.getVoicechat().isDisconnected() && !event.getVoicechat().isDisabled()));
        registration.registerEvent(VoicechatDisableEvent.class,
                event -> connectionSink.accept(!event.isDisabled()
                        && !event.getVoicechat().isDisconnected() && !event.getVoicechat().isMuted()));
    }

    private static boolean isAvailable(VoicechatClientApi api) {
        return !api.isDisconnected() && !api.isDisabled() && !api.isMuted();
    }

    private static void audio(ClientSoundEvent event, Consumer<short[]> audioSink) {
        if (event.isCancelled() || event.getVoicechat().isDisabled()
                || event.getVoicechat().isMuted() || event.getVoicechat().isDisconnected()) {
            return;
        }
        // SVC's public client event supplies mono signed 16-bit PCM at 48 kHz.
        // Never cancel or replace this event: proximity and group voice traffic are unaffected.
        audioSink.accept(event.getRawAudio());
    }
}
