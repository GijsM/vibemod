package com.gijsm.vibemod.loader.surgeon;

import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.gijsm.vibemod.platform.ClassSurgeon;

/**
 * The bytecode pass V3 is built on (Phase 0 §A): one walk per generated class
 * that verifies what the code really touches and rewrites the call sites the
 * host owns.
 *
 * <p><b>Why bytecode and not source.</b> The question "what does this mod
 * actually call" has exactly one honest answer and it is not in the source
 * text: a regex over imports is defeated by a fully-qualified name, and a
 * javac plugin would have to be re-taught for ECJ. The compiled classes are the
 * ground truth, they are already in memory between {@code compile()} and
 * {@code defineClass}, and {@code java.lang.classfile} reads them with zero
 * dependencies on a JDK the loaders already require (Java 25). The pass is a
 * few milliseconds on a mod-sized class set.
 *
 * <p><b>The scan is over instructions, not the constant pool.</b> That is a
 * deliberate correctness decision rather than a stylistic one. Lambdas, records
 * and pattern switches all put {@code java/lang/invoke/MethodHandle} and
 * friends into the constant pool, so a pool-level "no method handles" rule
 * would reject the most ordinary Java there is. Walking instructions lets the
 * rule be what it should be: a dynamic call site is fine when its
 * <em>bootstrap</em> is one of the four javac uses, and forbidden otherwise —
 * and the method handles javac threads through those bootstraps get checked as
 * the ordinary member references they are, so {@code Thread::start} is caught
 * even though it appears nowhere as an {@code invokevirtual}.
 *
 * <p><b>The rewrite is shape-preserving.</b> Every seam turns an
 * {@code invokevirtual} into an {@code invokestatic} whose descriptor is the
 * original with the receiver prepended, so the operand stack before and after
 * is byte-for-byte the same and no frame recomputation is needed. A class with
 * no seam hit is returned byte-identical, untouched — which is what keeps the
 * legacy {@code VibeContext} corpus provably unaffected by V3.
 *
 * <p>Knows no loader. The policy and the seam table both arrive from the host
 * at wiring time, which is what lets NeoForge run the same class with an empty
 * seam table and one extra denial (§A).
 */
public final class BytecodeSurgeon implements ClassSurgeon {

    /**
     * The four bootstraps javac emits for ordinary Java: lambdas and method
     * references, string concatenation, record {@code equals}/{@code hashCode}/
     * {@code toString}, and pattern/enum switches. Anything else bootstrapping
     * a call site is a mod doing something the host cannot reason about.
     */
    private static final Set<String> ALLOWED_BOOTSTRAPS = Set.of(
            "java/lang/invoke/LambdaMetafactory",
            "java/lang/invoke/StringConcatFactory",
            "java/lang/runtime/ObjectMethods",
            "java/lang/runtime/SwitchBootstraps");

    /**
     * Bootstraps allowed for a <em>constant</em> (a {@code CONSTANT_Dynamic}
     * pool entry), as opposed to a call site.
     *
     * <p>{@code ConstantBootstraps} is on this list and not on the one above,
     * and the difference was found by testing rather than reasoning: javac
     * compiles a pattern switch's {@code null} label into a dynamic constant
     * bootstrapped by {@code ConstantBootstraps.nullConstant}, so a policy
     * without it rejects an ordinary {@code switch}. It stays off the call-site
     * list because {@code ConstantBootstraps.invoke} can call an arbitrary
     * method handle — and even here the handle it is given is walked as an
     * ordinary member reference, so an argument reaching for something
     * forbidden is still caught.
     */
    private static final Set<String> ALLOWED_CONSTANT_BOOTSTRAPS = Set.of(
            "java/lang/invoke/LambdaMetafactory",
            "java/lang/invoke/StringConcatFactory",
            "java/lang/invoke/ConstantBootstraps",
            "java/lang/runtime/ObjectMethods",
            "java/lang/runtime/SwitchBootstraps");

    private final SurgeonPolicy policy;
    private final List<Seam> seams;

