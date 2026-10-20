package org.lwjgl.input;

/** Compile-time stand-in for LWJGL's Mouse; the game supplies the real one at runtime. */
public final class Mouse {
    private Mouse() {
    }

    public static boolean isCreated() {
        throw new UnsupportedOperationException("stub");
    }

    public static boolean next() {
        throw new UnsupportedOperationException("stub");
    }

    public static int getEventButton() {
        throw new UnsupportedOperationException("stub");
    }

    public static boolean getEventButtonState() {
        throw new UnsupportedOperationException("stub");
    }

    public static int getEventX() {
        throw new UnsupportedOperationException("stub");
    }

    public static int getEventY() {
        throw new UnsupportedOperationException("stub");
    }

    public static int getEventDX() {
        throw new UnsupportedOperationException("stub");
    }

    public static int getEventDY() {
        throw new UnsupportedOperationException("stub");
    }

    public static int getEventDWheel() {
        throw new UnsupportedOperationException("stub");
    }
}
