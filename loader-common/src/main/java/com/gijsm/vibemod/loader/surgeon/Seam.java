package com.gijsm.vibemod.loader.surgeon;

/**
 * One call site the surgeon redirects (V3 Phase 0 §A).
 *
 * <p>The rewrite is always the same shape and that is what makes it safe: an
 * {@code invokevirtual}/{@code invokeinterface} whose receiver and arguments
 * are already on the stack becomes an {@code invokestatic} to a host shim whose
 * descriptor is the original descriptor <em>with the receiver type prepended</em>.
 * The stack effect is identical, so no shuffling, no locals, no frame surgery.
 *
 * <p>Phase 0's table has exactly two entries, both for Fabric's
 * {@code Event.register}, supplied by the Fabric host at wiring time. The
 * surgeon itself knows no loader: it is handed a table (V3 Phase 0 §A) and
 * NeoForge hands it an empty one.
 *
 * @param owner      internal name of the type the call site names as receiver,
 *                   e.g. {@code net/fabricmc/fabric/api/event/Event}
 * @param name       the intercepted method's name
 * @param descriptor the intercepted method's <em>erased</em> descriptor, e.g.
 *                   {@code (Ljava/lang/Object;)V} for {@code Event.register(T)}
 * @param shimOwner  internal name of the host class carrying the replacement
 * @param shimName   the replacement static method's name
 * @param shimDescriptor the replacement's descriptor: {@code descriptor} with
 *                   {@code L<owner>;} prepended to the parameter list
 */
public record Seam(String owner, String name, String descriptor,
                   String shimOwner, String shimName, String shimDescriptor) {

    /**
     * The common case: builds {@link #shimDescriptor} by prepending the
     * receiver type to {@code descriptor}, so a caller cannot get the two out
     * of step by hand.
     */
    public static Seam prependingReceiver(String owner, String name, String descriptor,
                                          String shimOwner, String shimName) {
        int open = descriptor.indexOf('(');
        if (open != 0) {
            throw new IllegalArgumentException("not a method descriptor: " + descriptor);
        }
        String shimDescriptor = "(L" + owner + ";" + descriptor.substring(1);
        return new Seam(owner, name, descriptor, shimOwner, shimName, shimDescriptor);
    }

    /**
     * The other shape: an {@code invokestatic} that already has no receiver, so
     * the shim's descriptor is the original one unchanged and only the owner
     * moves (V3 Phase 1 §C/§D).
     *
     * <p>{@code KeyBindingHelper.registerKeyMapping(mapping)} and
     * {@code HudElementRegistry.addLast(id, element)} are both static factory
     * calls: nothing is pushed as a receiver, so prepending one would leave the
     * verifier looking for an argument that is not on the stack. Keeping the two
     * constructions apart — rather than making {@link #prependingReceiver} guess
     * from the descriptor — means the caller states which kind of call site it
     * is intercepting, and a mistake is a link error at the shim rather than a
     * silent stack mismatch.
     */
    public static Seam staticCall(String owner, String name, String descriptor,
                                  String shimOwner, String shimName) {
        if (descriptor.indexOf('(') != 0) {
            throw new IllegalArgumentException("not a method descriptor: " + descriptor);
        }
        return new Seam(owner, name, descriptor, shimOwner, shimName, descriptor);
    }

    /** Whether this seam covers a call to {@code owner}.{@code name}{@code descriptor}. */
    boolean matches(String callOwner, String callName, String callDescriptor) {
        return owner.equals(callOwner) && name.equals(callName) && descriptor.equals(callDescriptor);
    }

    /** Whether this seam covers {@code owner}.{@code name} at any descriptor (for method-reference detection). */
    boolean matchesMember(String callOwner, String callName) {
        return owner.equals(callOwner) && name.equals(callName);
    }
}
