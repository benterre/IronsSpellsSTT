package dev.sts.compat;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

public final class LegacyText {
    private LegacyText() {}
    public static Component fallback(String key,String fallback) {
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(fallback);
    }
}
