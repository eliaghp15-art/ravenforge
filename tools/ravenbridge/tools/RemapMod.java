package ravenbridge.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Build-time namespacer: rewrites net/minecraftforge/* inside a Forge mod to
 * ravenforge/*, and forces remap=false on its mixin annotations.
 *
 * Doing this offline means the jar shipped to the game provably contains no
 * Forge references, and the runtime agent needs no bytecode library of its own.
 */
public final class RemapMod {
    private static final String FORGE_PREFIX = "net/minecraftforge/";
    private static final String FORGE_DOTTED = "net.minecraftforge.";
    private static final String SHIM_PREFIX = "ravenforge/";
    private static final String MIXIN_DESC_PREFIX = "Lorg/spongepowered/asm/mixin/";

    /** Lunar has no LaunchWrapper, so the two Launch touches in Script.compile are redirected. */
    private static final String LAUNCH_CLASS = "net/minecraft/launchwrapper/Launch";
    private static final String LAUNCH_SHIM = "ravenforge/script/LaunchShim";
    /** The field's declared type: a plain ClassLoader is all the call site needs. */
    private static final String LAUNCH_CLASSLOADER = "net/minecraft/launchwrapper/LaunchClassLoader";
    private static final String PLAIN_CLASSLOADER = "java/lang/ClassLoader";
    private static final String LAUNCH_DOTTED = "net.minecraft.launchwrapper.Launch";
    private static final String LAUNCH_DOTTED_SHIM = "ravenforge.script.LaunchShim";

    /** A JRE has no compiler module, so the lookup is repointed at our supplier. */
    private static final String TOOL_PROVIDER = "javax/tools/ToolProvider";
    private static final String GET_COMPILER = "getSystemJavaCompiler";
    private static final String SCRIPT_COMPILER = "ravenforge/script/ScriptCompiler";

    /** ScriptManager.ownJar() can return "", which makes reload() a silent no-op. */
    private static final String OWN_JAR = "ownJar";
    private static final String CLASSPATH = "classpath";

    /**
     * SRG name -> MCP name. Raven looks members up reflectively with the SRG name as a
     * string literal (ScriptReflection's ReflectedField.srgName and friends) and only
     * sometimes carries an MCP fallback. In Lunar's named world the SRG lookup always
     * misses, so those literals are rewritten to the name the field actually has here.
     */
    private final Map<String, String> srgToMcp = new LinkedHashMap<>();

