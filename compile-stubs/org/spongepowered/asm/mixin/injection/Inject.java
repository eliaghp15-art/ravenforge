package org.spongepowered.asm.mixin.injection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compile-time stand-in for Inject; never packaged into the jar. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Inject {
    String[] method();

    At[] at();

    boolean cancellable() default false;

    int require() default -1;

    int expect() default 1;

    int allow() default -1;

    boolean remap() default true;
}
