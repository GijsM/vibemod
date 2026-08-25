package com.gijsm.vibemod.neoforge;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import com.gijsm.vibemod.runtime.ModLifecycle;

/**
 * Answers ARCHITECTURE-V2 §10.3's open question for NeoForge — <b>empirically,
 * on the machine that is actually running</b> — by defining a class file at the
 * JVM's own version through the exact loader generated mods are loaded by, and
 * seeing whether it survives.
 *
 * <p>The question is what {@code maxTargetRelease()} may be. It is not
 * theoretical: Paper's answer is <em>lower</em> than the JVM's, because its
 * plugin remapper pipes every dynamically defined class through an ASM that
 * rejects newer class files. §10.3 warned that ModLauncher transforms far more
 * aggressively than Knot and that Phase E might therefore inherit Paper's
 * problem rather than Fabric's answer.
 *
 * <p>Reasoning says no: FML's class processors (access transformers, mixin, the
 * coremod pipeline) run inside the transforming class loader's own
 * {@code findClass}, so they only ever see classes it is asked to LOAD, and
 * {@link ModLifecycle.BytesClassLoader} is a child that calls
 * {@code defineClass} on bytes it already holds. Nothing reads them.
 *
 * <p>But reasoning about someone else's class loader is exactly the kind of
 * thing that is right until a release changes it, so this measures instead —
 * and it measures through {@code ModLifecycle.BytesClassLoader} itself, not a
 * lookalike, so there is no gap between the probe and the real path. The result
 * goes into the boot log on every start, and the acceptance gate asserts on it.
 */
public final class ClassFileCeiling {

    /** JVMS: class-file major version 65 is Java 21, 69 is Java 25. */
    private static final int MAJOR_OFFSET = 44;

    private ClassFileCeiling() {
    }

    /** One log line: what was tried, and what happened. */
    public static String describe() {
        int feature = Runtime.version().feature();
        boolean ok = canDefine(feature);
        return "java" + feature + " class files (major " + (feature + MAJOR_OFFSET) + ") "
                + (ok ? "load through BytesClassLoader — the loader does not read hot-loaded bytecode"
                      : "are REJECTED by this loader; maxTargetRelease() must be lowered");
    }

    /**
     * Defines a minimal class at {@code release}'s class-file version through
     * the real hot-load path.
     *
     * @return true when the JVM accepted it
     */
    public static boolean canDefine(int release) {
        String name = "vibemod.probe.CeilingProbe" + release;
        byte[] bytes = minimalClass(name.replace('.', '/'), release + MAJOR_OFFSET);
        try {
            ClassLoader loader = new ModLifecycle.BytesClassLoader(
                    ClassFileCeiling.class.getClassLoader(), Map.of(name, bytes));
            return Class.forName(name, false, loader) != null;
        } catch (Throwable rejected) {
            return false;
        }
    }

    /**
     * The smallest legal class file: {@code public class <name> extends Object},
     * no fields, no methods, no attributes.
     *
     * <p>Hand-assembled rather than compiled, because the point is to control
     * the version number exactly and to depend on nothing — this runs before the
     * compiler backend has been resolved. No methods means nothing for the
     * verifier to check, so what is being measured is purely "will this loader
     * chain accept this class-file version".
     */
    private static byte[] minimalClass(String internalName, int major) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        try (DataOutputStream d = new DataOutputStream(out)) {
            d.writeInt(0xCAFEBABE);
            d.writeShort(0);            // minor
            d.writeShort(major);        // major
            d.writeShort(5);            // constant_pool_count = entries + 1
            d.writeByte(7);             // #1 CONSTANT_Class
            d.writeShort(2);            //    name_index -> #2
            d.writeByte(1);             // #2 CONSTANT_Utf8
            d.writeUTF(internalName);
            d.writeByte(7);             // #3 CONSTANT_Class
            d.writeShort(4);            //    name_index -> #4
            d.writeByte(1);             // #4 CONSTANT_Utf8
            d.writeUTF("java/lang/Object");
            d.writeShort(0x0021);       // ACC_PUBLIC | ACC_SUPER
            d.writeShort(1);            // this_class  -> #1
            d.writeShort(3);            // super_class -> #3
            d.writeShort(0);            // interfaces
            d.writeShort(0);            // fields
            d.writeShort(0);            // methods
            d.writeShort(0);            // attributes
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return out.toByteArray();
    }
}
