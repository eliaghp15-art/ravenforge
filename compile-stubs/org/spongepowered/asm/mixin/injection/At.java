package org.spongepowered.asm.mixin.injection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time stand-in for At; never packaged into the jar.
 *
 * Retention must be RUNTIME: Mixin reads injector annotations through
 * Annotations.getSingleVisible, so a CLASS-retained annotation is invisible to it
 * and the handler is merged into the target as an ordinary method with no call site.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.ANNOTATION_TYPE, ElementType.METHOD})
public @interface At {
    String value();

    String target() default "";

    String[] args() default {};

    int ordinal() default -1;

    Shift shift() default Shift.NONE;

    enum Shift {
        NONE, BEFORE, AFTER, BY
    }
}
