package ravenbridge;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ravenforge.client.event.DrawBlockHighlightEvent;

/**
 * Forge posts this immediately before the selection box is drawn, and only on frames
 * that have a block under the crosshair - the vanilla draw call is already inside that
 * guard. Hooking the draw method itself matches both facts, and cancelling skips the
 * outline exactly as Forge's `if (!onDrawBlockHighlight(...))` does.
 *
 * Hooking here rather than redirecting the two call sites also keeps our hands off
 * instructions Lunar may inject at; removing a call site is what broke the 21:13 bake.
 */
@Mixin(targets = "net.minecraft.client.renderer.RenderGlobal", remap = false)
public class RavenBlockHighlightMixin {
    @Inject(method = "drawSelectionBox", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void ravenforge$drawBlockHighlight(EntityPlayer player, MovingObjectPosition target,
                                               int subID, float partialTicks, CallbackInfo callbackInfo) {
        ItemStack held = player == null ? null : player.getHeldItem();
        if (RavenForge.post(new DrawBlockHighlightEvent((RenderGlobal) (Object) this, player, target,
                subID, held, partialTicks))) {
            callbackInfo.cancel();
        }
    }
}
