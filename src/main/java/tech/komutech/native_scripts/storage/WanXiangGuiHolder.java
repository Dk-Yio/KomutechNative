package tech.komutech.native_scripts.storage;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class WanXiangGuiHolder implements InventoryHolder {

    public final String storageName;
    public final String page;
    public final String mode;

    public WanXiangGuiHolder(String storageName, String page, String mode) {
        this.storageName = storageName == null ? "" : storageName;
        this.page = page == null ? "1" : page;
        this.mode = mode == null ? "normal" : mode;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
