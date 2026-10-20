package ravenbridge;

import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ravenforge.client.event.MouseEvent;
import ravenforge.fml.common.gameevent.TickEvent;

@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public class RavenTickMixin {
    @Inject(method = "runTick", at = @At("HEAD"), require = 0, remap = false)
    private void ravenforge$clientTickStart(CallbackInfo callbackInfo) {
        RavenForge.noteTickHook();
        RavenForge.post(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
    }

    @Inject(method = "runTick", at = @At("RETURN"), require = 0, remap = false)
    private void ravenforge$clientTickEnd(CallbackInfo callbackInfo) {
        RavenForge.post(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
    }

    /**
     * RenderTick must straddle the frame, not the whole loop iteration.
     *
     * Forge posts START immediately before `updateCameraAndRender` and END immediately
     * after it, which is *before* `Display.update()` swaps buffers. Posting END at
     * runGameLoop's RETURN put it after the swap: Raven's HUD (InfoHud.render) and the
     * 2D overlays draw on the END phase, so they were rendered into a frame that had
     * already been presented - the HUD simply never appeared.
     *
     * Lunar injects its own onRenderTick PRE/POST around this same call, which is a
     * useful confirmation that this is the frame boundary.
     */
    @Inject(
            method = "runGameLoop",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;updateCameraAndRender(FJ)V", shift = At.Shift.BEFORE),
            require = 0,
            remap = false)
    private void ravenforge$renderTickStart(CallbackInfo callbackInfo) {
        RavenForge.post(new TickEvent.RenderTickEvent(TickEvent.Phase.START, RavenForge.partialTicks()));
    }

    @Inject(
            method = "runGameLoop",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;updateCameraAndRender(FJ)V", shift = At.Shift.AFTER),
            require = 0,
            remap = false)
    private void ravenforge$renderTickEnd(CallbackInfo callbackInfo) {
        RavenForge.post(new TickEvent.RenderTickEvent(TickEvent.Phase.END, RavenForge.partialTicks()));
    }

    /**
     * Forge posts MouseEvent from inside Minecraft's raw-mouse loop and does
     * `continue` when a subscriber cancels, so a cancelled click never reaches
     * vanilla input handling.
     *
     * Redirecting the loop condition is what makes that possible: on cancel this
     * consumes the event (Mouse.next() again) without returning to the loop body,
     * which is exactly `continue`. An @Inject here could only have cancelled the
     * whole tick, and posting after Mouse.next() without redirecting - the previous
     * approach - left vanilla clicks untouched.
     *
     * Pure movement events (no button, no wheel) are not posted: nothing in the mod
     * reads them, and inventing work for them would cost a reflective dispatch per
     * mouse sample.
     */
    @Redirect(
            method = "runTick",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;next()Z", ordinal = 0),
            require = 0,
            remap = false)
    private boolean ravenforge$mouseInput() {
        boolean hasNext = Mouse.next();
        while (hasNext) {
            int button = Mouse.getEventButton();
            int wheel = Mouse.getEventDWheel();
            if (button < 0 && wheel == 0) {
                return true;
            }
            if (!RavenForge.post(build(button, wheel))) {
                return true;
            }
            hasNext = Mouse.next();
        }
        return false;
    }

    private static MouseEvent build(int button, int wheel) {
        MouseEvent event = new MouseEvent();
        event.x = Mouse.getEventX();
        event.y = Mouse.getEventY();
        event.dx = Mouse.getEventDX();
        event.dy = Mouse.getEventDY();
        event.button = button;
        event.buttonstate = Mouse.getEventButtonState();
        event.dwheel = wheel;
        return event;
    }
}
