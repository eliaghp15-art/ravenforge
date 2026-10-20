package ravenbridge;

import net.minecraft.client.entity.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ravenforge.client.event.RenderPlayerEvent;

/**
 * Forge posts these around the player renderer itself (RenderPlayer.doRender),
 * not around the whole render manager, so a cancelled Pre skips only this player.
 *
 * The descriptor is spelled out because doRender has three overloads here (the
 * typed one plus two bridges); matching by name alone would post the event three
 * times per player.
 */
@Mixin(targets = "net.minecraft.client.renderer.entity.RenderPlayer", remap = false)
public class RavenPlayerRenderMixin {
    @Inject(
            method = "doRender(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private void ravenforge$renderPlayerPre(AbstractClientPlayer player, double x, double y, double z,
                                            float yaw, float partialTicks, CallbackInfo callbackInfo) {
        if (RavenForge.post(new RenderPlayerEvent.Pre(player, partialTicks, x, y, z))) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "doRender(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V",
            at = @At("RETURN"),
            require = 0,
            remap = false)
    private void ravenforge$renderPlayerPost(AbstractClientPlayer player, double x, double y, double z,
                                             float yaw, float partialTicks, CallbackInfo callbackInfo) {
        RavenForge.post(new RenderPlayerEvent.Post(player, partialTicks, x, y, z));
    }
}
