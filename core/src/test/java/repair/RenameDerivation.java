package repair;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Derives the legacy&harr;vanilla constant rename table by MEASURING it, jar by
 * jar, instead of typing it from memory.
 *
 * <h2>The signal</h2>
 *
 * <p>Bukkit's registry-backed constant holders — {@code Attribute},
 * {@code Enchantment}, {@code Particle}, {@code PotionEffectType} and friends —
 * all initialise their constants in {@code <clinit>} from the <em>vanilla
 * registry key</em>, and the bytecode says so out loud. Three shapes, one
 * pattern, seen in every jar in {@code paper/api-jars/}:
 *
 * <pre>
 *   1.20.4 Enchantment:  ldc "protection"            invokestatic getEnchantment  putstatic PROTECTION_ENVIRONMENTAL
 *   1.20.5 Enchantment:  ldc "protection"            invokestatic getEnchantment  putstatic PROTECTION
 *   1.20.5 Attribute:    ldc "GENERIC_MAX_HEALTH" .. ldc "generic.max_health" ..  putstatic GENERIC_MAX_HEALTH
 *   1.21.3 Attribute:    ldc "max_health"            invokestatic getAttribute    putstatic MAX_HEALTH
 * </pre>
 *
 * <p>So: <strong>the last String constant loaded before a {@code putstatic} of a
 * field whose type is the enclosing class is that constant's vanilla key.</strong>
 * Two field names in two different versions that carry the same key are the same
 * game concept under two spellings — which is precisely a rename, established by
 * measurement rather than by anyone's recollection of the 1.20.5 changelog.
 *
 * <p>Field names alone cannot do this. {@code DURABILITY} &rarr; {@code UNBREAKING}
 * shares no substring, has an edit distance of 10, and would be indistinguishable
 * from a coincidence in the removed/added diff of a 38-constant type. The key
 * {@code "unbreaking"} appears on both sides and settles it.
 *
 * <h2>Key normalisation</h2>
 *
 * <p>Vanilla renamed the attribute ids in the same breath as Bukkit renamed the
 * fields: {@code generic.max_health} became {@code max_health}, and
 * {@code horse.jump_strength} became {@code generic.jump_strength} before becoming
 * {@code jump_strength}. So keys are compared on their last dot-separated segment.
 * Within one version that segment is checked to be unique per type; a collision
 * drops both entries rather than inventing a pair.
 *
 * <h2>Output</h2>
 *
 * <p>Groups, not directed pairs. Every field name that ever named a given key
 * across the supported range lands in one group, so the repair pass gets a
 * symmetric alias set and works in both directions (1.21.1 code on 1.21.3 and
 * 1.21.3 code on 1.21.1) from one table. {@code main} prints the group table in
 * the exact form {@code SymbolRepair} embeds, and {@code SymbolRepairSelfTest}
 * re-derives it from the jars and fails if the embedded copy has drifted.
 *
 * <pre>
 *   ./gradlew :core:deriveRenames
 * </pre>
 */
public final class RenameDerivation {

    private RenameDerivation() {
    }

    /** One measured constant: which field, which vanilla key. */
    public record Constant(String field, String key) {
    }

    // ------------------------------------------------------------------
    // Derivation
    // ------------------------------------------------------------------

