package io.github.warleysr.autopix.events;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;

import io.github.warleysr.autopix.AutoPix;

public final class AutoPixEventDispatcher {

    private static final List<Consumer<Event>> TEST_LISTENERS = new CopyOnWriteArrayList<>();

    private AutoPixEventDispatcher() {
    }

    public static void dispatch(Event event) {
        if (event == null) return;

        for (Consumer<Event> listener : TEST_LISTENERS) {
            try {
                listener.accept(event);
            } catch (Exception ignored) {
            }
        }

        if (Bukkit.getServer() == null) {
            return;
        }

        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
            return;
        }

        AutoPix plugin = AutoPix.getInstance();
        if (plugin != null && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));
        }
    }

    public static void registerTestListener(Consumer<Event> listener) {
        if (listener != null) {
            TEST_LISTENERS.add(listener);
        }
    }

    public static void clearTestListeners() {
        TEST_LISTENERS.clear();
    }
}
