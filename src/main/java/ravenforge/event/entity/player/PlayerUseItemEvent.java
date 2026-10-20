package ravenforge.event.entity.player;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import ravenforge.fml.common.eventhandler.Event;

public class PlayerUseItemEvent extends Event {
    public final EntityPlayer entityPlayer;
    public final ItemStack item;
    public final int duration;

    public PlayerUseItemEvent(EntityPlayer entityPlayer, ItemStack item, int duration) {
        this.entityPlayer = entityPlayer;
        this.item = item;
        this.duration = duration;
    }

    public static class Start extends PlayerUseItemEvent {
        public Start(EntityPlayer entityPlayer, int duration, ItemStack item) {
            super(entityPlayer, item, duration);
        }
    }

    public static class Stop extends PlayerUseItemEvent {
        public Stop(EntityPlayer entityPlayer, int duration, ItemStack item) {
            super(entityPlayer, item, duration);
        }
    }

    public static class Finish extends PlayerUseItemEvent {
        public Finish(EntityPlayer entityPlayer, int duration, ItemStack item) {
            super(entityPlayer, item, duration);
        }
    }

    public static class Tick extends PlayerUseItemEvent {
        public Tick(EntityPlayer entityPlayer, int duration, ItemStack item) {
            super(entityPlayer, item, duration);
        }
    }
}
