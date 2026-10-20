package ravenbridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import ravenforge.fml.common.eventhandler.Event;
import ravenforge.fml.common.eventhandler.EventBus;

/** Runtime side of the shim: initialises the Forge mod after Minecraft is up. */
public final class RavenForge {
    private static boolean initialised;
    private static boolean running;
    private static boolean hooksReported;
    private static final Set<String> reported = new HashSet<String>();
    private static Field timerField;
    private static Field partialTicksField;

    static {
        // Reached two ways: our own @Inject on Minecraft.startGame (when the mixin
        // injector attaches) and the agent's bootstrap hook, which runs at the same
        // RETURN via -Dlunar.agent.bootstrap.mods=ravenbridge.RavenForge. bootstrap()
        // is idempotent, so whichever gets here first wins and the other is a no-op.
        bootstrap();
    }

    /** Public so the agent's mod loader can instantiate it cleanly; holds no state. */
    public RavenForge() {
    }

    public static void bootstrap() {
        // `initialised` is only set on success: bootstrap can be reached from three places
        // (mixin startGame, the agent property, and class-init from an early GUI post), and a
        // failure in the first should not permanently disable the later ones.
        if (initialised || running) {
            return;
        }
        running = true;
        try {
            // Scripts need a javac-readable bake; building it now keeps the cost off the
            // first world tick, where ScriptManager.reload() runs on the client thread.
            ravenforge.script.ScriptCompiler.prepareBakeInBackground();
        } catch (Throwable throwable) {
            System.out.println("[RavenForge] bake warmup could not start: " + throwable);
        }
        try {
            Class<?> modClass = Class.forName("xyz.daikirai.ClickGuiMod", true, RavenForge.class.getClassLoader());
            Object instance = modClass.getDeclaredConstructor().newInstance();
            Class<?> initEvent = Class.forName("ravenforge.fml.common.event.FMLInitializationEvent", true, RavenForge.class.getClassLoader());
            Method init = modClass.getMethod("init", initEvent);
            init.invoke(instance, new Object[]{null});
            initialised = true;
            System.out.println("[RavenForge] ClickGuiMod.init completed");
        } catch (Throwable throwable) {
            Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
            System.out.println("[RavenForge] ClickGuiMod.init failed: " + cause);
            cause.printStackTrace();
        } finally {
            running = false;
        }
    }

    /**
     * Posts an event without letting a bad subscriber take the game down. The bus already
     * isolates individual handlers; this also covers event construction and dispatch edge
     * cases, and reports each distinct failure once so a broken hook is visible but harmless.
     */
    public static boolean post(Event event) {
        if (event == null) {
            return false;
        }
        try {
            EventBus bus = ravenforge.common.MinecraftForge.EVENT_BUS;
            if (bus == null) {
                return false;
            }
            return bus.post(event);
        } catch (Throwable throwable) {
            String key = event.getClass().getName();
            if (reported.add(key)) {
                System.out.println("[RavenForge] failed to post " + key + ": " + throwable);
                throwable.printStackTrace();
            }
            return false;
        }
    }

    /** Proves the tick injectors attached: only a called handler can print this. */
    public static void noteTickHook() {
        if (hooksReported) {
            return;
        }
        hooksReported = true;
        System.out.println("[RavenForge] mixin tick hooks are live");
    }

    /** Reads RenderTickEvent's partial tick value without compiling against Minecraft. */
    public static float partialTicks() {
        try {
            if (timerField == null) {
                Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft");
                Object mc = minecraft.getMethod("getMinecraft").invoke(null);
                timerField = minecraft.getField("timer");
                Object timer = timerField.get(mc);
                partialTicksField = timer.getClass().getField("renderPartialTicks");
                return partialTicksField.getFloat(timer);
            }
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft");
            Object mc = minecraft.getMethod("getMinecraft").invoke(null);
            Object timer = timerField.get(mc);
            return partialTicksField.getFloat(timer);
        } catch (Throwable ignored) {
            return 0.0F;
        }
    }
}
