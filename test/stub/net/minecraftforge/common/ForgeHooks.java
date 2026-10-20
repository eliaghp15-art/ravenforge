package net.minecraftforge.common;

import net.minecraft.entity.EntityLivingBase;

public class ForgeHooks {
    public static void onLivingJump(EntityLivingBase entity) {
        throw new AssertionError("STUB-CALLED (remap failed)");
    }
}
