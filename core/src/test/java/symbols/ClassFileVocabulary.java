package symbols;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.gijsm.vibemod.platform.ApiVocabulary;

/**
 * An {@link ApiVocabulary} read straight out of a {@code .jar} by parsing class
 * files as bytes.
 *
 * <p><strong>Why bytes and not reflection.</strong> The obvious implementation —
 * {@code Class.forName("org.bukkit.Attribute").getFields()} — cannot work here.
 * Loading a Bukkit type drags in its whole transitive closure (Adventure, Brigadier,
 * the registry machinery, and on some versions a static initialiser that wants a
 * running server), so the load fails long before the field list appears, and it
 * fails differently on each of the twenty versions we need to measure. Parsing the
 * class file needs nothing but the bytes: no classloader, no dependencies, no
 * version-specific breakage, and twenty jars index in a couple of seconds.
 *
 * <p><strong>Why not ASM.</strong> Deliberately dependency-free. The alternative is
 * putting a bytecode library on {@code core}'s test classpath purely to read two
 * counted arrays, and the class file format's fields/methods tables are simple
 * enough (JVMS §4.1) that the parser below is shorter than the dependency
 * declaration would be interesting.
 *
 * <p>{@code core} must never depend on {@code paper-api} (ARCHITECTURE-V2 §1). This
 * class honours that literally: it imports nothing from {@code org.bukkit} or
 * {@code io.papermc} and only ever sees a file path.
 *
 * <h2>What counts as a "constant"</h2>
 *
 * <p>Only {@code public static final} fields. That covers both shapes Paper uses,
 * which is the whole point: on older versions {@code Attribute} is an {@code enum}
 * whose constants are {@code public static final} + {@code ACC_ENUM} fields, and on
 * modern versions it is an {@code interface} whose constants are fields that are
 * implicitly {@code public static final}. Reading access flags catches both without
 * caring which era it is looking at.
 *
 * <h2>Keying</h2>
 *
 * <p>{@link ApiVocabulary} is keyed by simple name. Lookups here also accept a fully
 * qualified name ({@code "io.papermc.paper.dialog.Dialog"}), because for a few
 * questions the package is the question. Where two classes in a jar share a simple
 * name the top-level one wins over a nested one, and an earlier package wins over a
 * later one; {@link #ambiguous()} lists every simple name that had more than one
 * claimant so a caller can tell when it is asking a question the key cannot answer.
 *
 * <h2>main()</h2>
 *
 * <pre>
 *   java symbols.ClassFileVocabulary paper/api-jars/paper-api-1.21.3.jar
 *   java symbols.ClassFileVocabulary paper-api-1.21.3.jar Attribute ItemMeta
 * </pre>
 */
public final class ClassFileVocabulary implements ApiVocabulary {

    // JVMS §4.1 access_flags / field_info.access_flags
    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_FINAL = 0x0010;
    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_ABSTRACT = 0x0400;
    private static final int ACC_ANNOTATION = 0x2000;
    private static final int ACC_ENUM = 0x4000;

    /** Everything measured about one class. */
    public static final class TypeInfo {
        private final String binaryName;
        private final int accessFlags;
        private final String superName;
        private final Set<String> constants;
        private final Set<String> methods;
        private final Set<String> signatures;

        TypeInfo(String binaryName, int accessFlags, String superName,
                 Set<String> constants, Set<String> methods, Set<String> signatures) {
            this.binaryName = binaryName;
            this.accessFlags = accessFlags;
            this.superName = superName;
            this.constants = Collections.unmodifiableSet(constants);
            this.methods = Collections.unmodifiableSet(methods);
            this.signatures = Collections.unmodifiableSet(signatures);
        }

        /** Fully qualified name, dotted. */
        public String binaryName() {
            return binaryName;
        }

        /** {@code public static final} field names. */
        public Set<String> constants() {
            return constants;
        }

        /** Declared method names (overloads collapsed). */
        public Set<String> methods() {
            return methods;
        }

        /**
         * Declared methods as {@code name+descriptor}, e.g.
         * {@code setEnchantmentGlintOverride(Ljava/lang/Boolean;)V}. Overloads are
         * distinct here, which is what makes a claim about a method's <em>parameter
         * type</em> checkable rather than merely its name.
         *
         * <p>Not exposed through {@link ApiVocabulary}: that interface is
         * deliberately name-only, because the prompt and the repair pass reason
         * about names. This is for the report, which has to be stricter than the
         * consumers.
         */
        public Set<String> signatures() {
            return signatures;
        }

