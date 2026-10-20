package ravenforge.client.event;

import net.minecraft.client.gui.GuiScreen;
import ravenforge.fml.common.eventhandler.Event;

public class GuiScreenEvent extends Event {
    public final GuiScreen gui;

    public GuiScreenEvent(GuiScreen gui) {
        this.gui = gui;
    }

    public static class MouseInputEvent extends GuiScreenEvent {
        public final int mouseX;
        public final int mouseY;

        public MouseInputEvent(GuiScreen gui, int mouseX, int mouseY) {
            super(gui);
            this.mouseX = mouseX;
            this.mouseY = mouseY;
        }

        public static class Pre extends MouseInputEvent {
            public Pre(GuiScreen gui, int mouseX, int mouseY) {
                super(gui, mouseX, mouseY);
            }
        }

        public static class Post extends MouseInputEvent {
            public Post(GuiScreen gui, int mouseX, int mouseY) {
                super(gui, mouseX, mouseY);
            }
        }
    }

    public static class KeyboardInputEvent extends GuiScreenEvent {
        public final char typedChar;
        public final int keyCode;

        public KeyboardInputEvent(GuiScreen gui, char typedChar, int keyCode) {
            super(gui);
            this.typedChar = typedChar;
            this.keyCode = keyCode;
        }

        public static class Pre extends KeyboardInputEvent {
            public Pre(GuiScreen gui, char typedChar, int keyCode) {
                super(gui, typedChar, keyCode);
            }
        }

        public static class Post extends KeyboardInputEvent {
            public Post(GuiScreen gui, char typedChar, int keyCode) {
                super(gui, typedChar, keyCode);
            }
        }
    }
}
