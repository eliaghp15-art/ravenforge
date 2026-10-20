package ravenbridge;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ravenforge.event.entity.EntityJoinWorldEvent;

@Mixin(targets = "net.minecraft.client.multiplayer.WorldClient", remap = false)
public class RavenWorldMixin {
    @Inject(method = "addEntityToWorld", at = @At("HEAD"), require = 0, remap = false)
    private void ravenforge$entityJoin(int entityId, Entity entity, CallbackInfo callbackInfo) {
        if (entity == null) {
            return;
        }
        RavenForge.post(new EntityJoinWorldEvent(entity, (World) (Object) this));
    }
}