    public BytecodeSurgeon(SurgeonPolicy policy, List<Seam> seams) {
        this.policy = policy;
        this.seams = List.copyOf(seams);
    }

    /** The policy-only surgeon: verify, never rewrite (what NeoForge gets in Phase 0). */
    public BytecodeSurgeon(SurgeonPolicy policy) {
        this(policy, List.of());
    }

    @Override
    public Result operate(Map<String, byte[]> classes) {
        // Classes from the same compile reference each other freely; they are
        // not "external" references and must not be measured against the
        // package allowlist (the generated package is on it anyway, but a mod
        // whose package the model spelled unusually should still link).
        Set<String> own = new LinkedHashSet<>();
        for (String binaryName : classes.keySet()) {
            own.add(binaryName.replace('.', '/'));
        }

        // NOT ClassFile.of(): its default hierarchy resolver loads classes off
        // the SYSTEM class loader, which on a Fabric server cannot see
        // net.minecraft.* at all. Rewriting a method whose stack maps have to be
        // regenerated — anything holding a game type as a local across a branch
        // or a try/catch — then fails with "Could not resolve class Identifier",
        // a diagnostic naming no file, no line and no action the model could
        // take. Simple methods never need the resolver, which is exactly what
        // made this latent until a mod held an Identifier across a finally.
        //
        // This class's own loader is the right one: it is Knot on Fabric and the
        // mod loader's on NeoForge, and both can see the game.
        ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver
                        .ofClassLoading(BytecodeSurgeon.class.getClassLoader())
                        .cached()));
        List<String> violations = new ArrayList<>();
        Map<String, ClassModel> parsed = new LinkedHashMap<>();

        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            String sourceName = sourceNameOf(entry.getKey());
            ClassModel model;
            try {
                model = classFile.parse(entry.getValue());
            } catch (RuntimeException unreadable) {
                violations.add(sourceName + ": error: unreadable class file: " + unreadable);
                continue;
            }
            parsed.put(entry.getKey(), model);
            violations.addAll(new Scan(sourceName, own).verify(model));
        }

        if (!violations.isEmpty()) {
            return Result.rejected(String.join("\n", violations));
        }
        if (seams.isEmpty()) {
            return Result.accepted(classes);
        }

        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            ClassModel model = parsed.get(entry.getKey());
            byte[] rewritten = model == null ? entry.getValue() : rewrite(classFile, model);
            out.put(entry.getKey(), rewritten == null ? entry.getValue() : rewritten);
        }
        return Result.accepted(out);
    }

    // ------------------------------------------------------------------ rewrite

    /** Returns the rewritten bytes, or null when this class has no seam call site at all. */
    private byte[] rewrite(ClassFile classFile, ClassModel model) {
        if (!hasSeamCall(model)) {
            return null;
        }
        ClassTransform transform = ClassTransform.transformingMethodBodies((builder, element) -> {
            if (element instanceof InvokeInstruction invoke) {
                Seam seam = seamFor(invoke.owner().asInternalName(),
                        invoke.name().stringValue(), invoke.type().stringValue());
                if (seam != null) {
                    // Receiver and arguments are already on the stack in the
                    // right order; prepending the receiver to the descriptor is
                    // the entire translation.
                    builder.invokestatic(ClassDesc.ofInternalName(seam.shimOwner()), seam.shimName(),
                            MethodTypeDesc.ofDescriptor(seam.shimDescriptor()));
                    return;
                }
            }
            builder.with(element);
        });
        return classFile.transformClass(model, transform);
    }

    private boolean hasSeamCall(ClassModel model) {
        for (ClassElement element : model) {
            if (!(element instanceof MethodModel method)) {
                continue;
            }
            Optional<CodeModel> code = method.code();
            if (code.isEmpty()) {
                continue;
            }
            for (CodeElement instruction : code.get()) {
                if (instruction instanceof InvokeInstruction invoke
                        && seamFor(invoke.owner().asInternalName(), invoke.name().stringValue(),
                                invoke.type().stringValue()) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private Seam seamFor(String owner, String name, String descriptor) {
        for (Seam seam : seams) {
            if (seam.matches(owner, name, descriptor)) {
                return seam;
            }
        }
        return null;
    }

    private boolean isSeamMember(String owner, String name) {
        for (Seam seam : seams) {
            if (seam.matchesMember(owner, name)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ verify

    /** One class's worth of policy checking. Not reused across classes: it dedupes per file. */
    private final class Scan {

        private final String sourceName;
        private final Set<String> own;
        private final List<String> violations = new ArrayList<>();
        /** Dedupes: one bad call in a loop is one diagnostic, not fifty. */
        private final Set<String> reported = new LinkedHashSet<>();

        Scan(String sourceName, Set<String> own) {
            this.sourceName = sourceName;
            this.own = own;
        }

        List<String> verify(ClassModel model) {
            model.superclass().ifPresent(entry -> type(entry.asInternalName()));
            for (ClassEntry iface : model.interfaces()) {
                type(iface.asInternalName());
            }
            for (ClassElement element : model) {
                if (element instanceof FieldModel field) {
                    descriptorTypes(field.fieldType().stringValue());
                } else if (element instanceof MethodModel method) {
                    descriptorTypes(method.methodType().stringValue());
                    method.code().ifPresent(this::code);
                }
            }
            return violations;
        }

        private void code(CodeModel code) {
            for (CodeElement element : code) {
                if (element instanceof InvokeInstruction invoke) {
                    member(invoke.owner().asInternalName(), invoke.name().stringValue(),
                            invoke.type().stringValue());
                } else if (element instanceof FieldInstruction field) {
                    member(field.owner().asInternalName(), field.name().stringValue(),
                            field.type().stringValue());
                } else if (element instanceof NewObjectInstruction created) {
                    type(created.className().asInternalName());
                } else if (element instanceof TypeCheckInstruction check) {
                    type(check.type().asInternalName());
                } else if (element instanceof NewReferenceArrayInstruction array) {
                    type(array.componentType().asInternalName());
                } else if (element instanceof NewMultiArrayInstruction array) {
                    type(array.arrayType().asInternalName());
                } else if (element instanceof ExceptionCatch caught) {
                    caught.catchType().ifPresent(entry -> type(entry.asInternalName()));
                } else if (element instanceof InvokeDynamicInstruction indy) {
                    dynamic(indy);
                } else if (element instanceof java.lang.classfile.instruction.ConstantInstruction loaded) {
                    constant(loaded.constantValue());
                }
            }
        }

        private void dynamic(InvokeDynamicInstruction indy) {
            DirectMethodHandleDesc bootstrap;
            try {
                bootstrap = indy.bootstrapMethod();
            } catch (RuntimeException unreadable) {
                report("an unreadable dynamic call site (" + unreadable + ")");
                return;
            }
            String owner = internalName(bootstrap.owner());
            if (!ALLOWED_BOOTSTRAPS.contains(owner)) {
                report("a dynamic call site bootstrapped by " + dotted(owner) + "."
                        + bootstrap.methodName()
                        + " (only lambdas, string concatenation, records and switches are allowed)");
                return;
            }
            descriptorTypes(indy.type().stringValue());
            for (ConstantDesc argument : indy.bootstrapArgs()) {
                constant(argument);
            }
        }

        /**
         * A loadable constant: a class literal, a method type, or — the
         * interesting one — a method handle. javac hides every lambda body and
         * every method reference target behind a method handle in an
         * {@code invokedynamic}'s bootstrap arguments, so this is where
         * {@code Thread::start} is caught.
         */
        private void constant(ConstantDesc value) {
            if (value instanceof ClassDesc type) {
                type(internalName(type));
            } else if (value instanceof MethodTypeDesc type) {
                descriptorTypes(type.descriptorString());
            } else if (value instanceof DirectMethodHandleDesc handle) {
                String owner = internalName(handle.owner());
                String name = handle.methodName();
                if (isSeamMember(owner, name)) {
                    report("a method reference to " + dotted(owner) + "." + name
                            + " (call it directly — the host can only intercept a real call site)");
                    return;
                }
                member(owner, name, handle.lookupDescriptor());
            } else if (value instanceof DynamicConstantDesc<?> dynamic) {
                String owner = internalName(dynamic.bootstrapMethod().owner());
                if (!ALLOWED_CONSTANT_BOOTSTRAPS.contains(owner)) {
                    report("a dynamic constant bootstrapped by " + dotted(owner) + "."
                            + dynamic.bootstrapMethod().methodName());
                    return;
                }
                for (ConstantDesc argument : dynamic.bootstrapArgsList()) {
                    constant(argument);
                }
            }
        }

        /** A reference to {@code owner}'s member, plus everything its descriptor names. */
        private void member(String owner, String name, String descriptor) {
            String internal = normalize(owner);
            if (internal != null && !own.contains(internal)) {
                SurgeonPolicy.Denial denial = policy.denialFor(internal, name);
                if (denial != null) {
                    report(dotted(internal) + "." + name + " — " + denial.detail());
                    return;
                }
            }
            type(owner);
            descriptorTypes(descriptor);
        }

        /** A reference to a type, in internal-name or descriptor form. */
        private void type(String reference) {
            String internal = normalize(reference);
            if (internal == null || own.contains(internal)) {
                return;
            }
            SurgeonPolicy.Denial denial = policy.denialFor(internal, null);
            if (denial != null) {
                report(dotted(internal) + " — " + denial.detail());
                return;
            }
            if (!policy.allows(internal)) {
                report(dotted(internal)
                        + " is outside the packages a generated mod may use ("
                        + String.join(", ", dottedRoots()) + ")");
            }
        }

        /** Every {@code L…;} inside a field or method descriptor. */
        private void descriptorTypes(String descriptor) {
            if (descriptor == null) {
                return;
            }
            int i = 0;
            while (i < descriptor.length()) {
                char c = descriptor.charAt(i);
                if (c == 'L') {
                    int end = descriptor.indexOf(';', i);
                    if (end < 0) {
                        return;
                    }
                    type(descriptor.substring(i + 1, end));
                    i = end + 1;
                } else {
                    i++;
                }
            }
        }

        private void report(String detail) {
            if (reported.add(detail)) {
                violations.add(sourceName + ": error: forbidden API: " + detail);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<String> dottedRoots() {
        List<String> out = new ArrayList<>(policy.allowedRoots().size());
        for (String root : policy.allowedRoots()) {
            out.add(root.replace('/', '.') + "*");
        }
        return out;
    }

    /**
     * Internal name for a reference that may be an internal name, a field
     * descriptor or an array of either; null when it denotes a primitive (or an
     * array of primitives), which has nothing to check.
     */
    private static String normalize(String reference) {
        if (reference == null || reference.isEmpty()) {
            return null;
        }
        String s = reference;
        int dims = 0;
        while (dims < s.length() && s.charAt(dims) == '[') {
            dims++;
        }
        s = s.substring(dims);
        if (s.isEmpty()) {
            return null;
        }
        if (s.charAt(0) == 'L' && s.endsWith(";")) {
            return s.substring(1, s.length() - 1);
        }
        // A bare primitive descriptor letter after stripping array dimensions.
        if (s.length() == 1 && "ZBCSIJFDV".indexOf(s.charAt(0)) >= 0) {
            return null;
        }
        return s;
    }

    private static String internalName(ClassDesc type) {
        String internal = normalize(type.descriptorString());
        return internal == null ? "" : internal;
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }

    /**
     * {@code vibemod.foo.Bar$Baz} -&gt; {@code Bar.java}: diagnostics have to
     * name the file the model wrote, not the class javac emitted, or a repair
     * round is told to fix a file that does not exist.
     */
    private static String sourceNameOf(String binaryName) {
        String name = binaryName;
        int dollar = name.indexOf('$');
        if (dollar > 0) {
            name = name.substring(0, dollar);
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        return name + ".java";
    }
}
