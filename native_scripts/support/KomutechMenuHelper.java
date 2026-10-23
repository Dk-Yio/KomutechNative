package tech.komutech.native_scripts.support;

import org.bukkit.inventory.ItemStack;

public final class KomutechMenuHelper {

    private KomutechMenuHelper() {
    }

    public static void replaceExistingItem(Object menu, int slot, ItemStack item) {
        if (menu == null) {
            return;
        }
        try {
            menu.getClass().getMethod("replaceExistingItem", int.class, ItemStack.class)
                .invoke(menu, slot, item);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    public static ItemStack[] getContents(Object menu) {
        if (menu == null) {
            return new ItemStack[0];
        }
        try {
            Object result = menu.getClass().getMethod("getContents").invoke(menu);
            return result instanceof ItemStack[] contents ? contents : new ItemStack[0];
        } catch (ReflectiveOperationException exception) {
            return new ItemStack[0];
        }
    }
}
