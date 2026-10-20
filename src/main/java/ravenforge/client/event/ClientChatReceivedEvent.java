package ravenforge.client.event;

import net.minecraft.util.IChatComponent;
import ravenforge.fml.common.eventhandler.Event;

public class ClientChatReceivedEvent extends Event {
    public IChatComponent message;
    public byte type;

    public ClientChatReceivedEvent(IChatComponent message, byte type) {
        this.message = message;
        this.type = type;
    }

    public IChatComponent getMessage() {
        return this.message;
    }
}
