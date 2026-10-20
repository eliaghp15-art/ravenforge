package ravenbridge;

import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ravenforge.event.entity.living.LivingEvent;

@Mixin(targets = "net.minecraft.entity.EntityLivingBase", remap = false)
public class RavenLivingMixin {
    @Inject(method = "onLivingUpdate", at = @At("HEAD"), require = 0, remap = false)
    private void ravenforge$livingUpdate(CallbackInfo callbackInfo) {
        RavenForge.post(new LivingEvent.LivingUpdateEvent((EntityLivingBase) (Object) this));
    }
}
