package tech.komutech.native_scripts.support;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;

public interface KomutechMenuHandler {

    boolean handles(InventoryView view);

    void onClick(InventoryClickEvent event);

    default void onDrag(InventoryDragEvent event) {
    }

    default void onClose(InventoryCloseEvent event) {
    }
}