        /** True when a method of this name takes exactly the given JVM descriptor parameters. */
        public boolean hasSignature(String methodName, String paramDescriptor) {
            for (String s : signatures) {
                if (s.startsWith(methodName + "(")
                        && s.substring(methodName.length()).startsWith("(" + paramDescriptor + ")")) {
                    return true;
                }
            }
            return false;
        }

        public boolean isEnum() {
            // ACC_ENUM on the class, or the pre-flag-era shape: extends java.lang.Enum.
            return (accessFlags & ACC_ENUM) != 0 || "java.lang.Enum".equals(superName);
        }

        public boolean isInterface() {
            return (accessFlags & ACC_INTERFACE) != 0 && (accessFlags & ACC_ANNOTATION) == 0;
        }

        public boolean isAnnotation() {
            return (accessFlags & ACC_ANNOTATION) != 0;
        }

        public boolean isAbstractClass() {
            return !isInterface() && !isEnum() && (accessFlags & ACC_ABSTRACT) != 0;
        }

        /** enum / interface / annotation / abstract class / class — for reports. */
        public String kind() {
            if (isEnum()) {
                return "enum";
            }
            if (isAnnotation()) {
                return "annotation";
            }
            if (isInterface()) {
                return "interface";
            }
            return isAbstractClass() ? "abstract class" : "class";
        }
    }

    private final String source;
    private final Map<String, TypeInfo> bySimpleName;
    private final Map<String, TypeInfo> byBinaryName;
    private final Set<String> ambiguous;

