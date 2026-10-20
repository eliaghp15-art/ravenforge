package ravenforge.client.event;

import ravenforge.fml.common.eventhandler.Event;

public class MouseEvent extends Event {
    public int x;
    public int y;
    public int dx;
    public int dy;
    public int button;
    public boolean buttonstate;
    public int dwheel;

    public MouseEvent() {
    }
}
