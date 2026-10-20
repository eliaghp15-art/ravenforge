package ravenforge.event.entity.living;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import ravenforge.fml.common.eventhandler.Event;

public class LivingEvent extends Event {
    public final Entity entity;

    public LivingEvent(Entity entity) {
        this.entity = entity;
    }

    public static class LivingUpdateEvent extends LivingEvent {
        public LivingUpdateEvent(Entity entity) {
            super(entity);
        }
    }

    public static class LivingJumpEvent extends LivingEvent {
        public LivingJumpEvent(EntityLivingBase entity) {
            super(entity);
        }
    }
}
