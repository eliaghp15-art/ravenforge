package ravenbridge;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ravenforge.client.event.RenderWorldLastEvent;

@Mixin(targets = "net.minecraft.client.renderer.EntityRenderer", remap = false)
public class RavenRenderMixin {
    /**
     * RenderWorldLastEvent belongs inside the 3D pass, immediately before the hand is
     * drawn - the point where Forge dispatches it and where Lunar hooks its own
     * renderWorldLast. Posting at renderWorld's RETURN (the previous anchor) ran after
     * the camera transform had been unwound, so ESP boxes, tracers and nametags were
     * drawn against the wrong transform: shifted, scaled or flickering.
     *
     * There are two hand paths and only one runs per frame - vanilla's private
     * renderHand(FI) when shaders are off, OptiFine's renderHand1 when they are on - so
     * each gets an injector. Posting from only the first is why ESP would disappear the
     * moment shaders were enabled.
     */
    @Inject(
            method = "renderWorldPass",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;renderHand(FI)V", shift = At.Shift.BEFORE),
            require = 0,
            remap = false)
    private void ravenforge$renderWorldLast(int pass, float partialTicks, long finishTimeNano,
                                            CallbackInfo callbackInfo) {
        RavenForge.post(new RenderWorldLastEvent(partialTicks));
    }

    @Inject(
            method = "renderWorldPass",
            at = @At(value = "INVOKE", target = "Lnet/optifine/shaders/ShadersRender;renderHand1(Lnet/minecraft/client/renderer/EntityRenderer;FI)V", shift = At.Shift.BEFORE),
            require = 0,
            remap = false)
    private void ravenforge$renderWorldLastShaders(int pass, float partialTicks, long finishTimeNano,
                                                   CallbackInfo callbackInfo) {
        RavenForge.post(new RenderWorldLastEvent(partialTicks));
    }
}
