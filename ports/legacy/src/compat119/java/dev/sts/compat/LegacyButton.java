package dev.sts.compat;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Adapts the same widget construction to the pre-1.19.3 button API. */
public final class LegacyButton extends Button {
    private LegacyButton(int x,int y,int width,int height,Component label,OnPress action) {
        super(x,y,width,height,label,action);
    }
    public static Builder builder(Component label,OnPress action) { return new Builder(label,action); }
    public static final class Builder {
        private final Component label;
        private final OnPress action;
        private int x,y,width,height;
        private Builder(Component label,OnPress action) { this.label=label; this.action=action; }
        public Builder bounds(int x,int y,int width,int height) { this.x=x; this.y=y; this.width=width; this.height=height; return this; }
        public LegacyButton build() { return new LegacyButton(x,y,width,height,label,action); }
    }
}
