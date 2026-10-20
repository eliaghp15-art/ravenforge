package ravenforge.client.event;

import ravenforge.fml.common.eventhandler.Event;

public class RenderWorldLastEvent extends Event {
    public final float partialTicks;

    public RenderWorldLastEvent(float partialTicks) {
        this.partialTicks = partialTicks;
    }

    public float getPartialTicks() {
        return this.partialTicks;
    }
}
