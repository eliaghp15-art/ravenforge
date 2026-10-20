package ravenbridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ravenforge.client.event.GuiScreenEvent;

/**
 * Fires GuiScreenEvent.MouseInputEvent / KeyboardInputEvent once per buffered input
 * event, which Forge does by wrapping the handler calls inside GuiScreen.handleInput's
 * Mouse.next()/Keyboard.next() loops.
 *
 * This deliberately hooks the handler methods themselves rather than redirecting the
 * calls. An @Redirect there deletes the `handleMouseInput()` instruction, and Lunar's
 * own mixin injects at exactly that instruction (lunar$overwriteGuiScroll) - the bake
 * then fails with "Scanned 0 target(s)" and the game dies before the main menu. Hooking
 * the callee is equivalent for our purposes: cancelling returns from that one event's
 * handling, which is Forge's `continue`, without removing anything Lunar needs.
 */
@Mixin(targets = "net.minecraft.client.gui.GuiScreen", remap = false)
public class RavenGuiInputMixin {
    @Inject(method = "handleMouseInput", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void ravenforge$mousePre(CallbackInfo callbackInfo) {
        GuiScreen screen = (GuiScreen) (Object) this;
        if (RavenForge.post(new GuiScreenEvent.MouseInputEvent.Pre(screen, Mouse.getEventX(), Mouse.getEventY()))) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleMouseInput", at = @At("RETURN"), require = 0, remap = false)
    private void ravenforge$mousePost(CallbackInfo callbackInfo) {
        GuiScreen screen = (GuiScreen) (Object) this;
        if (stillOpen(screen)) {
            RavenForge.post(new GuiScreenEvent.MouseInputEvent.Post(screen, Mouse.getEventX(), Mouse.getEventY()));
        }
    }

    @Inject(method = "handleKeyboardInput", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void ravenforge$keyboardPre(CallbackInfo callbackInfo) {
        GuiScreen screen = (GuiScreen) (Object) this;
        if (RavenForge.post(new GuiScreenEvent.KeyboardInputEvent.Pre(screen,
                Keyboard.getEventCharacter(), Keyboard.getEventKey()))) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleKeyboardInput", at = @At("RETURN"), require = 0, remap = false)
    private void ravenforge$keyboardPost(CallbackInfo callbackInfo) {
        GuiScreen screen = (GuiScreen) (Object) this;
        if (stillOpen(screen)) {
            RavenForge.post(new GuiScreenEvent.KeyboardInputEvent.Post(screen,
                    Keyboard.getEventCharacter(), Keyboard.getEventKey()));
        }
    }

    /** Forge only posts the Post event if handling did not already close the screen. */
    private static boolean stillOpen(GuiScreen screen) {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft != null && minecraft.currentScreen == screen;
    }
}
