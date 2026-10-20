package ravenforge.common;

import ravenforge.fml.common.eventhandler.EventBus;

public class MinecraftForge {
    public static final EventBus EVENT_BUS = new EventBus();
    public static final EventBus TERRAIN_GEN_BUS = new EventBus();
    public static final EventBus ORE_GEN_BUS = new EventBus();
}
