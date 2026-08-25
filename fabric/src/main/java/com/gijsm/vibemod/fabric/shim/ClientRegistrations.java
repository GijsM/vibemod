package com.gijsm.vibemod.fabric.shim;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.KeyMapping;

import com.gijsm.vibemod.platform.Registration;

/**
 * The two client registrations a native mod makes through the loader's own API
 * (V3 Phase 1 §C, §D), as the shims see them.
 *
 * <p>Separate from {@link ClientSeam} for one mechanical reason: these method
 * descriptors name {@code KeyMapping} and {@code HudElement}, which do not exist
 * on a dedicated server. {@code ClientSeam} is loaded there (the fanout and the
 * entrypoint adapter both hold one); this interface is loaded only by
 * {@link ClientShims}, and {@code ClientShims} is only ever reached from
 * rewritten mod bytecode that already had to link against those same classes to
 * compile at all.
 */
public interface ClientRegistrations {

    /**
     * Leases one of the eight pooled slots (§8.2, §C).
     *
     * @param modName   the mod to journal and release against
     * @param requested the mapping the mod built; read for its translation key,
     *                  category and default binding, never registered
     * @return the pooled mapping to poll — <em>not</em> {@code requested} — and
     *         the revocation that frees the slot
     * @throws IllegalStateException when all eight slots are leased
     */
    Leased leaseKeyMapping(String modName, KeyMapping requested);

    /** A leased slot: the mapping the mod polls, and the lease that frees it. */
    record Leased(KeyMapping mapping, Registration release) {
    }

    /**
     * Attaches a mod's {@code HudElement} to the host's single permanent HUD
     * element (§D). Watchdogged, journalled, and detached the first time it
     * throws, like every other render-thread dispatch.
     */
    Registration addHud(String modName, HudElement element);
}
