package com.gijsm.vibemod.paper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import com.gijsm.vibemod.platform.EventBridge;
import com.gijsm.vibemod.platform.Registration;
import com.gijsm.vibemod.runtime.ModDispatch;

/**
 * {@link EventBridge} over Bukkit's event system: the {@code @EventHandler} scan,
 * the per-method {@link EventExecutor}, and the {@link HandlerList} teardown that
 * v1 kept inline inside {@code ModRegistry}.
 *
 * <p>Registration is per method rather than per listener object, which is what
 * lets each callback be individually wrapped in the watchdog and the mod's error
 * accounting — the whole point of the bridge (ARCHITECTURE-V2 §2). Bukkit's own
 * {@code registerEvents} would give us one opaque registration and no place to
 * hang a timer.
 */
public final class PaperEventBridge implements EventBridge {

    private final Plugin plugin;
    private final ModDispatch dispatch;

    public PaperEventBridge(Plugin plugin, ModDispatch dispatch) {
        this.plugin = plugin;
        this.dispatch = dispatch;
    }

    @Override
    public Registration listen(Object nativeListener, String modName) {
        if (!(nativeListener instanceof Listener listener)) {
            throw new IllegalArgumentException("Paper listeners must implement org.bukkit.event.Listener, got "
                    + (nativeListener == null ? "null" : nativeListener.getClass().getName()));
        }
        for (Method method : eventHandlerMethods(listener.getClass())) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !Event.class.isAssignableFrom(params[0])) {
                continue;
            }
            Class<? extends Event> eventClass = params[0].asSubclass(Event.class);
            EventHandler annotation = method.getAnnotation(EventHandler.class);
            method.setAccessible(true);
            EventExecutor executor = (registered, event) -> {
                if (!eventClass.isInstance(event)) {
                    return;
                }
                dispatch.run(modName, null, "listener:" + eventClass.getSimpleName(), () -> {
                    try {
                        method.invoke(listener, event);
                    } catch (InvocationTargetException ite) {
                        Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                        if (cause instanceof Exception e) {
                            throw e;
                        }
                        throw new IllegalStateException(cause);
                    }
                });
            };
            plugin.getServer().getPluginManager().registerEvent(eventClass, listener, annotation.priority(),
                    executor, plugin, annotation.ignoreCancelled());
        }
        // HandlerList.unregisterAll(listener) removes every handler this object
        // contributed, which is exactly the whole-object revocation the contract
        // promises - so one registration per listener object, not per method.
        return Registration.of(() -> HandlerList.unregisterAll(listener));
    }

    /** Every non-bridge, non-synthetic {@code @EventHandler} method on a class, walking up superclasses. */
    private static List<Method> eventHandlerMethods(Class<?> cls) {
        List<Method> result = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                if (m.isAnnotationPresent(EventHandler.class)) {
                    result.add(m);
                }
            }
        }
        return result;
    }
}
