package dev.sts.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small rendering boundary for Minecraft's older PoseStack GUI; no textures or retained audio. */
public final class GuiGraphics {
    private final PoseStack pose;
    private final Screen owner;
    public GuiGraphics(PoseStack pose,Screen owner) { this.pose=pose; this.owner=owner; }
    public void fill(int x1,int y1,int x2,int y2,int color) { GuiComponent.fill(pose,x1,y1,x2,y2,color); }
    public void drawString(Font font,String text,int x,int y,int color,boolean shadow) {
        if (shadow) font.drawShadow(pose,text,x,y,color); else font.draw(pose,text,x,y,color);
    }
    public void drawString(Font font,Component text,int x,int y,int color,boolean shadow) { drawString(font,text.getString(),x,y,color,shadow); }
    public void drawCenteredString(Font font,Component text,int x,int y,int color) { drawString(font,text,x-font.width(text)/2,y,color,true); }
    public void drawWordWrap(Font font,Component text,int x,int y,int width,int color) {
        for (var line : font.split(text,width)) { font.draw(pose,line,x,y,color); y+=font.lineHeight; }
    }
    public void renderTooltip(Font font,Component text,int x,int y) { owner.renderTooltip(pose,text,x,y); }
}