    private ClassFileVocabulary(String source, Map<String, TypeInfo> bySimpleName,
                                Map<String, TypeInfo> byBinaryName, Set<String> ambiguous) {
        this.source = source;
        this.bySimpleName = bySimpleName;
        this.byBinaryName = byBinaryName;
        this.ambiguous = ambiguous;
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    /** Indexes every class in a jar. */
    public static ClassFileVocabulary ofJar(Path jar) throws IOException {
        Map<String, TypeInfo> simple = new HashMap<>();
        Map<String, TypeInfo> binary = new HashMap<>();
        Set<String> ambiguous = new TreeSet<>();

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                TypeInfo info;
                try (InputStream in = zip.getInputStream(entry)) {
                    info = parse(in);
                } catch (IOException | RuntimeException malformed) {
                    // One unreadable entry must not lose the other 5,000. A jar
                    // with a module-info or a future class file version is still
                    // worth indexing.
                    continue;
                }
                if (info == null) {
                    continue;
                }
                binary.put(info.binaryName(), info);

                String simpleName = simpleNameOf(info.binaryName());
                TypeInfo existing = simple.get(simpleName);
                if (existing == null) {
                    simple.put(simpleName, info);
                } else if (!existing.binaryName().equals(info.binaryName())) {
                    ambiguous.add(simpleName);
                    // Prefer a top-level class over a nested one; otherwise keep
                    // whichever name sorts first, so an index is reproducible
                    // regardless of the order the zip happened to enumerate in.
                    boolean existingNested = existing.binaryName().indexOf('$') >= 0;
                    boolean candidateNested = info.binaryName().indexOf('$') >= 0;
                    boolean replace = (existingNested && !candidateNested)
                            || (existingNested == candidateNested
                                && info.binaryName().compareTo(existing.binaryName()) < 0);
                    if (replace) {
                        simple.put(simpleName, info);
                    }
                }
            }
        }
        return new ClassFileVocabulary(jar.getFileName().toString(), simple, binary, ambiguous);
    }

    private static String simpleNameOf(String binaryName) {
        String s = binaryName;
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            s = s.substring(dot + 1);
        }
        int dollar = s.lastIndexOf('$');
        if (dollar >= 0) {
            s = s.substring(dollar + 1);
        }
        return s;
    }

    // ------------------------------------------------------------------
    // The class file parser (JVMS §4)
    // ------------------------------------------------------------------

    private static TypeInfo parse(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        if (in.readInt() != 0xCAFEBABE) {
            return null;
        }
        in.readUnsignedShort(); // minor_version
        in.readUnsignedShort(); // major_version

        // --- constant pool -------------------------------------------------
        int poolCount = in.readUnsignedShort();
        String[] utf8 = new String[poolCount];
        int[] classNameIndex = new int[poolCount];
        for (int i = 1; i < poolCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: // Utf8
                    utf8[i] = in.readUTF();
                    break;
                case 7: // Class -> name_index
                case 8: // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    int idx = in.readUnsignedShort();
                    if (tag == 7) {
                        classNameIndex[i] = idx;
                    }
                    break;
                case 15: // MethodHandle: reference_kind u1 + reference_index u2
                    skip(in, 3);
                    break;
                case 3: // Integer
                case 4: // Float
                case 9: // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    skip(in, 4);
                    break;
                case 5: // Long
                case 6: // Double
                    skip(in, 8);
                    i++; // JVMS §4.4.5: takes two pool slots. The infamous one.
                    break;
                default:
                    // An unknown tag means the rest of the stream is unparseable;
                    // give up on this class rather than emit nonsense.
                    return null;
            }
        }

        int accessFlags = in.readUnsignedShort();
        int thisClass = in.readUnsignedShort();
        int superClass = in.readUnsignedShort();

        String binaryName = resolveClassName(utf8, classNameIndex, thisClass);
        if (binaryName == null) {
            return null;
        }
        String superName = superClass == 0 ? null : resolveClassName(utf8, classNameIndex, superClass);

        int interfaceCount = in.readUnsignedShort();
        skip(in, interfaceCount * 2);

        // --- fields: only public static final ones are "constants" ----------
        Set<String> constants = new LinkedHashSet<>();
        int fieldCount = in.readUnsignedShort();
        for (int i = 0; i < fieldCount; i++) {
            int flags = in.readUnsignedShort();
            int nameIndex = in.readUnsignedShort();
            in.readUnsignedShort(); // descriptor_index
            skipAttributes(in);
            boolean constant = (flags & ACC_PUBLIC) != 0
                    && (flags & ACC_STATIC) != 0
                    && (flags & ACC_FINAL) != 0;
            if (constant && nameIndex > 0 && nameIndex < utf8.length && utf8[nameIndex] != null) {
                constants.add(utf8[nameIndex]);
            }
        }

        // --- methods ---------------------------------------------------------
        Set<String> methods = new LinkedHashSet<>();
        Set<String> signatures = new LinkedHashSet<>();
        int methodCount = in.readUnsignedShort();
        for (int i = 0; i < methodCount; i++) {
            in.readUnsignedShort(); // access_flags
            int nameIndex = in.readUnsignedShort();
            int descIndex = in.readUnsignedShort();
            skipAttributes(in);
            if (nameIndex > 0 && nameIndex < utf8.length && utf8[nameIndex] != null) {
                methods.add(utf8[nameIndex]);
                String desc = descIndex > 0 && descIndex < utf8.length ? utf8[descIndex] : null;
                signatures.add(desc == null ? utf8[nameIndex] : utf8[nameIndex] + desc);
            }
        }

        return new TypeInfo(binaryName, accessFlags, superName, constants, methods, signatures);
    }

    private static String resolveClassName(String[] utf8, int[] classNameIndex, int classIndex) {
        if (classIndex <= 0 || classIndex >= classNameIndex.length) {
            return null;
        }
        int nameIndex = classNameIndex[classIndex];
        if (nameIndex <= 0 || nameIndex >= utf8.length || utf8[nameIndex] == null) {
            return null;
        }
        return utf8[nameIndex].replace('/', '.');
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort(); // attribute_name_index
            long length = in.readInt() & 0xFFFFFFFFL;
            skip(in, length);
        }
    }

    private static void skip(DataInputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                // skip() may legitimately return 0; fall back to a read so a
                // short read is an error rather than an infinite loop.
                if (in.read() < 0) {
                    throw new EOFException("truncated class file");
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    // ------------------------------------------------------------------
    // ApiVocabulary
    // ------------------------------------------------------------------

    @Override
    public Set<String> knownTypes() {
        return Collections.unmodifiableSet(bySimpleName.keySet());
    }

    @Override
    public Set<String> constants(String typeName) {
        TypeInfo info = lookup(typeName);
        return info == null ? Collections.emptySet() : info.constants();
    }

    @Override
    public Set<String> methods(String typeName) {
        TypeInfo info = lookup(typeName);
        return info == null ? Collections.emptySet() : info.methods();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Overridden so a fully qualified name resolves too: the base
     * implementation checks {@link #knownTypes()}, which holds simple names only.
     */
    @Override
    public Known knows(String typeName) {
        if (bySimpleName.isEmpty()) {
            return Known.UNKNOWN;
        }
        return lookup(typeName) != null ? Known.YES : Known.NO;
    }

    /** The {@link TypeInfo} for a simple or fully qualified name, or null. */
    public TypeInfo type(String typeName) {
        return lookup(typeName);
    }

    private TypeInfo lookup(String typeName) {
        if (typeName == null) {
            return null;
        }
        TypeInfo info = byBinaryName.get(typeName);
        return info != null ? info : bySimpleName.get(typeName);
    }

    /** Every fully qualified name in the jar. */
    public Set<String> binaryNames() {
        return Collections.unmodifiableSet(byBinaryName.keySet());
    }

    /** Simple names claimed by more than one class; see the class javadoc. */
    public Set<String> ambiguous() {
        return Collections.unmodifiableSet(ambiguous);
    }

    /** Where this index came from, for report headers. */
    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return "ClassFileVocabulary[" + source + ", " + byBinaryName.size() + " classes]";
    }

    // ------------------------------------------------------------------
    // main(): print an index a human can inspect
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ClassFileVocabulary <jar> [type ...]");
            System.err.println("  no types: a summary of the jar");
            System.err.println("  types:    full constant/method listing for each");
            System.exit(2);
        }
        Path jar = Path.of(args[0]);
        if (!Files.isRegularFile(jar)) {
            System.err.println("no such jar: " + jar.toAbsolutePath());
            System.err.println("run scripts/fetch-api-jars.sh first");
            System.exit(2);
        }

        long started = System.nanoTime();
        ClassFileVocabulary vocab = ofJar(jar);
        long ms = (System.nanoTime() - started) / 1_000_000;

        System.out.println("=== " + jar.getFileName() + " ===");
        System.out.println("classes indexed : " + vocab.binaryNames().size() + "  (" + ms + " ms)");
        System.out.println("simple names    : " + vocab.knownTypes().size()
                + "  (" + vocab.ambiguous().size() + " ambiguous)");
        System.out.println();

        List<String> types = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            types.add(args[i]);
        }
        if (types.isEmpty()) {
            // The types the prompt makes claims about — the reason this tool exists.
            types = List.of("Attribute", "Sound", "Particle", "Enchantment",
                    "PotionEffectType", "Material", "EntityType", "ItemMeta",
                    "io.papermc.paper.dialog.Dialog");
            System.out.println("-- summary of the types the prompt asserts things about --");
            System.out.printf("%-22s %-16s %8s %8s%n", "TYPE", "KIND", "CONSTS", "METHODS");
            for (String t : types) {
                TypeInfo info = vocab.type(t);
                if (info == null) {
                    System.out.printf("%-22s %-16s %8s %8s%n", t, "ABSENT", "-", "-");
                } else {
                    System.out.printf("%-22s %-16s %8d %8d%n",
                            simpleNameOf(info.binaryName()), info.kind(),
                            info.constants().size(), info.methods().size());
                }
            }
            System.out.println();
            System.out.println("pass type names as extra arguments for full listings");
            return;
        }

        for (String t : types) {
            TypeInfo info = vocab.type(t);
            System.out.println("--- " + t + " ---");
            if (info == null) {
                System.out.println("  ABSENT from this jar");
                System.out.println();
                continue;
            }
            System.out.println("  " + info.kind() + " " + info.binaryName());
            System.out.println("  constants (" + info.constants().size() + "):");
            printColumns(new TreeSet<>(info.constants()));
            System.out.println("  methods (" + info.methods().size() + "):");
            printColumns(new TreeSet<>(info.methods()));
            System.out.println();
        }
    }

    private static void printColumns(Set<String> names) {
        StringBuilder line = new StringBuilder("    ");
        for (String n : names) {
            if (line.length() + n.length() + 2 > 96) {
                System.out.println(line);
                line = new StringBuilder("    ");
            }
            line.append(n).append("  ");
        }
        if (line.toString().trim().length() > 0) {
            System.out.println(line);
        }
    }
}