    /**
     * Measures every jar in {@code apiJarsDir} and returns, per simple type name,
     * the alias groups: sets of two-or-more field names that were measured to
     * carry the same normalised vanilla key.
     */
    public static Map<String, List<Set<String>>> derive(Path apiJarsDir) throws IOException {
        // type -> normalised key -> every field name ever seen for it
        Map<String, Map<String, Set<String>>> byKey = new TreeMap<>();

        List<Path> jars = jarsIn(apiJarsDir);
        for (Path jar : jars) {
            Map<String, Map<String, String>> perVersion = measureJar(jar);
            for (Map.Entry<String, Map<String, String>> type : perVersion.entrySet()) {
                Map<String, Set<String>> keys = byKey.computeIfAbsent(type.getKey(), k -> new TreeMap<>());
                for (Map.Entry<String, String> e : type.getValue().entrySet()) {
                    keys.computeIfAbsent(e.getValue(), k -> new TreeSet<>()).add(e.getKey());
                }
            }
        }

        Map<String, List<Set<String>>> out = new TreeMap<>();
        for (Map.Entry<String, Map<String, Set<String>>> type : byKey.entrySet()) {
            List<Set<String>> groups = new ArrayList<>();
            for (Set<String> names : type.getValue().values()) {
                if (names.size() > 1) {
                    groups.add(names);
                }
            }
            if (!groups.isEmpty()) {
                groups.sort(Comparator.comparing(s -> s.iterator().next()));
                out.put(type.getKey(), groups);
            }
        }
        return out;
    }

