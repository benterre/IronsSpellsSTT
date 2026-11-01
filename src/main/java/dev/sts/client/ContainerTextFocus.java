package dev.sts.client;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** JEI's focus strategy also checks fields not registered as the screen's focused child. */
final class ContainerTextFocus {
    private static final ClassValue<List<Field>> FIELDS = new ClassValue<>() {
        @Override protected List<Field> computeValue(Class<?> screenClass) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> type = screenClass; type != null && Screen.class.isAssignableFrom(type); type = type.getSuperclass())
                for (Field field : type.getDeclaredFields())
                    if (EditBox.class.isAssignableFrom(field.getType()) && field.trySetAccessible()) fields.add(field);
            return List.copyOf(fields);
        }
    };
    private ContainerTextFocus() {}
    static boolean isTyping(Screen screen) {
        if (screen.getFocused() instanceof EditBox edit && edit.isActive() && edit.isFocused()) return true;
        if (screen.children().stream().anyMatch(child -> child instanceof EditBox edit && edit.isActive() && edit.isFocused())) return true;
        for (Field field : FIELDS.get(screen.getClass())) {
            try {
                if (field.get(screen) instanceof EditBox edit && edit.isActive() && edit.isFocused()) return true;
            } catch (IllegalAccessException ignored) { /* An inaccessible field cannot take the shortcut. */ }
        }
        return false;
    }
}
