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

/** Iron 2.x/3.0 have the registry API but no mana-modifying completion event. */
@Pseudo
@Mixin(targets="io.redspace.ironsspellbooks.api.spells.AbstractSpell",remap=false)
abstract class ModernCastMixin {
    @Inject(method="castSpell",at=@At("HEAD"),cancellable=true,remap=false)
    private void sts$fundPulse(Level world,int level,ServerPlayer player,@Coerce Object source,boolean cooldown,CallbackInfo info) {
        if(!IronAccess.CAST_EVENT&&!IronAccess.beforeNativeCast(this,level,player)) info.cancel();
    }
    @Inject(method="castSpell",at=@At("RETURN"),remap=false)
    private void sts$completed(Level world,int level,ServerPlayer player,@Coerce Object source,boolean cooldown,CallbackInfo info) {
        if(!IronAccess.CAST_EVENT) IronAccess.afterNativeCast(this,level,player);
    }
}
