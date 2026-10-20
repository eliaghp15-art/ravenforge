package ravenforge.script;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

/**
 * Exercises the compiler through the exact interface Script.compile uses: the same option
 * list, the same generated wrapper text, the same single SimpleJavaFileObject, output into
 * a flat temp directory, then a URLClassLoader over that directory.
 *
 * Usage: ScriptCompileTest <classpath-entry-for-the-stub-base>
 */
public final class ScriptCompileTest {
    private static final String IMPORTS =
            "import java.awt.Color;\nimport java.util.List;\nimport java.util.ArrayList;\n"
                    + "import java.util.Arrays;\nimport java.util.Map;\nimport java.util.HashMap;\n"
                    + "import java.util.HashSet;\nimport java.util.concurrent.ConcurrentHashMap;\n"
                    + "import java.util.LinkedHashMap;\nimport java.util.LinkedList;\n"
                    + "import java.util.Collections;\nimport java.util.Iterator;\n"
                    + "import java.util.Comparator;\nimport java.util.concurrent.atomic.AtomicBoolean;\n"
                    + "import java.util.concurrent.atomic.AtomicInteger;\n"
                    + "import java.util.concurrent.atomic.AtomicLong;\n"
                    + "import java.util.Random;\nimport java.util.regex.Pattern;\n"
                    + "import java.util.regex.Matcher;\n";

    /** -Dravenforge.testCompiler=system compares against the in-process compiler. */
    private static final JavaCompiler COMPILER = pickCompiler();

