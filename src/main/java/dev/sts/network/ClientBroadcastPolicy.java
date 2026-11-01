package dev.sts.network;

import dev.sts.config.BroadcastOptions;
import java.util.UUID;

public final class ClientBroadcastPolicy {
    private static volatile SyncBroadcastPolicyPayload state;
    private ClientBroadcastPolicy() {}
    public static BroadcastOptions get(UUID owner) {
        var current = state;
        return current != null && current.owner().equals(owner) ? current.options() : BroadcastOptions.DISABLED;
    }
    public static void apply(SyncBroadcastPolicyPayload payload) { state = payload; }
    public static void clear() { state = null; }
}
