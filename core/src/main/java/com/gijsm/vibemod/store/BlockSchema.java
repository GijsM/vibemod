package com.gijsm.vibemod.store;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Everything about a block's state schema that a later boot needs in order to
 * rebuild it (V4 Phase 1).
 *
 * <p>This record exists because a block id can never be released. When the mod
 * that registered one is deleted, the id has to keep coming back on every
 * subsequent boot — see {@link RegistryLedger}'s class comment for the palette
 * shift that makes the alternative catastrophic — and "coming back" is not
 * satisfied by any old block under the right name. A saved chunk stores a state
 * as its property <em>names and value strings</em>
 * ({@code {"Name":"…","Properties":{"lit":"true"}}}), so a replacement that
 * offers a different property set decodes to a different state, or fails to
 * decode at all, which is the shift again.
 *
 * <p>So three things are recorded, and only three:
 *
 * <ul>
 *   <li>the id, so the replay knows what to register it as;</li>
 *   <li>every property's name and its possible values <em>as the strings the
 *       save codec writes</em> — {@code Property.getName(T)}, never
 *       {@code toString()};</li>
 *   <li>the total state count, as a checksum.</li>
 * </ul>
 *
 * <p><b>Reconstruction order needs no recording.</b>
 * {@code StateDefinition.propertiesByName} is an {@code ImmutableSortedMap}
 * (verified), so the cartesian product a rebuilt block produces is a pure
 * function of the properties sorted by name — whatever order they were added
 * in. Value order within one property <em>is</em> recorded, because
 * {@code getPossibleValues()} order is the property's own and nothing sorts it.
 *
 * <p>The state count is the checksum rather than the specification, and
 * {@link #problems()} is what makes it useful: a stub whose rebuilt state count
 * disagrees with the recorded one must refuse to register, because a wrong stub
 * is worse than a missing one. A missing one is a loud decode error; a wrong one
 * silently decodes a saved property to a different state.
 *
 * <p>Pure JDK by rule — {@code core} names no platform type, and
 * {@code :core:checkPlatformFree} fails the build over it — so a property is a
 * name and a list of strings, and the block side of the translation lives in
 * the host's {@code StubBlock}.
 */
public record BlockSchema(String id, List<Prop> properties, int stateCount) {

    /**
     * Vanilla's own rule for a property or value name, lifted from
     * {@code StateDefinition.NAME_PATTERN} ({@code ^[a-z0-9_]+$}, verified).
     *
     * <p>Checked here rather than left to the game because
     * {@code StateDefinition$Builder.validateProperty} throws
     * {@code IllegalArgumentException} from inside {@code Block.<init>} — which
     * is to say from inside the constructor of an object that has already taken
     * an intrusive registry holder. Refusing before construction is the
     * difference between a named refusal and a half-built block.
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]+$");

    /** One property: its name, and its values as the save codec writes them. */
    public record Prop(String name, List<String> values) {
        public Prop {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public BlockSchema {
        properties = properties == null ? List.of() : List.copyOf(properties);
    }

    /**
     * The state count the recorded properties actually imply — the product of
     * the value-set sizes, and 1 for a block with no properties at all.
     *
     * <p>Computed rather than trusted. {@link #stateCount()} is what the game
     * said when the block was registered; this is what the record on disk can
     * still prove. They disagree only when the record was truncated, hand-edited
     * or written by an older schema, and that disagreement is exactly what
     * {@link #problems()} refuses on.
     */
    public int cartesianStateCount() {
        long product = 1;
        for (Prop prop : properties) {
            product *= prop.values().size();
            if (product > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) product;
    }

    /**
     * Every reason this schema cannot be rebuilt into a block, in words a log
     * line can carry; empty when it can.
     *
     * <p>All of them at once rather than the first: a schema that is wrong is
     * wrong on disk, nobody is going to fix it by hand twice, and the operator
     * reading the line deserves the whole picture of what was lost.
     */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        if (id == null || id.isBlank()) {
            out.add("it has no block id");
        }
        Set<String> seen = new HashSet<>();
        for (Prop prop : properties) {
            String name = prop.name();
            if (name == null || !NAME_PATTERN.matcher(name).matches()) {
                out.add("property name " + name + " is not " + NAME_PATTERN.pattern()
                        + ", which StateDefinition rejects from inside Block.<init>");
                continue;
            }
            if (!seen.add(name)) {
                out.add("property " + name + " is recorded twice");
            }
            if (prop.values().size() < 2) {
                out.add("property " + name + " has " + prop.values().size()
                        + " value(s); StateDefinition requires more than one");
            }
            Set<String> values = new HashSet<>();
            for (String value : prop.values()) {
                if (value == null || !NAME_PATTERN.matcher(value).matches()) {
                    out.add("value " + value + " of property " + name + " is not "
                            + NAME_PATTERN.pattern());
                } else if (!values.add(value)) {
                    out.add("value " + value + " of property " + name + " is recorded twice");
                }
            }
        }
        int implied = cartesianStateCount();
        if (implied != stateCount) {
            out.add("the recorded state count is " + stateCount + " but the recorded properties "
                    + "imply " + implied + "; a stub built from this would decode saved properties "
                    + "to the wrong state index");
        }
        return out;
    }

    /** True when {@link #problems()} is empty — a schema a stub may be built from. */
    public boolean usable() {
        return problems().isEmpty();
    }

    /** {@code "vibemod_x:ruby_block[lit=false|true]"} — for a log line or a refusal. */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(id == null ? "(no id)" : id);
        if (!properties.isEmpty()) {
            out.append('[');
            for (int i = 0; i < properties.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(properties.get(i).name()).append('=')
                        .append(String.join("|", properties.get(i).values()));
            }
            out.append(']');
        }
        return out.append(" x").append(stateCount).toString();
    }
}
