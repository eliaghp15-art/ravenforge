package net.minecraft.network.play.server;

import net.minecraft.util.IChatComponent;

/** Compile-time stand-in for the real packet; never packaged into the jar. */
public class S02PacketChat {
    public IChatComponent getChatComponent() {
        throw new UnsupportedOperationException("stub");
    }

    public byte getType() {
        throw new UnsupportedOperationException("stub");
    }
}
