package ravenforge.script;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.JavaCompiler;
import javax.lang.model.SourceVersion;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

/**
 * A JavaCompiler for JVMs that ship without one.
 *
 * Lunar's bundled runtime is a JRE (its module list has no jdk.compiler), so Raven's
 * ScriptManager.compiler was null and reload() bailed out before touching the scripts
 * folder. This implementation shells out to a JDK's javac that is already on the
 * machine, which keeps Raven's own code path intact: getTask(...).call() writes the
 * same class files into the -d directory that Script then loads with a URLClassLoader.
 *
 * Only the options Raven actually passes are honoured (-d, -classpath); the classpath is
 * rebuilt here because scripts need the baked Minecraft classes, which Lunar keeps in
 * its cache rather than on the game's classpath.
 */
final class JavacProcessCompiler implements JavaCompiler {
    private static final Pattern DIAGNOSTIC =
            Pattern.compile("^(.+?):(\\d+):\\s*(error|warning|note):\\s*(.*)$");

    private static String javacPath;
    private static int javacMajor;

    @Override
    public StandardJavaFileManager getStandardFileManager(DiagnosticListener<? super JavaFileObject> listener,
                                                          Locale locale, Charset charset) {
        return new StubFileManager();
    }

    @Override
    public CompilationTask getTask(Writer out, JavaFileManager fileManager,
                                   DiagnosticListener<? super JavaFileObject> listener,
                                   Iterable<String> options, Iterable<String> classes,
                                   Iterable<? extends JavaFileObject> compilationUnits) {
        return new JavacTask(listener, options, compilationUnits);
    }

    @Override
    public int isSupportedOption(String option) {
        return -1;
    }

    @Override
    public Set<SourceVersion> getSourceVersions() {
        return Collections.singleton(SourceVersion.RELEASE_8);
    }

    /** Tool.run: an in-process compiler entry point our process backend cannot support. */
    @Override
    public int run(java.io.InputStream in, OutputStream out, OutputStream err, String... arguments) {
        throw new UnsupportedOperationException("scripts are compiled in a separate javac process");
    }

    /**
     * Resolved on the first script compile, not at startup: probing candidates spawns
     * javac processes, and ScriptManager's static initialiser runs during client init.
     */
    private static synchronized String locate() {
        if (javacPath != null) {
            return javacPath.isEmpty() ? null : javacPath;
        }
        int runtime = runtimeMajor();
        List<String> candidates = candidates(runtime);
        String fallback = null;
        for (String candidate : candidates) {
            int major = probe(candidate);
            if (major <= 0) {
                continue;
            }
            if (major == runtime) {
                javacPath = candidate;
                javacMajor = major;
                announce();
                return javacPath;
            }
            if (fallback == null) {
                fallback = candidate + "\u0000" + major;
            }
        }
        if (fallback != null) {
            int split = fallback.lastIndexOf('\u0000');
            javacPath = fallback.substring(0, split);
            javacMajor = Integer.parseInt(fallback.substring(split + 1));
            announce();
            return javacPath;
        }
        javacPath = "";
        System.out.println("[RavenForge] script compiler: no JDK found; install one or set JAVA_HOME");
        return null;
    }

    private static void announce() {
        System.out.println("[RavenForge] script compiler: using " + javacPath + " (javac " + javacMajor
                + ", targeting Java " + runtimeMajor() + ")");
    }

