package com.gijsm.vibemod.runtime;

/**
 * The platform half of a mod's lifecycle: instantiate the generated main class,
 * hand it the host's {@code VibeContext}, and call {@code onEnable} /
 * {@code onDisable}.
 *
 * <p>This is why {@link ModLifecycle} can live in core at all. The sdk contract
 * a generated mod codes against is platform-typed by design — Paper's
 * {@code VibeContext} speaks {@code org.bukkit}, the loaders' speaks
 * {@code net.minecraft} (ARCHITECTURE-V2 §4.1) — so core cannot name
 * {@code Mod} or {@code VibeContext} without dragging a platform in. It does
 * not need to: everything core owns (states, versions, restore-on-boot,
 * error-storm policy, {@code Registration} draining) is expressible over an
 * opaque activation token.
 *
 * <p>Called on the main server thread only. Every registration the context
 * makes must be tracked on the passed {@link ModHandle} via
 * {@link ModHandle#track}; {@link ModLifecycle} drains it on teardown.
 */
public interface ModHost {

    /**
     * Loads {@code mainClassFqcn} from {@code loader}, builds a context bound to
     * {@code handle}, and calls the mod's {@code onEnable}.
     *
     * @return an opaque token identifying this activation, handed back to
     *         {@link #deactivate}; never null
     * @throws ModLoadException with {@code where() == null} when the class could
     *         not be instantiated at all, and {@code where() == "onEnable"} when
     *         the mod's own {@code onEnable} threw
     */
    Object activate(ModHandle handle, ClassLoader loader, String mainClassFqcn) throws ModLoadException;

    /**
     * Calls the mod's {@code onDisable}. Anything thrown is reported by the
     * caller as an {@code "onDisable"} error and does not abort teardown.
     */
    void deactivate(ModHandle handle, Object activation) throws Exception;
}
