package ravenforge.client.event;

import net.minecraft.client.gui.GuiScreen;
import ravenforge.fml.common.eventhandler.Event;

public class GuiOpenEvent extends Event {
    public GuiScreen gui;

    public GuiOpenEvent(GuiScreen gui) {
        this.gui = gui;
    }
}
