package com.gijsm.vibemod.fabric.shim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.world.level.block.state.properties.Property;

/**
 * A blockstate property rebuilt from nothing but the strings a save file writes
 * (V4 Phase 1).
 *
 * <p>Vanilla has no property class that can be constructed from a recorded value
 * set. {@code EnumProperty} needs a live {@code Class<T extends Enum<T>>};
 * {@code BooleanProperty} and {@code IntegerProperty} carry their own value
 * domains. A deleted mod's {@code EnumProperty<MyDirection>} named its values
 * through {@code StringRepresentable}, and the class it did that with went away
 * with the mod. What survives is the ledger's list of strings — so the stub's
 * property is a property <em>of strings</em>.
 *
 * <p>{@code Property} has a {@code protected Property(String, Class<T>)}
 * constructor (verified), which is an invitation rather than a loophole: this is
 * HOST code, subclassing a game class the way any Fabric mod does. Only four
 * methods are abstract on it — {@code getPossibleValues}, {@code getName(T)},
 * {@code getValue(String)} and {@code getInternalIndex(T)} — and every one of
 * them is answered directly by the recorded list. The codecs
 * {@code Property.<init>} builds are lambdas over those four, so they come out
 * right for free; that is what makes the save round-trip work rather than merely
 * the state count matching.
 *
 * <h2>The two overrides that are not abstract, and why they are here anyway</h2>
 *
 * <p>{@code Property.equals} compares {@code clazz} and {@code name} only
 * (disassembled), and every {@code StubProperty} shares one {@code clazz}. Left
 * alone, two stubs with a property both called {@code facing} but with different
 * value sets would be {@code equals} and would collide in any map keyed by
 * property. {@code EnumProperty} overrides both for exactly this reason; so does
 * this.
 *
 * <p>The nested {@link Value} deliberately shadows the inherited
 * {@code Property.Value} — they are unrelated types, and inside this file the
 * game's one is always written out in full.
 */
public final class StubProperty extends Property<StubProperty.Value> {

    /**
     * One recorded value: the string a save writes, and its position.
     *
     * <p>{@code Comparable} because {@code Property<T extends Comparable<T>>}
     * demands it, and by ordinal rather than alphabetically because the order
     * that matters is the one the original property had — which is the order the
     * schema recorded, and which decides the cartesian product's layout.
     */
    public static final class Value implements Comparable<Value> {

        private final String name;
        private final int ordinal;

        Value(String name, int ordinal) {
            this.name = name;
            this.ordinal = ordinal;
        }

        /** The string the save codec writes for this value. */
        public String value() {
            return name;
        }

        /** Its index in the recorded value list. */
        public int ordinal() {
            return ordinal;
        }

        @Override
        public int compareTo(Value other) {
            return Integer.compare(ordinal, other.ordinal);
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || (other instanceof Value value && ordinal == value.ordinal
                            && name.equals(value.name));
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + ordinal;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final List<Value> values;
    private final Map<String, Value> byName;

    /**
     * @param name   the property name, exactly as recorded
     * @param values its possible values in recorded order, which is the order
     *               the original {@code getPossibleValues()} returned
     */
    public StubProperty(String name, List<String> values) {
        super(name, Value.class);
        List<Value> built = new ArrayList<>(values.size());
        Map<String, Value> index = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            Value value = new Value(values.get(i), i);
            built.add(value);
            index.put(value.value(), value);
        }
        this.values = List.copyOf(built);
        this.byName = Map.copyOf(index);
    }

    @Override
    public List<Value> getPossibleValues() {
        return values;
    }

    @Override
    public String getName(Value value) {
        return value.value();
    }

    @Override
    public Optional<Value> getValue(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    @Override
    public int getInternalIndex(Value value) {
        return value.ordinal();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof StubProperty stub
                && getName().equals(stub.getName())
                && values.equals(stub.values);
    }

    @Override
    public int generateHashCode() {
        return 31 * super.generateHashCode() + values.hashCode();
    }
}
