package ravenforge.fml.common.eventhandler;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reflection-based Forge event bus. Registering an object scans its class hierarchy for
 * methods annotated with {@link SubscribeEvent} that take exactly one Event parameter.
 */
public class EventBus {
    private static final class Handler {
        final Object target;
        final Method method;
        final Class<?> eventType;
        final EventPriority priority;
        final boolean receiveCanceled;

        Handler(Object target, Method method, Class<?> eventType, EventPriority priority, boolean receiveCanceled) {
            this.target = target;
            this.method = method;
            this.eventType = eventType;
            this.priority = priority;
            this.receiveCanceled = receiveCanceled;
        }
    }

    private final List<Handler> handlers = new CopyOnWriteArrayList<Handler>();

    /**
     * Sorted copy of {@link #handlers}, rebuilt only when the listener set changes.
     * LivingUpdateEvent is posted per living entity per tick, so sorting on every post
     * would allocate and sort thousands of times a second for no reason.
     */
    private volatile List<Handler> sortedHandlers;

    public void register(Object target) {
        if (target == null) {
            return;
        }
        unregister(target);
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                SubscribeEvent annotation = method.getAnnotation(SubscribeEvent.class);
                if (annotation == null || method.getParameterTypes().length != 1) {
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    // fall through; invoke may still work for public methods
                }
                handlers.add(new Handler(target, method, method.getParameterTypes()[0], annotation.priority(), annotation.receiveCanceled()));
            }
        }
        this.sortedHandlers = null;
    }

    public void unregister(Object target) {
        if (target == null) {
            return;
        }
        List<Handler> keep = new ArrayList<Handler>(handlers.size());
        for (Handler handler : handlers) {
            if (handler.target != target) {
                keep.add(handler);
            }
        }
        handlers.clear();
        handlers.addAll(keep);
        this.sortedHandlers = null;
    }

    public boolean post(Event event) {
        if (event == null) {
            return false;
        }
        List<Handler> snapshot = this.sortedHandlers;
        if (snapshot == null) {
            snapshot = new ArrayList<Handler>(handlers);
            snapshot.sort(Comparator.comparingInt(handler -> handler.priority.ordinal()));
            this.sortedHandlers = snapshot;
        }
        for (Handler handler : snapshot) {
            if (!handler.eventType.isInstance(event)) {
                continue;
            }
            if (event.isCanceled() && !handler.receiveCanceled) {
                continue;
            }
            try {
                handler.method.invoke(handler.target, event);
            } catch (Throwable throwable) {
                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                System.out.println("[RavenForge] handler threw for " + event.getClass().getName() + ": " + cause);
                cause.printStackTrace();
            }
        }
        return event.isCanceled();
    }

    public boolean hasListener(Class<? extends Event> eventType) {
        for (Handler handler : handlers) {
            if (handler.eventType == eventType) {
                return true;
            }
        }
        return false;
    }
}