    private static List<String> candidates(int runtime) {
        List<String> list = new ArrayList<String>();
        String executable = isWindows() ? "javac.exe" : "javac";
        // Probing spawns a JVM per candidate, so the likely match (same major as the
        // running game) is put first and the scan stops there.
        String home = System.getProperty("java.home");
        if (home != null) {
            list.add(new File(new File(home, "bin"), executable).getAbsolutePath());
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) {
            list.add(new File(new File(javaHome, "bin"), executable).getAbsolutePath());
        }
        for (String root : new String[]{
                "C:\\\\Program Files\\\\Eclipse Adoptium",
                "C:\\\\Program Files\\\\Java",
                "C:\\\\Program Files\\\\Microsoft",
                "C:\\\\Program Files\\\\Zulu"}) {
            File[] children = new File(root).listFiles();
            if (children == null) {
                continue;
            }
            final String major = String.valueOf(runtime);
            Arrays.sort(children, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    boolean left = matchesMajor(a.getName(), major);
                    boolean right = matchesMajor(b.getName(), major);
                    if (left != right) {
                        return left ? -1 : 1;
                    }
                    return b.getName().compareTo(a.getName());
                }
            });
            for (File child : children) {
                String name = child.getName();
                if (!name.toLowerCase(Locale.ROOT).startsWith("jdk")) {
                    continue;
                }
                list.add(new File(new File(child, "bin"), executable).getAbsolutePath());
            }
        }
        list.add(executable);
        return list;
    }

    private static boolean matchesMajor(String name, String major) {
        return name.contains("jdk-" + major) || name.contains("jdk" + major + "-")
                || name.contains("jdk-" + major + ".") || name.endsWith("jdk-" + major);
    }

    private static int probe(String path) {
        try {
            Process process = new ProcessBuilder(path, "-version").redirectErrorStream(true).start();
            String output = read(process);
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            if (process.exitValue() != 0) {
                return -1;
            }
            Matcher matcher = Pattern.compile("javac\\s+(\\d+)").matcher(output);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static int runtimeMajor() {
        String version = System.getProperty("java.specification.version", "8");
        try {
            return version.contains(".") ? Integer.parseInt(version.substring(version.indexOf('.') + 1)) : Integer.parseInt(version);
        } catch (NumberFormatException ignored) {
            return 8;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String read(Process process) throws IOException {
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private static final class JavacTask implements CompilationTask {
        private final DiagnosticListener<? super JavaFileObject> listener;
        private final Iterable<String> options;
        private final Iterable<? extends JavaFileObject> units;

        JavacTask(DiagnosticListener<? super JavaFileObject> listener, Iterable<String> options,
                  Iterable<? extends JavaFileObject> units) {
            this.listener = listener;
            this.options = options;
            this.units = units;
        }

        @Override
        public void setProcessors(Iterable<? extends javax.annotation.processing.Processor> processors) {
            // -proc:none is always passed: scripts never declare annotation processors.
        }

        @Override
        public void setLocale(Locale locale) {
            // Nothing to localise: javac runs in its own process.
        }

        @Override
        public Boolean call() {
            String javac = locate();
            if (javac == null) {
                reportFailure(null, "no JDK found: install one or set JAVA_HOME so scripts can compile");
                return Boolean.FALSE;
            }
            String outputDir = null;
            String givenClasspath = null;
            List<String> optionList = new ArrayList<String>();
            if (options != null) {
                for (String option : options) {
                    if ("-d".equals(option)) {
                        optionList.add(option);
                    } else if (option.startsWith("-XD")) {
                        // javac-internal tuning Raven passes; meaningless to a process.
                        continue;
                    } else {
                        optionList.add(option);
                    }
                }
                optionList = normalise(optionList);
                int d = optionList.indexOf("-d");
                if (d >= 0 && d + 1 < optionList.size()) {
                    outputDir = optionList.get(d + 1);
                }
                int cp = optionList.indexOf("-classpath");
                if (cp >= 0 && cp + 1 < optionList.size()) {
                    givenClasspath = optionList.get(cp + 1);
                }
            }
            List<JavaFileObject> sources = new ArrayList<JavaFileObject>();
            if (units != null) {
                for (JavaFileObject unit : units) {
                    sources.add(unit);
                }
            }
            if (sources.isEmpty()) {
                return Boolean.FALSE;
            }
            File sourceDir = null;
            try {
                sourceDir = createTempDirectory();
                Map<String, JavaFileObject> byName = new LinkedHashMap<String, JavaFileObject>();
                List<String> command = new ArrayList<String>();
                command.add(javac);
                command.add("-encoding");
                command.add("UTF-8");
                command.add("-proc:none");
                command.add("-nowarn");
                command.addAll(releaseFlags());
                if (outputDir != null) {
                    command.add("-d");
                    command.add(outputDir);
                }
                List<String> classpath = ScriptCompiler.compileClasspath(givenClasspath);
                if (!classpath.isEmpty()) {
                    StringBuilder joined = new StringBuilder();
                    for (String entry : classpath) {
                        if (joined.length() > 0) {
                            joined.append(File.pathSeparatorChar);
                        }
                        joined.append(entry);
                    }
                    command.add("-classpath");
                    command.add(joined.toString());
                }
                for (JavaFileObject unit : sources) {
                    String name = fileName(unit);
                    File file = new File(sourceDir, name);
                    write(file, unit.getCharContent(true).toString());
                    byName.put(name, unit);
                    command.add(file.getAbsolutePath());
                }
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                String output = read(process);
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    reportFailure(sources.get(0), "javac timed out");
                    return Boolean.FALSE;
                }
                int code = process.exitValue();
                if (code == 0) {
                    return Boolean.TRUE;
                }
                boolean reported = reportDiagnostics(output, byName);
                if (!reported) {
                    reportFailure(sources.get(0), output.trim());
                }
                return Boolean.FALSE;
            } catch (Throwable throwable) {
                reportFailure(sources.get(0), "javac failed: " + throwable);
                return Boolean.FALSE;
            } finally {
                delete(sourceDir);
            }
        }

        private List<String> normalise(List<String> options) {
            List<String> result = new ArrayList<String>();
            for (String option : options) {
                if ("-cp".equals(option)) {
                    result.add("-classpath");
                } else {
                    result.add(option);
                }
            }
            return result;
        }

        /** Match the running JVM's class-file version so the loader can accept the result. */
        private List<String> releaseFlags() {
            int runtime = runtimeMajor();
            if (javacMajor == runtime) {
                return Collections.emptyList();
            }
            if (javacMajor >= 9 && runtime >= 8) {
                return Arrays.asList("--release", String.valueOf(runtime));
            }
            return Arrays.asList("-source", "1.8", "-target", "1.8");
        }

        private boolean reportDiagnostics(String output, Map<String, JavaFileObject> byName) {
            boolean any = false;
            String[] lines = output.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = DIAGNOSTIC.matcher(lines[i]);
                if (!matcher.matches()) {
                    continue;
                }
                any = true;
                String file = new File(matcher.group(1)).getName();
                int number = Integer.parseInt(matcher.group(2));
                String severity = matcher.group(3);
                String message = matcher.group(4);
                JavaFileObject source = byName.get(file);
                Diagnostic.Kind kind = "error".equals(severity) ? Diagnostic.Kind.ERROR
                        : "warning".equals(severity) ? Diagnostic.Kind.WARNING : Diagnostic.Kind.NOTE;
                long position = -1;
                if (source != null) {
                    String text = text(source);
                    long lineStart = offsetOfLine(text, number);
                    position = lineStart + Math.max(0, Math.min(column(lines, i, text, lineStart, number),
                            lineLength(text, lineStart)));
                }
                emit(new SourceDiagnostic(kind, source, position, number, message));
            }
            return any;
        }

        /**
         * javac prints the offending source line followed by a caret marker; taking the
         * column from it keeps getPosition() pointing at the token, as the in-process
         * compiler's diagnostics do. Raven turns that offset into a script line number.
         */
        private int column(String[] lines, int index, String text, long lineStart, int number) {
            for (int i = index + 1; i < Math.min(lines.length, index + 4); i++) {
                String candidate = lines[i];
                int caret = candidate.indexOf('^');
                if (caret < 0) {
                    continue;
                }
                boolean marker = true;
                for (int c = 0; c < candidate.length(); c++) {
                    char ch = candidate.charAt(c);
                    if (ch != '^' && ch != ' ' && ch != '\t') {
                        marker = false;
                        break;
                    }
                }
                if (marker) {
                    return caret;
                }
            }
            return 0;
        }

        private int lineLength(String text, long lineStart) {
            int end = text.indexOf('\n', (int) lineStart);
            String line = end < 0 ? text.substring((int) lineStart) : text.substring((int) lineStart, end);
            return line.length();
        }

        private String text(JavaFileObject source) {
            try {
                return source.getCharContent(true).toString();
            } catch (Throwable ignored) {
                return "";
            }
        }

        private void emit(Diagnostic<? extends JavaFileObject> diagnostic) {
            if (listener == null) {
                System.out.println("[RavenForge] " + diagnostic.getMessage(null));
                return;
            }
            try {
                listener.report(diagnostic);
            } catch (Throwable throwable) {
                System.out.println("[RavenForge] diagnostic listener failed: " + throwable);
            }
        }

        private void reportFailure(JavaFileObject source, String message) {
            System.out.println("[RavenForge] script compile: " + message);
            if (source != null) {
                emit(new SourceDiagnostic(Diagnostic.Kind.ERROR, source, -1, -1, message));
            }
        }
    }

    /** Raven only ever closes the manager; the rest is never reached on this path. */
    private static final class StubFileManager implements StandardJavaFileManager {
        @Override
        public ClassLoader getClassLoader(Location location) {
            return JavacProcessCompiler.class.getClassLoader();
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds,
                                             boolean recurse) {
            return Collections.emptyList();
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            return null;
        }

        @Override
        public boolean isSameFile(FileObject a, FileObject b) {
            return a == b;
        }

        @Override
        public boolean handleOption(String current, java.util.Iterator<String> remaining) {
            return false;
        }

        @Override
        public boolean hasLocation(Location location) {
            return StandardLocation.CLASS_OUTPUT == location || StandardLocation.CLASS_PATH == location;
        }

        @Override
        public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                                   FileObject sibling) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileObject getFileForInput(Location location, String packageName, String relativeName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileObject getFileForOutput(Location location, String packageName, String relativeName,
                                           FileObject sibling) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int isSupportedOption(String option) {
            return -1;
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(Iterable<? extends File> files) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setLocation(Location location, Iterable<? extends File> path) {
            throw new UnsupportedOperationException();
        }

        // No @Override: this overload is absent from the --release 8 view of the interface.
        public void setLocation(Location location, File path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends File> getLocation(Location location) {
            return null;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static final class SourceDiagnostic implements Diagnostic<JavaFileObject> {
        private final Kind kind;
        private final JavaFileObject source;
        private final long position;
        private final long line;
        private final String message;

        SourceDiagnostic(Kind kind, JavaFileObject source, long position, long line, String message) {
            this.kind = kind;
            this.source = source;
            this.position = position;
            this.line = line;
            this.message = message;
        }

        @Override
        public Kind getKind() {
            return kind;
        }

        @Override
        public JavaFileObject getSource() {
            return source;
        }

        @Override
        public long getPosition() {
            return position;
        }

        @Override
        public long getStartPosition() {
            return position;
        }

        @Override
        public long getEndPosition() {
            return position;
        }

        @Override
        public long getLineNumber() {
            return line;
        }

        @Override
        public long getColumnNumber() {
            return -1;
        }

        @Override
        public String getCode() {
            return null;
        }

        @Override
        public String getMessage(Locale locale) {
            return message;
        }
    }

    private static String fileName(JavaFileObject unit) {
        try {
            URI uri = unit.toUri();
            String path = uri == null ? null : uri.getPath();
            if (path != null && path.endsWith(".java")) {
                return path.substring(path.lastIndexOf('/') + 1);
            }
        } catch (Throwable ignored) {
            // fall through to getName()
        }
        String name = unit.getName();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static long offsetOfLine(String text, int line) {
        if (line <= 1) {
            return 0;
        }
        int index = 0;
        int current = 1;
        while (current < line) {
            int next = text.indexOf('\n', index);
            if (next < 0) {
                return 0;
            }
            index = next + 1;
            current++;
        }
        return index;
    }

    private static File createTempDirectory() throws IOException {
        File directory = File.createTempFile("ravenforge-script-", "");
        if (!directory.delete() || !directory.mkdirs()) {
            throw new IOException("could not create " + directory);
        }
        return directory;
    }

    private static void write(File file, String content) throws IOException {
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static void delete(File file) {
        if (file == null) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }
}
