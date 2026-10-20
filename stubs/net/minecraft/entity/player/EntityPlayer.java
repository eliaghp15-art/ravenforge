package net.minecraft.entity.player;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;

public class EntityPlayer extends EntityLivingBase {
    public ItemStack itemInUse;
    public int itemInUseCount;

    public ItemStack getHeldItem() {
        return null;
    }
}