    /** Every {@code paper-api-*.jar} in a directory, in filename order. */
    public static List<Path> jarsIn(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * One jar: simple type name -&gt; (field name -&gt; normalised vanilla key), for
     * every type whose {@code <clinit>} exhibits the key-then-putstatic pattern.
     * Keys that are not unique within a type are dropped from that type entirely.
     */
    public static Map<String, Map<String, String>> measureJar(Path jar) throws IOException {
        Map<String, Map<String, String>> out = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                ClassFile parsed;
                try (InputStream in = zip.getInputStream(entry)) {
                    parsed = ClassFile.parse(in);
                } catch (RuntimeException | IOException broken) {
                    continue; // an unreadable class costs one type, never a wrong pair
                }
                if (parsed == null || parsed.selfConstants.isEmpty()) {
                    continue;
                }
                String simple = simpleNameOf(parsed.binaryName);
                Map<String, String> normalised = new TreeMap<>();
                Map<String, Integer> keyUses = new LinkedHashMap<>();
                for (Constant c : parsed.selfConstants) {
                    String key = normaliseKey(c.key());
                    normalised.put(c.field(), key);
                    keyUses.merge(key, 1, Integer::sum);
                }
                // Within one version a key must name exactly one constant. If it
                // does not, the normalisation lost information here — drop it
                // rather than pair on an ambiguous key.
                normalised.values().removeIf(k -> keyUses.get(k) > 1);
                if (!normalised.isEmpty()) {
                    // Nested classes lose to their top-level namesake, and an
                    // earlier package wins, matching ClassFileVocabulary's keying.
                    out.putIfAbsent(simple, normalised);
                }
            }
        }
        return out;
    }

    /** Last dot-separated segment, lowercased, {@code minecraft:} namespace stripped. */
    public static String normaliseKey(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT);
        int colon = k.indexOf(':');
        if (colon >= 0) {
            k = k.substring(colon + 1);
        }
        int dot = k.lastIndexOf('.');
        return dot >= 0 ? k.substring(dot + 1) : k;
    }

    private static String simpleNameOf(String binaryName) {
        String s = binaryName.substring(binaryName.lastIndexOf('.') + 1);
        int dollar = s.lastIndexOf('$');
        return dollar >= 0 ? s.substring(dollar + 1) : s;
    }

    // ------------------------------------------------------------------
    // Class file parsing (JVMS §4) — constant pool, fields, <clinit> Code
    // ------------------------------------------------------------------

    /** Just enough of a class file to answer "which key initialises which constant". */
    static final class ClassFile {
        String binaryName;
        final List<Constant> selfConstants = new ArrayList<>();

        static ClassFile parse(InputStream raw) throws IOException {
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw));
            if (in.readInt() != 0xCAFEBABE) {
                return null;
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major

            int poolCount = in.readUnsignedShort();
            int[] tag = new int[poolCount];
            String[] utf8 = new String[poolCount];
            int[] ref1 = new int[poolCount];
            int[] ref2 = new int[poolCount];
            for (int i = 1; i < poolCount; i++) {
                int t = in.readUnsignedByte();
                tag[i] = t;
                switch (t) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7, 8, 16, 19, 20 -> ref1[i] = in.readUnsignedShort();
                    case 15 -> {
                        in.readUnsignedByte();
                        ref1[i] = in.readUnsignedShort();
                    }
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++; // longs and doubles occupy two pool slots
                    }
                    case 9, 10, 11, 12, 17, 18 -> {
                        ref1[i] = in.readUnsignedShort();
                        ref2[i] = in.readUnsignedShort();
                    }
                    default -> throw new IOException("unknown constant pool tag " + t);
                }
            }

            ClassFile cf = new ClassFile();
            in.readUnsignedShort(); // access flags
            int thisClass = in.readUnsignedShort();
            cf.binaryName = className(tag, utf8, ref1, thisClass);
            in.readUnsignedShort(); // super
            int interfaces = in.readUnsignedShort();
            skip(in, 2L * interfaces);

            String selfDescriptor = "L" + cf.binaryName.replace('.', '/') + ";";

            // fields: remember which ones are constants OF this type
            Set<String> selfTyped = new TreeSet<>();
            int fieldCount = in.readUnsignedShort();
            for (int i = 0; i < fieldCount; i++) {
                in.readUnsignedShort(); // access
                String name = utf8[in.readUnsignedShort()];
                String desc = utf8[in.readUnsignedShort()];
                skipAttributes(in);
                if (selfDescriptor.equals(desc)) {
                    selfTyped.add(name);
                }
            }
            if (selfTyped.isEmpty()) {
                return cf;
            }

            int methodCount = in.readUnsignedShort();
            byte[] clinit = null;
            for (int i = 0; i < methodCount; i++) {
                in.readUnsignedShort(); // access
                String name = utf8[in.readUnsignedShort()];
                in.readUnsignedShort(); // descriptor
                int attrs = in.readUnsignedShort();
                for (int a = 0; a < attrs; a++) {
                    String attrName = utf8[in.readUnsignedShort()];
                    long len = in.readInt() & 0xFFFFFFFFL;
                    if ("<clinit>".equals(name) && "Code".equals(attrName) && clinit == null) {
                        in.readUnsignedShort(); // max_stack
                        in.readUnsignedShort(); // max_locals
                        int codeLength = in.readInt();
                        byte[] code = new byte[codeLength];
                        in.readFully(code);
                        clinit = code;
                        skip(in, len - 8 - codeLength);
                    } else {
                        skip(in, len);
                    }
                }
            }
            if (clinit != null) {
                cf.selfConstants.addAll(scanClinit(clinit, tag, utf8, ref1, ref2, cf.binaryName, selfTyped));
            }
            return cf;
        }

        /**
         * Walks {@code <clinit>} and pairs each {@code putstatic} of a self-typed
         * field with the most recent String constant pushed before it. Real opcode
         * lengths, not a byte scan: an unaligned search would happily read a branch
         * offset as a {@code putstatic}.
         */
        private static List<Constant> scanClinit(byte[] code, int[] tag, String[] utf8,
                                                 int[] ref1, int[] ref2,
                                                 String binaryName, Set<String> selfTyped) {
            List<Constant> out = new ArrayList<>();
            String internal = binaryName.replace('.', '/');
            String pending = null;
            int i = 0;
            while (i < code.length) {
                int op = code[i] & 0xFF;
                int operands = operandLength(code, i);
                if (operands < 0) {
                    break; // unrecognised opcode: stop rather than desynchronise
                }
                if (op == 0x12 || op == 0x13) { // ldc / ldc_w
                    int index = op == 0x12 ? (code[i + 1] & 0xFF) : u2(code, i + 1);
                    if (index > 0 && index < tag.length && tag[index] == 8) {
                        pending = utf8[ref1[index]];
                    }
                } else if (op == 0xB3) { // putstatic
                    int fieldRef = u2(code, i + 1);
                    if (pending != null && fieldRef > 0 && fieldRef < tag.length && tag[fieldRef] == 9) {
                        String owner = className(tag, utf8, ref1, ref1[fieldRef]).replace('.', '/');
                        int nameAndType = ref2[fieldRef];
                        String field = utf8[ref1[nameAndType]];
                        if (internal.equals(owner) && selfTyped.contains(field)) {
                            out.add(new Constant(field, pending));
                        }
                    }
                    pending = null;
                }
                i += 1 + operands;
            }
            return out;
        }

        private static int u2(byte[] b, int at) {
            return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
        }

        /** Operand byte count for the opcode at {@code at}, or -1 if unknown. */
        private static int operandLength(byte[] code, int at) {
            int op = code[at] & 0xFF;
            return switch (op) {
                case 0xAA -> tableSwitchLength(code, at);
                case 0xAB -> lookupSwitchLength(code, at);
                case 0xC4 -> (code[at + 1] & 0xFF) == 0x84 ? 5 : 3; // wide [iinc]
                case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19,
                     0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC -> 1;
                case 0x11, 0x13, 0x14, 0x84,
                     0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F,
                     0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8,
                     0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8,
                     0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7 -> 2;
                case 0xC5 -> 3;
                case 0xB9, 0xBA, 0xC8, 0xC9 -> 4;
                default -> op <= 0xC3 ? 0 : -1;
            };
        }

        private static int tableSwitchLength(byte[] code, int at) {
            int pad = (4 - ((at + 1) % 4)) % 4;
            int base = at + 1 + pad;
            int low = readInt(code, base + 4);
            int high = readInt(code, base + 8);
            return pad + 12 + 4 * (high - low + 1);
        }

        private static int lookupSwitchLength(byte[] code, int at) {
            int pad = (4 - ((at + 1) % 4)) % 4;
            int base = at + 1 + pad;
            int npairs = readInt(code, base + 4);
            return pad + 8 + 8 * npairs;
        }

        private static int readInt(byte[] b, int at) {
            return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
                    | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
        }

        private static String className(int[] tag, String[] utf8, int[] ref1, int classIndex) {
            if (classIndex <= 0 || classIndex >= tag.length || tag[classIndex] != 7) {
                return "";
            }
            String n = utf8[ref1[classIndex]];
            return n == null ? "" : n.replace('/', '.');
        }

        private static void skipAttributes(DataInputStream in) throws IOException {
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                in.readUnsignedShort();
                skip(in, in.readInt() & 0xFFFFFFFFL);
            }
        }

        private static void skip(DataInputStream in, long n) throws IOException {
            long left = n;
            while (left > 0) {
                long done = in.skip(left);
                if (done <= 0) {
                    if (in.read() < 0) {
                        throw new IOException("truncated class file");
                    }
                    done = 1;
                }
                left -= done;
            }
        }
    }

    // ------------------------------------------------------------------
    // main: print the table SymbolRepair embeds
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "paper/api-jars");
        List<Path> jars = jarsIn(dir);
        if (jars.isEmpty()) {
            System.out.println("No jars in " + dir.toAbsolutePath() + " — run scripts/fetch-api-jars.sh");
            return;
        }
        System.out.println("Measured " + jars.size() + " jars in " + dir.toAbsolutePath());

        Map<String, List<Set<String>>> groups = derive(dir);
        boolean filtered = args.length > 1 && "--vocab-types".equals(args[1]);
        int types = 0;
        int lines = 0;
        StringBuilder table = new StringBuilder();
        for (Map.Entry<String, List<Set<String>>> e : groups.entrySet()) {
            if (filtered && !VOCAB_TYPES.contains(e.getKey())) {
                continue;
            }
            types++;
            for (Set<String> names : e.getValue()) {
                lines++;
                table.append("            \"").append(e.getKey()).append('|')
                        .append(String.join(",", names)).append("\",\n");
            }
        }
        System.out.println(types + " types carry renames, " + lines + " alias groups\n");
        System.out.print(table);
    }

    /** The types a host actually measures (PaperApiVocabulary's map). */
    public static final Set<String> VOCAB_TYPES = new TreeSet<>(List.of(
            "Attribute", "Enchantment", "PotionEffectType", "Particle", "Sound",
            "Material", "EntityType"));
}
