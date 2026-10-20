package ravenbridge;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads a Forge mod into Lunar without exposing net.minecraftforge.* to the game loader.
 *
 * The mod jar is namespaced ahead of time by {@link ravenbridge.tools.RemapMod}: every
 * net/minecraftforge/* reference in it already points at the ravenforge/* shim carried by
 * this jar. Nothing is rewritten at runtime, so this agent needs no bytecode library and
 * cannot silently fail to patch a class.
 */
public final class Agent {
    private Agent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        try {
            File self = new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File mod = agentArgs == null || agentArgs.trim().isEmpty() ? null : new File(agentArgs.trim());
            System.out.println("[RavenBridge] premain self=" + self + " mod=" + mod);
            if (mod == null || !mod.isFile()) {
                System.out.println("[RavenBridge] no mod jar supplied in the agent argument; aborting");
                return;
            }
            Thread registrar = new Thread(new Registrar(instrumentation, self, mod), "RavenBridge-Registrar");
            registrar.setDaemon(true);
            registrar.start();
        } catch (Throwable throwable) {
            System.out.println("[RavenBridge] premain failed: " + throwable);
            throwable.printStackTrace();
        }
    }

    /** Waits for Lunar's Mixin runtime, then injects the jars and registers both configs. */
    private static final class Registrar implements Runnable {
        private final Instrumentation instrumentation;
        private final File self;
        private final File mod;

        Registrar(Instrumentation instrumentation, File self, File mod) {
            this.instrumentation = instrumentation;
            this.self = self;
            this.mod = mod;
        }

        @Override
        public void run() {
            try {
                Object mixinLoader = awaitMixinLoader();
                if (mixinLoader == null) {
                    System.out.println("[RavenBridge] MixinEnvironment never appeared");
                    return;
                }
                System.out.println("[RavenBridge] Mixin loader: " + mixinLoader.getClass().getName());
                Method addUrl = mixinLoader.getClass().getMethod("addURL", URL.class);

                Path patchDir = Files.createTempDirectory("ravenbridge-mixin-");
                patchDir.toFile().deleteOnExit();
                String config = readConfig(this.mod, "clickgui.mixins.json");
                if (config == null) {
                    System.out.println("[RavenBridge] clickgui.mixins.json missing from the mod jar");
                    return;
                }
                Files.write(patchDir.resolve("clickgui.mixins.json"),
                        stripRefmap(config).getBytes(StandardCharsets.UTF_8));

                addUrl.invoke(mixinLoader, patchDir.toUri().toURL());
                addUrl.invoke(mixinLoader, this.self.toURI().toURL());
                addUrl.invoke(mixinLoader, this.mod.toURI().toURL());
                System.out.println("[RavenBridge] jars injected into the game loader");

                Class<?> mixins = Class.forName("org.spongepowered.asm.mixin.Mixins", true, mixinLoader.getClass().getClassLoader());
                Method addConfiguration = mixins.getMethod("addConfiguration", String.class);
                addConfiguration.invoke(null, "mixins.ravenbridge.json");
                addConfiguration.invoke(null, "clickgui.mixins.json");
                System.out.println("[RavenBridge] mixin configs registered");
            } catch (Throwable throwable) {
                System.out.println("[RavenBridge] registrar failed: " + throwable);
                throwable.printStackTrace();
            }
        }

        private Object awaitMixinLoader() throws InterruptedException {
            for (int i = 0; i < 6000; i++) {
                for (Class<?> loaded : this.instrumentation.getAllLoadedClasses()) {
                    if ("org.spongepowered.asm.mixin.MixinEnvironment".equals(loaded.getName()) && loaded.getClassLoader() != null) {
                        return loaded.getClassLoader();
                    }
                }
                Thread.sleep(50L);
            }
            return null;
        }

        private String readConfig(File jar, String name) {
            try (JarFile jarFile = new JarFile(jar)) {
                JarEntry entry = jarFile.getJarEntry(name);
                if (entry == null) {
                    return null;
                }
                try (InputStream stream = jarFile.getInputStream(entry)) {
                    byte[] buffer = new byte[(int) entry.getSize()];
                    int read = 0;
                    while (read < buffer.length) {
                        int step = stream.read(buffer, read, buffer.length - read);
                        if (step < 0) {
                            break;
                        }
                        read += step;
                    }
                    return new String(buffer, 0, read, StandardCharsets.UTF_8);
                }
            } catch (Throwable throwable) {
                return null;
            }
        }

        private String stripRefmap(String json) {
            String result = json.replaceAll(",\\s*\"refmap\"\\s*:\\s*\"[^\"]*\"", "");
            result = result.replaceAll("\"refmap\"\\s*:\\s*\"[^\"]*\"\\s*,", "");
            result = result.replaceAll("\"refmap\"\\s*:\\s*\"[^\"]*\"", "");
            return result;
        }
    }
}