    private final List<String> changed = new ArrayList<>();
    private int classesScanned;
    private int forgeRefs;
    private int launchRefs;
    private int compilerCalls;
    private int classpathCalls;
    private int mixinAnnotations;
    private int stripped;
    private int srgLiterals;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: RemapMod <input.jar> <output.jar> [agent.jar-with-mappings]");
            System.exit(2);
        }
        RemapMod tool = new RemapMod();
        if (args.length > 2) {
            tool.loadMappings(new File(args[2]));
        }
        tool.run(new File(args[0]), new File(args[1]));
        System.out.println("classes scanned: " + tool.classesScanned);
        System.out.println("classes rewritten: " + tool.changed.size());
        System.out.println("forge references rewritten: " + tool.forgeRefs);
        System.out.println("launchwrapper references rewritten: " + tool.launchRefs);
        System.out.println("compiler calls repointed at ScriptCompiler.get: " + tool.compilerCalls);
        System.out.println("ownJar calls repointed at ScriptCompiler.classpath: " + tool.classpathCalls);
        System.out.println("mixin annotations forced to remap=false: " + tool.mixinAnnotations);
        System.out.println("vendored mixin/ASM entries stripped: " + tool.stripped);
        System.out.println("srg names available for literal remap: " + tool.srgToMcp.size());
        System.out.println("srg string literals remapped to MCP: " + tool.srgLiterals);
        if (tool.forgeRefs == 0) {
            System.out.println("WARNING: nothing was rewritten; is this the right jar?");
        }
    }

    /** Reads mappings/{fields,methods}.csv out of the agent jar; both share the searge,name shape. */
    private void loadMappings(File agentJar) {
        if (agentJar == null || !agentJar.isFile()) {
            System.out.println("no agent jar supplied; SRG string literals will not be remapped");
            return;
        }
        for (String entry : new String[]{"mappings/fields.csv", "mappings/methods.csv"}) {
            try (JarFile jar = new JarFile(agentJar)) {
                JarEntry e = jar.getJarEntry(entry);
                if (e == null) {
                    System.out.println("mapping table missing from agent jar: " + entry);
                    continue;
                }
                String text = new String(read(jar, e), java.nio.charset.StandardCharsets.UTF_8);
                int added = 0;
                for (String line : text.split("\\R")) {
                    int comma = line.indexOf(',');
                    if (comma <= 0) {
                        continue;
                    }
                    String srg = line.substring(0, comma).trim();
                    int end = line.indexOf(',', comma + 1);
                    String mcp = (end < 0 ? line.substring(comma + 1) : line.substring(comma + 1, end)).trim();
                    if (srg.isEmpty() || mcp.isEmpty() || "searge".equals(srg)) {
                        continue;
                    }
                    srgToMcp.put(srg, mcp);
                    added++;
                }
                System.out.println("loaded " + added + " names from " + entry);
            } catch (Exception e) {
                System.out.println("could not read " + entry + " from " + agentJar + ": " + e);
            }
        }
    }

    private void run(File input, File output) throws Exception {
        Path parent = output.toPath().toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (JarFile jar = new JarFile(input);
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(output.toPath()))) {
            List<JarEntry> entries = new ArrayList<>();
            for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                entries.add(e.nextElement());
            }
            for (JarEntry entry : entries) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (strips(name)) {
                    stripped++;
                    continue;
                }
                byte[] data = read(jar, entry);
                if (name.endsWith(".class")) {
                    classesScanned++;
                    byte[] patched = rewrite(name, data);
                    if (patched != null) {
                        changed.add(name);
                        data = patched;
                    }
                } else if (name.equals("clickgui.mixins.json")) {
                    data = stripRefmap(new String(data, "UTF-8")).getBytes("UTF-8");
                }
                JarEntry copy = new JarEntry(name);
                out.putNextEntry(copy);
                out.write(data);
                out.closeEntry();
            }
        }
    }

    /**
     * RavenB4 vendors its own Mixin and ASM, which would compete with the Mixin
     * runtime Lunar already loaded. Lunar's copy is a superset of everything the
     * mod references, so the vendored one is dropped along with its service file.
     */
    private static boolean strips(String name) {
        return name.startsWith("org/spongepowered/")
                || name.startsWith("org/objectweb/")
                || name.startsWith("META-INF/services/org.spongepowered");
    }

    /** Returns the rewritten class, or null when the class needed no change. */
    private byte[] rewrite(String name, byte[] bytes) {
        boolean hasForge = contains(bytes, FORGE_PREFIX) || contains(bytes, FORGE_DOTTED)
                || contains(bytes, LAUNCH_CLASS) || contains(bytes, LAUNCH_DOTTED)
                || contains(bytes, TOOL_PROVIDER);
        boolean isMixinClass = contains(bytes, MIXIN_DESC_PREFIX);
        boolean mayHaveSrg = !srgToMcp.isEmpty()
                && (contains(bytes, "field_") || contains(bytes, "func_"));
        if (!hasForge && !isMixinClass && !mayHaveSrg) {
            return null;
        }
        ClassReader reader = new ClassReader(bytes);
        // Plain ClassWriter: copying the reader's constant pool would keep the old
        // net/minecraftforge strings in the output even though nothing references them.
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor visitor = hasForge ? new ForgeNamespaceWriter(writer) : writer;
        if (mayHaveSrg) {
            visitor = new SrgLiteralWriter(visitor);
        }
        if (isMixinClass) {
            visitor = new MixinAnnotationWriter(visitor);
        }
        try {
            reader.accept(visitor, 0);
        } catch (Throwable throwable) {
            System.out.println("  could not rewrite " + name + ": " + throwable);
            return null;
        }
        return writer.toByteArray();
    }

    private final class ForgeRemapper extends Remapper {
        private final java.util.Set<String> replaced;

        ForgeRemapper(java.util.Set<String> replaced) {
            this.replaced = replaced;
        }

        @Override
        public String map(String internalName) {
            if (internalName == null) {
                return null;
            }
            if (LAUNCH_CLASS.equals(internalName)) {
                launchRefs++;
                return LAUNCH_SHIM;
            }
            if (LAUNCH_CLASSLOADER.equals(internalName)) {
                // LaunchShim.classLoader is declared as ClassLoader, so the descriptor on
                // the getstatic has to match or the field lookup fails at runtime.
                launchRefs++;
                return PLAIN_CLASSLOADER;
            }
            if (!internalName.startsWith(FORGE_PREFIX)) {
                return internalName;
            }
            replaced.add(internalName);
            return SHIM_PREFIX + internalName.substring(FORGE_PREFIX.length());
        }
    }

    private final class ForgeNamespaceWriter extends ClassRemapper {
        private final java.util.Set<String> replaced;

        ForgeNamespaceWriter(ClassVisitor next) {
            this(new java.util.HashSet<String>(), next);
        }

        private ForgeNamespaceWriter(java.util.Set<String> replaced, ClassVisitor next) {
            super(Opcodes.ASM9, next, new ForgeRemapper(replaced));
            this.replaced = replaced;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodVisitor(Opcodes.ASM9, next) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    if (TOOL_PROVIDER.equals(owner) && GET_COMPILER.equals(name)) {
                        compilerCalls++;
                        super.visitMethodInsn(opcode, SCRIPT_COMPILER, "get", descriptor, false);
                        return;
                    }
                    if (OWN_JAR.equals(name) && "()Ljava/lang/String;".equals(descriptor)) {
                        classpathCalls++;
                        super.visitMethodInsn(opcode, SCRIPT_COMPILER, CLASSPATH, descriptor, false);
                        return;
                    }
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof String) {
                        String text = (String) value;
                        if (text.contains(FORGE_DOTTED) || text.contains(LAUNCH_DOTTED)) {
                            String rewritten = text.replace(FORGE_DOTTED, "ravenforge.")
                                    .replace(LAUNCH_DOTTED, LAUNCH_DOTTED_SHIM);
                            replaced.add(rewritten);
                            super.visitLdcInsn(rewritten);
                            return;
                        }
                    }
                    super.visitLdcInsn(value);
                }
            };
        }

        @Override
        public void visitEnd() {
            forgeRefs += replaced.size();
            super.visitEnd();
        }
    }

    /** Rewrites SRG names that appear as string literals into their MCP names. */
    private final class SrgLiteralWriter extends ClassVisitor {
        SrgLiteralWriter(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (next == null) {
                return null;
            }
            return new MethodVisitor(Opcodes.ASM9, next) {
                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof String) {
                        String mapped = srgToMcp.get(value);
                        if (mapped != null && !mapped.equals(value)) {
                            srgLiterals++;
                            super.visitLdcInsn(mapped);
                            return;
                        }
                    }
                    super.visitLdcInsn(value);
                }
            };
        }
    }

    /** Adds remap=false to every Mixin annotation so Lunar's remapper leaves Raven alone. */
    private final class MixinAnnotationWriter extends ClassVisitor {
        MixinAnnotationWriter(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            AnnotationVisitor next = super.visitAnnotation(descriptor, visible);
            if (next == null || descriptor == null || !descriptor.startsWith(MIXIN_DESC_PREFIX)) {
                return next;
            }
            mixinAnnotations++;
            return new RemapFalseVisitor(next);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodVisitor(Opcodes.ASM9, next) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    AnnotationVisitor av = super.visitAnnotation(annotationDescriptor, visible);
                    if (av == null || annotationDescriptor == null || !annotationDescriptor.startsWith(MIXIN_DESC_PREFIX)) {
                        return av;
                    }
                    mixinAnnotations++;
                    return new RemapFalseVisitor(av);
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int index, String annotationDescriptor, boolean visible) {
                    AnnotationVisitor av = super.visitParameterAnnotation(index, annotationDescriptor, visible);
                    if (av == null || annotationDescriptor == null || !annotationDescriptor.startsWith(MIXIN_DESC_PREFIX)) {
                        return av;
                    }
                    mixinAnnotations++;
                    return new RemapFalseVisitor(av);
                }
            };
        }
    }

    private static final class RemapFalseVisitor extends AnnotationVisitor {
        RemapFalseVisitor(AnnotationVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public void visit(String name, Object value) {
            if ("remap".equals(name)) {
                super.visit(name, Boolean.FALSE);
            } else {
                super.visit(name, value);
            }
        }

        @Override
        public void visitEnd() {
            super.visit("remap", Boolean.FALSE);
            super.visitEnd();
        }
    }

    private static String stripRefmap(String json) {
        String result = json.replaceAll(",\\s*\"refmap\"\\s*:\\s*\"[^\"]*\"", "");
        result = result.replaceAll("\"refmap\"\\s*:\\s*\"[^\"]*\"\\s*,", "");
        result = result.replaceAll("\"refmap\"\\s*:\\s*\"[^\"]*\"", "");
        return result;
    }

    private static byte[] read(JarFile jar, JarEntry entry) throws Exception {
        try (InputStream in = jar.getInputStream(entry)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private static boolean contains(byte[] haystack, String needle) {
        byte[] target = needle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= haystack.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (haystack[i + j] != target[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
