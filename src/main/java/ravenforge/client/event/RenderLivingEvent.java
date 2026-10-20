package ravenforge.client.event;

import net.minecraft.entity.EntityLivingBase;
import ravenforge.fml.common.eventhandler.Event;

public abstract class RenderLivingEvent extends Event {
    public final EntityLivingBase entity;
    public final double x;
    public final double y;
    public final double z;

    protected RenderLivingEvent(EntityLivingBase entity, double x, double y, double z) {
        this.entity = entity;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static class Pre extends RenderLivingEvent {
        public Pre(EntityLivingBase entity, double x, double y, double z) {
            super(entity, x, y, z);
        }
    }

    public static class Post extends RenderLivingEvent {
        public Post(EntityLivingBase entity, double x, double y, double z) {
            super(entity, x, y, z);
        }
    }

    public static class Specials extends RenderLivingEvent {
        protected Specials(EntityLivingBase entity, double x, double y, double z) {
            super(entity, x, y, z);
        }

        public static class Pre extends Specials {
            public Pre(EntityLivingBase entity, double x, double y, double z) {
                super(entity, x, y, z);
            }
        }

        public static class Post extends Specials {
            public Post(EntityLivingBase entity, double x, double y, double z) {
                super(entity, x, y, z);
            }
        }
    }
}
