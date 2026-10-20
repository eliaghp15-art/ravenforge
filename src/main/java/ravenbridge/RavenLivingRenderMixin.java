package ravenbridge;

import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ravenforge.client.event.RenderLivingEvent;

/**
 * RenderLivingEvent.Specials wraps nametag/special layer drawing
 * (RendererLivingEntity.renderName), which is where Forge posts it. Cancelling
 * Pre hides the vanilla nametag, which is what the nametag/ESP modules need.
 */
@Mixin(targets = "net.minecraft.client.renderer.entity.RendererLivingEntity", remap = false)
public class RavenLivingRenderMixin {
    @Inject(
            method = "renderName(Lnet/minecraft/entity/EntityLivingBase;DDD)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private void ravenforge$specialsPre(EntityLivingBase entity, double x, double y, double z,
                                        CallbackInfo callbackInfo) {
        if (RavenForge.post(new RenderLivingEvent.Specials.Pre(entity, x, y, z))) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "renderName(Lnet/minecraft/entity/EntityLivingBase;DDD)V",
            at = @At("RETURN"),
            require = 0,
            remap = false)
    private void ravenforge$specialsPost(EntityLivingBase entity, double x, double y, double z,
                                         CallbackInfo callbackInfo) {
        RavenForge.post(new RenderLivingEvent.Specials.Post(entity, x, y, z));
    }
}
