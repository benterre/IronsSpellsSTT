package dev.sts.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VolumeCategory;

/** Validate SVC wire limits before its asynchronous category registration can reach Netty. */
public final class SpellVoiceCategory {
    public static final String ID = "sts_spells";
    private SpellVoiceCategory() {}
    public static VolumeCategory create(VoicechatApi api) {
        VolumeCategory category = api.volumeCategoryBuilder().setId(ID).setName("Spell voices")
                .setDescription("Pitch and reverb incantations in a 50-block radius").build();
        validate(category);
        return category;
    }
    static void validate(VolumeCategory category) {
        if (category == null || category.getId() == null || !category.getId().matches("[a-z_]{1,16}"))
            throw new IllegalArgumentException("Invalid SVC category ID");
        bounded(category.getName(), 16, true);
        bounded(category.getDescription(), 32_767, false);
        // Translation keys are deliberately not set: those getters were added after SVC 2.5.
        int[][] icon = category.getIcon();
        if (icon != null) {
            if (icon.length != 16) throw new IllegalArgumentException("SVC icon must be 16x16");
            for (int[] row : icon) if (row == null || row.length != 16) throw new IllegalArgumentException("SVC icon must be 16x16");
        }
    }
    private static void bounded(String value, int maximum, boolean required) {
        if ((required && (value == null || value.isEmpty())) || (value != null && value.length() > maximum))
            throw new IllegalArgumentException("SVC category text exceeds its wire limit");
    }
}
