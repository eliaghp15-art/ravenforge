package ravenbridge;

import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ravenforge.event.entity.player.PlayerUseItemEvent;

/**
 * Forge posts PlayerUseItemEvent.Finish from inside EntityPlayer.onItemUseFinish,
 * while itemInUse still points at the stack being consumed. Posting at HEAD keeps
 * that stack available; by RETURN the field has been cleared.
 */
@Mixin(targets = "net.minecraft.entity.player.EntityPlayer", remap = false)
public class RavenUseItemMixin {
    @Inject(method = "onItemUseFinish", at = @At("HEAD"), require = 0, remap = false)
    private void ravenforge$useItemFinish(CallbackInfo callbackInfo) {
        EntityPlayer player = (EntityPlayer) (Object) this;
        if (player.itemInUse == null) {
            return;
        }
        RavenForge.post(new PlayerUseItemEvent.Finish(player, player.itemInUseCount, player.itemInUse));
    }
}