    private static JavaCompiler pickCompiler() {
        if ("system".equals(System.getProperty("ravenforge.testCompiler"))) {
            JavaCompiler system = javax.tools.ToolProvider.getSystemJavaCompiler();
            if (system != null) {
                return system;
            }
        }
        return new JavacProcessCompiler();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: ScriptCompileTest <classpath-entry-for-the-stub-base>");
            System.out.println("       ScriptCompileTest real <raven-mod-jar>   (with -Dravenforge.bake=<bake.zip>)");
            System.exit(2);
        }
        if ("real".equals(args[0])) {
            realScript(args[1]);
            System.out.println("SCRIPT-REAL-JAR-OK");
            return;
        }
        if ("dir".equals(args[0])) {
            System.exit(compileDirectory(args[1], new File(args[2])));
        }
        String stubClasspath = new File(args[0]).getAbsolutePath();
        successfulCompile(stubClasspath);
        missingBaseIsReported(Files.createTempDirectory("ravenforge-empty-cp-").toFile().getAbsolutePath());
        errorPositionIsExact(stubClasspath);
        brokenOptionsAreTolerated(stubClasspath);
        System.out.println("SCRIPT-COMPILER-OK");
    }

    /**
     * The classpath decision in the real world: a script whose superclass is Raven's own
     * ScriptDefaults must resolve against the shipped mod jar plus Lunar's bake, which is
     * exactly what ScriptCompiler.compileClasspath builds in game.
     */
    private static void realScript(String classpath) throws Exception {
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!new File(entry).isFile()) {
                throw new AssertionError("classpath entry is not a file: " + entry);
            }
        }
        Path out = Files.createTempDirectory("ravenforge-scripttest-");
        String name = className();
        String body = "    public void ravenForgeProbe() {\n        int n = 1;\n        n += 1;\n    }";
        String text = IMPORTS + "public class " + name
                + " extends xyz.daikirai.script.ScriptDefaults {\n" + body + "\n}";
        List<Diagnostic<? extends JavaFileObject>> seen = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        Source unit = new Source(name, text);
        Boolean result = compile(classpath, out, unit, seen);
        if (!Boolean.TRUE.equals(result)) {
            throw new AssertionError("real ScriptDefaults did not resolve: " + messages(seen));
        }
        File classFile = new File(out.toFile(), name + ".class");
        if (!classFile.isFile()) {
            throw new AssertionError("no " + classFile.getName() + " in " + out);
        }
        String version = System.getProperty("java.specification.version");
        int expected = 44 + Integer.parseInt(version.substring(version.lastIndexOf('.') + 1));
        int major = classMajor(Files.readAllBytes(classFile.toPath()));
        if (major != expected) {
            throw new AssertionError("class file targets " + major + ", runtime needs " + expected);
        }
        minecraftTypes(classpath);
    }

    /**
     * Compiles every .java in a Raven scripts folder the way reload() would: same wrapper
     * text, one long class name per file. Exit status 1 if any file fails.
     */
    private static int compileDirectory(String classpath, File directory) throws Exception {
        File[] files = directory.listFiles();
        if (files == null) {
            throw new AssertionError("no scripts directory at " + directory);
        }
        int compiled = 0;
        int failed = 0;
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || name.startsWith("_") || !name.endsWith(".java")) {
                continue;
            }
            Path out = Files.createTempDirectory("ravenforge-scripttest-");
            String className = className();
            String source = new String(Files.readAllBytes(file.toPath()), "UTF-8");
            String text = IMPORTS + "public class " + className
                    + " extends xyz.daikirai.script.ScriptDefaults {\n" + source + "\n}";
            List<Diagnostic<? extends JavaFileObject>> seen = new ArrayList<Diagnostic<? extends JavaFileObject>>();
            Boolean result = compile(classpath, out, new Source(className, text), seen);
            if (Boolean.TRUE.equals(result)) {
                compiled++;
                System.out.println("  OK    " + name);
            } else {
                failed++;
                System.out.println("  FAIL  " + name);
                for (String message : messages(seen)) {
                    System.out.println("          " + message);
                }
            }
        }
        if (compiled + failed == 0) {
            throw new AssertionError("no .java files in " + directory);
        }
        System.out.println("scripts compiled: " + compiled + ", failed: " + failed);
        return failed == 0 ? 0 : 1;
    }

    /**
     * A script naming Minecraft types must resolve them from the bake. This is what the bake
     * repack exists for: Lunar stores the bake under dot-separated entry names, which javac
     * silently ignores, so without the repack every game type is a missing symbol.
     */
    private static void minecraftTypes(String classpath) throws Exception {
        Path out = Files.createTempDirectory("ravenforge-scripttest-");
        String name = className();
        String body = "    public boolean probe() {\n"
                + "        net.minecraft.client.Minecraft game = net.minecraft.client.Minecraft.getMinecraft();\n"
                + "        net.minecraft.util.MovementInput input = null;\n"
                + "        return input != null && input.jump && game.thePlayer != null;\n"
                + "    }";
        String text = IMPORTS + "public class " + name
                + " extends xyz.daikirai.script.ScriptDefaults {\n" + body + "\n}";
        List<Diagnostic<? extends JavaFileObject>> seen = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        Boolean result = compile(classpath, out, new Source(name, text), seen);
        if (!Boolean.TRUE.equals(result)) {
            throw new AssertionError("Minecraft types did not resolve: " + messages(seen));
        }
    }

    /** A valid script compiles, lands in the output directory and loads. */
    private static void successfulCompile(String classpath) throws Exception {
        Path out = Files.createTempDirectory("ravenforge-scripttest-");
        String name = className();
        String body = "    public String value() {\n        return baseValue() + \"!\";\n    }";
        List<Diagnostic<? extends JavaFileObject>> seen = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        Source unit = new Source(name, wrap(name, body, "ravenforge.script.stub.ScriptBase"));
        Boolean result = compile(classpath, out, unit, seen);
        if (!Boolean.TRUE.equals(result)) {
            throw new AssertionError("valid script did not compile; diagnostics=" + messages(seen));
        }
        if (!seen.isEmpty()) {
            throw new AssertionError("unexpected diagnostics: " + messages(seen));
        }
        File classFile = new File(out.toFile(), name + ".class");
        if (!classFile.isFile()) {
            throw new AssertionError("no " + classFile.getName() + " in " + out);
        }
        int major = classMajor(Files.readAllBytes(classFile.toPath()));
        String version = System.getProperty("java.specification.version");
        int expected = 44 + Integer.parseInt(version.substring(version.lastIndexOf('.') + 1));
        if (major != expected) {
            throw new AssertionError("class file targets " + major + ", runtime needs " + expected);
        }
        URLClassLoader loader = new URLClassLoader(new URL[]{out.toUri().toURL()}, ScriptCompileTest.class.getClassLoader());
        try {
            Object instance = loader.loadClass(name).getDeclaredConstructor().newInstance();
            Object value = instance.getClass().getMethod("value").invoke(instance);
            if (!"ok!".equals(value)) {
                throw new AssertionError("script body returned " + value);
            }
        } finally {
            loader.close();
        }
    }

    /** Without the base class on the classpath, the failure must be visible, not silent. */
    private static void missingBaseIsReported(String classpath) throws Exception {
        Path out = Files.createTempDirectory("ravenforge-scripttest-");
        String name = className();
        List<Diagnostic<? extends JavaFileObject>> seen = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        Source unit = new Source(name, wrap(name, "    public String value() {\n        return \"x\";\n    }",
                "ravenforge.script.stub.ScriptBase"));
        Boolean result = compile(classpath, out, unit, seen);
        if (Boolean.TRUE.equals(result)) {
            throw new AssertionError("compiled without the base class; the classpath is not being used");
        }
        if (seen.isEmpty()) {
            throw new AssertionError("compile failed with no diagnostic: Script.compile would swallow it");
        }
        boolean hasError = false;
        for (Diagnostic<? extends JavaFileObject> diagnostic : seen) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                hasError = true;
                if (diagnostic.getSource() != unit) {
                    throw new AssertionError("diagnostic lost its source");
                }
                if (diagnostic.getMessage(null) == null || diagnostic.getMessage(null).isEmpty()) {
                    throw new AssertionError("empty diagnostic message");
                }
            }
        }
        if (!hasError) {
            throw new AssertionError("no ERROR diagnostic reported: " + messages(seen));
        }
        if (new File(out.toFile(), name + ".class").exists()) {
            throw new AssertionError("failed compile still wrote a class file");
        }
    }

    /** Raven turns getPosition() into a script line number, so the offset must be exact. */
    private static void errorPositionIsExact(String classpath) throws Exception {
        Path out = Files.createTempDirectory("ravenforge-scripttest-");
        String name = className();
        String body = "    public String value() {\n        return missingName;\n    }";
        String text = wrap(name, body, "ravenforge.script.stub.ScriptBase");
        List<Diagnostic<? extends JavaFileObject>> seen = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        Source unit = new Source(name, text);
        Boolean result = compile(classpath, out, unit, seen);
        if (Boolean.TRUE.equals(result)) {
            throw new AssertionError("undefined symbol compiled");
        }
        long expectedOffset = text.indexOf("missingName");
        int expectedLine = text.substring(0, (int) expectedOffset).split("\n").length;
        Diagnostic<? extends JavaFileObject> error = null;
        for (Diagnostic<? extends JavaFileObject> diagnostic : seen) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getPosition() >= 0) {
                error = diagnostic;
                break;
            }
        }
        if (error == null) {
            throw new AssertionError("no positioned diagnostic: " + messages(seen));
        }
        long position = error.getPosition();
        if (position != expectedOffset) {
            throw new AssertionError("position " + position + " != " + expectedOffset
                    + " ('" + text.substring((int) Math.min(position, text.length()),
                    (int) Math.min(position + 16, text.length())) + "')");
        }
        if (error.getLineNumber() != expectedLine) {
            throw new AssertionError("line " + error.getLineNumber() + " != " + expectedLine);
        }
    }

    /** Raven also passes javac-internal flags; they must not reach the external javac. */
    private static void brokenOptionsAreTolerated(String classpath) throws Exception {
        Path out = Files.createTempDirectory("ravenforge-scripttest-");
        String name = className();
        List<Diagnostic<? extends JavaFileObject>> seen = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        Source unit = new Source(name, wrap(name, "    public int one() {\n        return 1;\n    }",
                "ravenforge.script.stub.ScriptBase"));
        Boolean result = compile(classpath, out, unit, seen, "-XDuseUnsharedTable", "-XDshouldStopPolicyIfError=GENERATE");
        if (!Boolean.TRUE.equals(result)) {
            throw new AssertionError("javac-internal flags broke the compile: " + messages(seen));
        }
    }

    private static Boolean compile(String classpath, Path out, Source unit,
                                   List<Diagnostic<? extends JavaFileObject>> seen, String... extra) throws IOException {
        JavaFileManager manager = COMPILER.getStandardFileManager(null, null, Charset.forName("UTF-8"));
        try {
            List<String> options = new ArrayList<String>(Arrays.asList("-d", out.toFile().getAbsolutePath()));
            options.addAll(Arrays.asList(extra));
            options.addAll(Arrays.asList("-classpath", classpath));
            return COMPILER.getTask(null, manager, new Collector(seen), options, null,
                    Collections.singletonList(unit)).call();
        } finally {
            manager.close();
        }
    }

    private static int classMajor(byte[] bytes) {
        return ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
    }

    private static String wrap(String className, String body, String base) {
        return IMPORTS + "public class " + className + " extends " + base + " {\n" + body + "\n}";
    }

    /** Mirrors ScriptText.randomClassName's shape: one long identifier. */
    private static String className() {
        StringBuilder builder = new StringBuilder("S");
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        long seed = System.nanoTime();
        for (int i = 0; i < 60; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            builder.append(alphabet.charAt((int) Math.abs(seed >> 33) % alphabet.length()));
        }
        return builder.toString();
    }

    private static List<String> messages(List<Diagnostic<? extends JavaFileObject>> seen) {
        List<String> list = new ArrayList<String>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : seen) {
            list.add(diagnostic.getKind() + "@" + diagnostic.getLineNumber() + ": "
                    + diagnostic.getMessage(null) + source(diagnostic));
        }
        return list;
    }

    /** The script line the diagnostic points at, so a failure names its own symbol. */
    private static String source(Diagnostic<? extends JavaFileObject> diagnostic) {
        JavaFileObject unit = diagnostic.getSource();
        long position = diagnostic.getPosition();
        if (unit == null || position < 0) {
            return "";
        }
        try {
            String text = unit.getCharContent(true).toString();
            int start = (int) Math.min(position, text.length());
            int end = text.indexOf('\n', start);
            return "  |  " + (end < 0 ? text.substring(start) : text.substring(start, end)).trim();
        } catch (IOException ignored) {
            return "";
        }
    }

    private static final class Collector implements DiagnosticListener<JavaFileObject> {
        private final List<Diagnostic<? extends JavaFileObject>> seen;

        Collector(List<Diagnostic<? extends JavaFileObject>> seen) {
            this.seen = seen;
        }

        @Override
        public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            seen.add(diagnostic);
        }
    }

    /** Same URI shape as Script.Source: string:///Name.java, no package. */
    private static final class Source extends SimpleJavaFileObject {
        private final String code;

        Source(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
