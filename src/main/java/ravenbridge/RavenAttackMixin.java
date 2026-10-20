package ravenbridge;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ravenforge.event.entity.player.AttackEntityEvent;

@Mixin(targets = "net.minecraft.entity.player.EntityPlayer", remap = false)
public class RavenAttackMixin {
    @Inject(method = "attackTargetEntityWithCurrentItem", at = @At("HEAD"), require = 0, remap = false)
    private void ravenforge$attack(Entity target, CallbackInfo callbackInfo) {
        if (target == null) {
            return;
        }
        RavenForge.post(new AttackEntityEvent((EntityPlayer) (Object) this, target));
    }
}
