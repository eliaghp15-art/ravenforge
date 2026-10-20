package org.spongepowered.asm.mixin.injection.callback;

/** Compile-time stand-in for CallbackInfo; never packaged into the jar. */
public class CallbackInfo {
    public void cancel() {
    }

    public boolean isCancelled() {
        return false;
    }
}
