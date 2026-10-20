package ravenforge.fml.relauncher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ReflectionHelper {
    public static Field findField(Class<?> owner, String... names) {
        for (String name : names) {
            for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (Throwable ignored) {
                    // try the next name / superclass
                }
            }
        }
        throw new RuntimeException("Unable to find field " + String.join("/", names) + " on " + owner);
    }

    public static Method findMethod(Class<?> owner, Object instance, String[] names, Class<?>... parameters) {
        for (String name : names) {
            for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
                try {
                    Method method = type.getDeclaredMethod(name, parameters);
                    method.setAccessible(true);
                    return method;
                } catch (Throwable ignored) {
                    // try the next name / superclass
                }
            }
        }
        throw new RuntimeException("Unable to find method " + String.join("/", names) + " on " + owner);
    }

    public static <T, E> T getPrivateValue(Class<? super E> owner, E instance, String... names) {
        try {
            return (T) findField(owner, names).get(instance);
        } catch (Throwable throwable) {
            throw new RuntimeException("Unable to read " + String.join("/", names), throwable);
        }
    }

    public static <T, E> void setPrivateValue(Class<? super E> owner, E instance, T value, String... names) {
        try {
            findField(owner, names).set(instance, value);
        } catch (Throwable throwable) {
            throw new RuntimeException("Unable to write " + String.join("/", names), throwable);
        }
    }
}
