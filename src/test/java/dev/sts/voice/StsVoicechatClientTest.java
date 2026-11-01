package dev.sts.voice;

import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.events.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual SVC API callback path with synthetic PCM; no production audio or network. */
class StsVoicechatClientTest {
    @Test
    void observesVoiceFrameWithoutReplacingOrCancelingForwardedAudio() {
        Harness harness = new Harness();
        short[] pcm = new short[960];
        pcm[13] = 1720;
        State sound = new State(pcm);
        harness.fire(ClientSoundEvent.class, sound);

        assertEquals(Integer.MIN_VALUE, harness.registration.priorities.get(ClientSoundEvent.class));
        assertEquals(1, harness.audio.size());
        assertSame(pcm, harness.audio.getFirst());
        assertSame(pcm, sound.pcm);
        assertEquals(0, sound.mutations);
        assertEquals(1720, pcm[13]);
    }

    @Test
    void canceledMutedDisabledOrDisconnectedAudioIsNeverRecognized() {
        Harness harness = new Harness();
        State state = new State(new short[960]);
        state.cancelled = true;
        harness.fire(ClientSoundEvent.class, state);
        state.cancelled = false;
        harness.api.muted = true;
        harness.fire(ClientSoundEvent.class, state);
        harness.api.muted = false;
        harness.api.disabled = true;
        harness.fire(ClientSoundEvent.class, state);
        harness.api.disabled = false;
        harness.api.disconnected = true;
        harness.fire(ClientSoundEvent.class, state);
        assertTrue(harness.audio.isEmpty());
        assertEquals(0, state.mutations);
    }

    @Test
    void connectionMuteAndDisableEventsReflectTheirNewState() {
        Harness harness = new Harness();
        State state = new State(null);
        harness.fire(ClientVoicechatInitializationEvent.class, state);
        state.connected = false;
        harness.fire(ClientVoicechatConnectionEvent.class, state);
        state.disabled = true;
        harness.fire(MicrophoneMuteEvent.class, state);
        state.disabled = false;
        harness.fire(MicrophoneMuteEvent.class, state);
        state.disabled = true;
        harness.fire(VoicechatDisableEvent.class, state);
        assertEquals(List.of(true, false, false, true, false), harness.connections);
    }

    @Test
    void observesAudioModifiedEarlierInTheVoiceChatPipeline() {
        Harness harness = new Harness();
        short[] modified = {128, 256, -256, -128};
        State state = new State(modified);
        harness.fire(ClientSoundEvent.class, state);
        assertArrayEquals(modified, harness.audio.getFirst());
        assertEquals(0, state.mutations);
    }

    private static final class Harness {
        final FakeRegistration registration = new FakeRegistration();
        final State api = new State(null);
        final List<short[]> audio = new ArrayList<>();
        final List<Boolean> connections = new ArrayList<>();
        final VoicechatClientApi clientApi = proxy(VoicechatClientApi.class, api, null);

        Harness() {
            StsVoicechatClient.register(registration, audio::add, connections::add);
        }

        <T extends Event> void fire(Class<T> type, State state) {
            registration.fire(type, proxy(type, state, clientApi));
        }
    }

    private static final class FakeRegistration implements EventRegistration {
        final Map<Class<?>, Consumer<?>> listeners = new HashMap<>();
        final Map<Class<?>, Integer> priorities = new HashMap<>();

        @Override
        public <T extends Event> void registerEvent(Class<T> eventClass, Consumer<T> handler, int priority) {
            listeners.put(eventClass, handler);
            priorities.put(eventClass, priority);
        }

        @SuppressWarnings("unchecked")
        <T extends Event> void fire(Class<T> type, T event) {
            ((Consumer<T>) listeners.get(type)).accept(event);
        }
    }

    private static final class State {
        short[] pcm;
        boolean cancelled;
        boolean muted;
        boolean disabled;
        boolean disconnected;
        boolean connected = true;
        int mutations;

        State(short[] pcm) {
            this.pcm = pcm;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, State state, VoicechatClientApi clientApi) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (target, method, args) -> {
            return switch (method.getName()) {
                case "getRawAudio" -> state.pcm;
                case "getVoicechat" -> clientApi;
                case "isCancelled" -> state.cancelled;
                case "isCancellable" -> true;
                case "isMuted" -> state.muted;
                case "isDisabled" -> state.disabled;
                case "isDisconnected" -> state.disconnected;
                case "isConnected" -> state.connected;
                case "setRawAudio", "cancel" -> {
                    state.mutations++;
                    yield null;
                }
                case "toString" -> type.getSimpleName() + " synthetic test";
                default -> throw new AssertionError("Unexpected API call " + method.getName());
            };
        });
    }
}
