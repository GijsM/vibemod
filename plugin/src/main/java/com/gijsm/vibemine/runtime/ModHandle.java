package com.gijsm.vibemine.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import com.gijsm.vibemine.api.ModCommandHandler;

/**
 * Live handle on one loaded mod. The read surface below is for UI callers;
 * the mutators and tracking collections are package-private and used only by
 * {@link ModRegistry} to build up and tear down a mod's registrations.
 */
public final class ModHandle {

    private final String name;
    private final int version;
    private final String description;
    volatile boolean enabled;

    final Set<Listener> listeners = new LinkedHashSet<>();
    final List<BukkitTask> tasks = new ArrayList<>();
    final List<String> commandNames = new ArrayList<>();
    final Map<String, ModCommandHandler> actions = new LinkedHashMap<>();

    ModHandle(String name, int version, String description) {
        this.name = name;
        this.version = version;
        this.description = description;
    }

    public String name() {
        return name;
    }

    public int version() {
        return version;
    }

    public String description() {
        return description;
    }

    public boolean enabled() {
        return enabled;
    }

    public int listenerCount() {
        return listeners.size();
    }

    public int taskCount() {
        return tasks.size();
    }

    public List<String> commandNames() {
        return List.copyOf(commandNames);
    }

    public List<String> actionNames() {
        return List.copyOf(actions.keySet());
    }
}
