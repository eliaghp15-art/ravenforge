package ravenforge.script;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Supplies the JavaCompiler Raven's script system expects, and the classpath it needs.
 *
 * Under Forge the game runs on whatever JDK the player launched with, so
 * {@code ToolProvider.getSystemJavaCompiler()} usually works. Lunar runs on a bundled
 * JRE whose module set has no jdk.compiler, so that call returns null and
 * ScriptManager.reload() returns at its first guard - scripts never load and nothing
 * is logged. This class is what the build-time patch substitutes for that call.
 */
public final class ScriptCompiler {
    private static JavaCompiler compiler;
    private static boolean reported;
    private static boolean classpathLogged;

    private ScriptCompiler() {
    }

    /** Replacement for ToolProvider.getSystemJavaCompiler(); never returns null. */
    public static synchronized JavaCompiler get() {
        if (compiler != null) {
            return compiler;
        }
        JavaCompiler system = null;
        try {
            system = ToolProvider.getSystemJavaCompiler();
        } catch (Throwable ignored) {
            // A JRE without jdk.compiler can also throw here rather than return null.
        }
        if (system != null) {
            compiler = system;
            report("using the JVM's own compiler");
        } else {
            compiler = new JavacProcessCompiler();
            // The path is resolved on the first compile: probing it here would spawn a
            // process per candidate during ScriptManager's static initialiser.
            report("this JVM has no compiler module; scripts will be compiled by an external javac");
        }
        return compiler;
    }

