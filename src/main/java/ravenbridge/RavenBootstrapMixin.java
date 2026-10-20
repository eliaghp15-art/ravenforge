package ravenbridge;

import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ravenforge.client.event.GuiOpenEvent;

@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public class RavenBootstrapMixin {
    @Inject(method = "startGame", at = @At("RETURN"), require = 0, remap = false)
    private void ravenforge$bootstrap(CallbackInfo callbackInfo) {
        System.out.println("[RavenForge] startGame hook reached");
        RavenForge.bootstrap();
    }

    /**
     * Forge posts GuiOpenEvent here and returns early when it is cancelled, which is
     * how a module can refuse to open chat or the pause menu. Cancelling the inject
     * reproduces that return; the event also carries the screen so listeners can see
     * which GUI was requested.
     */
    @Inject(method = "displayGuiScreen", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void ravenforge$displayGuiScreen(GuiScreen gui, CallbackInfo callbackInfo) {
        if (RavenForge.post(new GuiOpenEvent(gui))) {
            callbackInfo.cancel();
        }
    }
}
