package ravenforge.script;

import java.util.HashMap;
import java.util.Map;

/**
 * Stands in for net.minecraft.launchwrapper.Launch, which Lunar does not have.
 *
 * Script.compile reads {@code Launch.blackboard} to pick its classpath branch and uses
 * {@code Launch.classLoader} as the parent of the loader that instantiates script classes.
 * Both are field accesses, so the build-time rewriter can point them here instead of
 * shipping a fake net.minecraft.launchwrapper package into a game that has no LaunchWrapper.
 */
public final class LaunchShim {
    public static final Map<String, Object> blackboard = new HashMap<String, Object>();

    public static final ClassLoader classLoader = LaunchShim.class.getClassLoader();

    static {
        // false selects Script.compile's "-classpath" branch, the one our compiler can
        // honour: under Lunar the game's classes are not ambient, they are in the bake.
        blackboard.put("fml.deobfuscatedEnvironment", Boolean.FALSE);
    }

    private LaunchShim() {
    }
}
