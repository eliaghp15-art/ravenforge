package ravenforge.event.entity.player;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import ravenforge.fml.common.eventhandler.Event;

public class AttackEntityEvent extends Event {
    public final EntityPlayer entityPlayer;
    public final Entity target;

    public AttackEntityEvent(EntityPlayer entityPlayer, Entity target) {
        this.entityPlayer = entityPlayer;
        this.target = target;
    }
}
