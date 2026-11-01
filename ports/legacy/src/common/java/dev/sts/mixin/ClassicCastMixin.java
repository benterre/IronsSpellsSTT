package dev.sts.mixin;

import dev.sts.compat.IronAccess;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Old Iron has no cast event; hook its real mana-consuming completion rather than a timer. */
@Pseudo
@Mixin(targets="io.redspace.ironsspellbooks.spells.AbstractSpell",remap=false)
abstract class ClassicCastMixin {
    @Inject(method="castSpell",at=@At("HEAD"),cancellable=true,remap=false)
    private void sts$fundPulse(Level world,ServerPlayer player,@Coerce Object source,boolean cooldown,CallbackInfo info) {
        if(!IronAccess.beforeClassicCast(this,player)) info.cancel();
    }
    @Inject(method="castSpell",at=@At("RETURN"),remap=false)
    private void sts$completed(Level world,ServerPlayer player,@Coerce Object source,boolean cooldown,CallbackInfo info) {
        IronAccess.afterClassicCast(this,player);
    }
}
