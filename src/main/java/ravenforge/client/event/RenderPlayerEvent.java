package ravenforge.client.event;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import ravenforge.fml.common.eventhandler.Event;

public abstract class RenderPlayerEvent extends Event {
    public final Entity entity;
    public final EntityPlayer player;
    public final float partialRenderTick;
    public final double x;
    public final double y;
    public final double z;

    protected RenderPlayerEvent(EntityPlayer player, float partialRenderTick, double x, double y, double z) {
        this.entity = player;
        this.player = player;
        this.partialRenderTick = partialRenderTick;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static class Pre extends RenderPlayerEvent {
        public Pre(EntityPlayer player, float partialRenderTick, double x, double y, double z) {
            super(player, partialRenderTick, x, y, z);
        }
    }

    public static class Post extends RenderPlayerEvent {
        public Post(EntityPlayer player, float partialRenderTick, double x, double y, double z) {
            super(player, partialRenderTick, x, y, z);
        }
    }
}