    /**
     * Warms the javac-readable bake on a daemon thread during client startup.
     *
     * The repack touches a few thousand classes, and the first ScriptManager.reload() runs
     * on the client thread at the first world tick; doing it here means the work happens
     * while the player is still on the main menu instead of hitching the first frame.
     */
    public static void prepareBakeInBackground() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    compileBakeJar();
                } catch (Throwable throwable) {
                    System.out.println("[RavenForge] bake warmup failed: " + throwable);
                }
            }
        }, "RavenForge-BakeWarmup");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Replacement for ScriptManager.ownJar(), which returns "" when the mod jar's
     * CodeSource cannot be read - and reload() treats an empty classpath as "nothing to do"
     * and returns without logging. This never returns an empty string.
     */
    public static String classpath() {
        String mod = modJar();
        if (mod != null) {
            return mod;
        }
        String own = ownJar();
        if (own != null) {
            return own;
        }
        String property = System.getProperty("java.class.path");
        if (property != null && !property.isEmpty()) {
            return property;
        }
        return System.getProperty("java.io.tmpdir", ".");
    }

    /** The mod jar scripts compile against, or null when it cannot be located. */
    static String modJar() {
        String path = locationOf("xyz.daikirai.script.ScriptDefaults");
        return path != null && new File(path).isFile() ? path : null;
    }

    /** This agent's jar, which carries the ravenforge.* types ScriptDefaults may expose. */
    static String ownJar() {
        String path = locationOf("ravenforge.script.ScriptCompiler");
        return path != null && new File(path).isFile() ? path : null;
    }

    private static String locationOf(String className) {
        try {
            Class<?> type = Class.forName(className, false, ScriptCompiler.class.getClassLoader());
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            String path = source.getLocation().toURI().getPath();
            if (path == null || path.isEmpty()) {
                return null;
            }
            path = URLDecoder.decode(path, "UTF-8");
            return new File(path).getAbsolutePath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void report(String message) {
        if (reported) {
            return;
        }
        reported = true;
        System.out.println("[RavenForge] script compiler: " + message);
    }

    /**
     * Classpath for javac: the mod jar, this agent jar, and the baked game classes.
     * The bake is what makes scripts able to name net.minecraft types at all - under
     * Lunar the game classes exist only inside the loader, not on disk, except for the
     * bake cache.
     */
    static List<String> compileClasspath(String extra) {
        List<String> entries = new ArrayList<String>();
        add(entries, extra);
        add(entries, modJar());
        add(entries, ownJar());
        add(entries, compileBakeJar());
        if (!classpathLogged) {
            classpathLogged = true;
            System.out.println("[RavenForge] script classpath: " + entries);
        }
        return entries;
    }

    private static void add(List<String> entries, String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return;
        }
        // ScriptManager passes one joined -classpath string; splitting keeps the log honest.
        for (String entry : candidate.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isEmpty() && !entries.contains(entry)) {
                entries.add(entry);
            }
        }
    }

    /** Newest bake.zip under Lunar's multiver cache, or an override from -Dravenforge.bake. */
    static String bakeJar() {
        String override = System.getProperty("ravenforge.bake");
        if (override != null && !override.isEmpty() && new File(override).isFile()) {
            return override;
        }
        File cache = new File(System.getProperty("user.home", "."),
                ".lunarclient" + File.separator + "offline" + File.separator + "multiver" + File.separator + "cache");
        File best = newest(cache, 0);
        return best == null ? null : best.getAbsolutePath();
    }

    /**
     * A javac-readable copy of the bake.
     *
     * Lunar's bake stores deobfuscated classes under dot-separated entry names
     * ("net.minecraft.util.MovementInput" holding a .class file), which javac ignores
     * entirely: it looks up "net/minecraft/util/MovementInput.class". Without this repack
     * the classpath entry is inert and a script cannot name a single Minecraft type.
     */
    static synchronized String compileBakeJar() {
        String raw = bakeJar();
        if (raw == null) {
            return null;
        }
        File source = new File(raw);
        if (!source.isFile()) {
            return null;
        }
        File directory = new File(System.getProperty("java.io.tmpdir", "."), "ravenforge-bake");
        File cached = new File(directory, "bake-" + source.length() + "-" + source.lastModified() + ".jar");
        if (cached.isFile() && cached.length() > 0) {
            return cached.getAbsolutePath();
        }
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                return null;
            }
            File temporary = new File(directory, cached.getName() + ".tmp");
            int classes = repack(source, temporary);
            if (classes == 0) {
                temporary.delete();
                return null;
            }
            if (!temporary.renameTo(cached)) {
                temporary.delete();
                if (!cached.isFile()) {
                    return null;
                }
            } else {
                System.out.println("[RavenForge] bake prepared for javac: " + cached.getName()
                        + " (" + classes + " classes)");
            }
            return cached.getAbsolutePath();
        } catch (Throwable throwable) {
            System.out.println("[RavenForge] could not prepare " + source + ": " + throwable);
            return null;
        }
    }

    private static int repack(File source, File output) throws Exception {
        int classes = 0;
        ZipFile zip = new ZipFile(source);
        try {
            ZipOutputStream out = new ZipOutputStream(new java.io.BufferedOutputStream(
                    new java.io.FileOutputStream(output)));
            try {
                for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    if (name.startsWith("META-INF/") || name.isEmpty()) {
                        continue;
                    }
                    byte[] data = readAll(zip.getInputStream(entry));
                    if (data.length < 8 || data[0] != (byte) 0xCA || data[1] != (byte) 0xFE
                            || data[2] != (byte) 0xBA || data[3] != (byte) 0xBE) {
                        continue;
                    }
                    String base = name.endsWith(".class") ? name.substring(0, name.length() - 6) : name;
                    if (base.isEmpty() || !wanted(base)) {
                        continue;
                    }
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(data);
                    // Stored, not deflated: the entries are already-compressed class files in
                    // a temp cache, and this halves the time the client spends preparing it.
                    ZipEntry packed = new ZipEntry(base.replace('.', '/') + ".class");
                    packed.setMethod(ZipEntry.STORED);
                    packed.setSize(data.length);
                    packed.setCompressedSize(data.length);
                    packed.setCrc(crc.getValue());
                    out.putNextEntry(packed);
                    out.write(data);
                    out.closeEntry();
                    classes++;
                }
            } finally {
                out.close();
            }
        } finally {
            zip.close();
        }
        return classes;
    }

    /**
     * Scripts reference the game, not the libraries bundled beside it. The bake is 107 MB
     * across 19k classes; the game packages plus Lunar's own (which Minecraft implements as
     * marker interfaces, so a class cannot be read without them) are about 60 MB, and the
     * protobuf/ICU/Gson trees never appear in a script's signatures.
     */
    private static boolean wanted(String base) {
        return base.startsWith("net.") || base.startsWith("optifine.")
                || base.startsWith("tv.") || base.startsWith("com.moonsworth.");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }

    private static File newest(File directory, int depth) {
        if (directory == null || !directory.isDirectory() || depth > 4) {
            return null;
        }
        File best = null;
        File[] children = directory.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                File nested = newest(child, depth + 1);
                if (nested != null && (best == null || nested.lastModified() > best.lastModified())) {
                    best = nested;
                }
            } else if ("bake.zip".equals(child.getName())) {
                if (best == null || child.lastModified() > best.lastModified()) {
                    best = child;
                }
            }
        }
        return best;
    }
}
