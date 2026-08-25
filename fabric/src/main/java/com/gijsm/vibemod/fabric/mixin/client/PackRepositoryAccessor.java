package com.gijsm.vibemod.fabric.mixin.client;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;

/**
 * The second mixin, and the reason it has to exist (V3 Phase 2 §D).
 *
 * <p>{@code PackRepository.sources} is {@code private final Set<RepositorySource>}
 * assigned from {@code ImmutableSet.copyOf(varargs)} in the constructor
 * (verified by disassembly). There is no add method, no setter and no event —
 * so a resource pack that did not exist when the client was constructed cannot
 * reach the client's repository through any public API at all. fabric-api's own
 * resource loader solves this the same way.
 *
 * <p>An accessor rather than a constructor injection, on purpose. The
 * constructor runs for the <em>server's</em> pack repository too, and a
 * ctor-tail mixin would then have to guess which repository it is looking at by
 * sniffing the sources it was handed. Reading the field off the object
 * {@code Minecraft#getResourcePackRepository()} hands back asks the question
 * directly instead, and it works whenever we get around to asking — which
 * matters, because {@code Minecraft}'s own repository field is not assigned
 * until part way through its constructor, well after Fabric runs client
 * entrypoints.
 *
 * <p>The set is replaced wholesale rather than added to: the one vanilla builds
 * is immutable.
 */
@Mixin(PackRepository.class)
public interface PackRepositoryAccessor {

    @Accessor("sources")
    Set<RepositorySource> getSources();

    @Mutable
    @Accessor("sources")
    void setSources(Set<RepositorySource> sources);
}
