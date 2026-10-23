package tech.komutech.native_scripts.support;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class MainThread {

    private MainThread() {
    }

    public static void run(Plugin plugin, Runnable task) {
        if (plugin == null || task == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public static void runAsync(Plugin plugin, Runnable task) {
        if (plugin == null || task == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            task.run();
        }
    }
}
