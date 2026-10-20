package org.spongepowered.asm.mixin.injection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time stand-in for Redirect; never packaged into the jar.
 *
 * Retention is RUNTIME for the same reason as Inject/At: Mixin only sees
 * visible annotations, and a CLASS-retained one would leave the handler
 * merged as a plain method with no call site.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Redirect {
    String[] method();

    At[] at();

    int require() default -1;

    int expect() default 1;

    int allow() default -1;

    boolean remap() default true;
}
