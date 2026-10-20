package xyz.daikirai;

import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.common.ForgeHooks;

public final class Fixture {
    public static void run() {
        ForgeHooks.onLivingJump((EntityLivingBase) null);
    }
}
