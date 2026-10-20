package ravenforge.common;

import net.minecraft.entity.EntityLivingBase;
import ravenforge.event.entity.living.LivingEvent;

public class ForgeHooks {
    public static void onLivingJump(EntityLivingBase entity) {
        MinecraftForge.EVENT_BUS.post(new LivingEvent.LivingJumpEvent(entity));
    }
}
