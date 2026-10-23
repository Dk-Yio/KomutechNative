package tech.komutech.native_scripts.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

public final class KomutechMenuRouter implements Listener {

    private static final List<KomutechMenuHandler> HANDLERS = new CopyOnWriteArrayList<>();
    private static final Map<Inventory, KomutechMenuHandler> INVENTORY_HANDLERS = new ConcurrentHashMap<>();
    private static KomutechMenuRouter instance;

    private KomutechMenuRouter() {
    }

    public static void register(Plugin plugin) {
        if (instance == null) {
            instance = new KomutechMenuRouter();
            plugin.getServer().getPluginManager().registerEvents(instance, plugin);
        }
    }

    public static void registerHandler(KomutechMenuHandler handler) {
        if (handler != null && !HANDLERS.contains(handler)) {
            HANDLERS.add(handler);
        }
    }

    public static void bindInventory(Inventory inventory, KomutechMenuHandler handler) {
        if (inventory != null && handler != null) {
            INVENTORY_HANDLERS.put(inventory, handler);
        }
    }

    public static void unbindInventory(Inventory inventory) {
        if (inventory != null) {
            INVENTORY_HANDLERS.remove(inventory);
        }
    }

    public static boolean isHandlerFor(Inventory inventory, KomutechMenuHandler handler) {
        return inventory != null && handler != null && handler == INVENTORY_HANDLERS.get(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        KomutechMenuHandler handler = resolve(event.getView().getTopInventory(), event.getView());
        if (handler == null) {
            return;
        }
        handler.onClick(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        KomutechMenuHandler handler = resolve(event.getView().getTopInventory(), event.getView());
        if (handler == null) {
            return;
        }
        handler.onDrag(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        KomutechMenuHandler bound = INVENTORY_HANDLERS.remove(top);
        if (bound != null) {
            bound.onClose(event);
            return;
        }
        KomutechMenuHandler handler = resolve(top, event.getView());
        if (handler != null) {
            handler.onClose(event);
        }
    }

    private static KomutechMenuHandler resolve(Inventory top, org.bukkit.inventory.InventoryView view) {
        KomutechMenuHandler bound = INVENTORY_HANDLERS.get(top);
        if (bound != null) {
            return bound;
        }
        for (KomutechMenuHandler handler : HANDLERS) {
            if (handler.handles(view)) {
                return handler;
            }
        }
        return null;
    }
}
