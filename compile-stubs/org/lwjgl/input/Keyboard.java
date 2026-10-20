package org.lwjgl.input;

/** Compile-time stand-in for LWJGL's Keyboard; the game supplies the real one at runtime. */
public final class Keyboard {
    private Keyboard() {
    }

    public static boolean isCreated() {
        throw new UnsupportedOperationException("stub");
    }

    public static boolean next() {
        throw new UnsupportedOperationException("stub");
    }

    public static char getEventCharacter() {
        throw new UnsupportedOperationException("stub");
    }

    public static int getEventKey() {
        throw new UnsupportedOperationException("stub");
    }

    public static boolean getEventKeyState() {
        throw new UnsupportedOperationException("stub");
    }
}
