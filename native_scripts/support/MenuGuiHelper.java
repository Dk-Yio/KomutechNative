package tech.komutech.native_scripts.support;

import java.util.List;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class MenuGuiHelper {

    private MenuGuiHelper() {
    }

    public static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (name != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        }
        if (lore != null) {
            meta.setLore(lore.stream().map(line -> ChatColor.translateAlternateColorCodes('&', line)).toList());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public static ItemStack item(String materialName, String name, List<String> lore) {
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.PAPER;
        }
        return item(material, name, lore);
    }

    public static ItemStack border() {
        return item(Material.WHITE_STAINED_GLASS_PANE, " ", null);
    }

    public static Inventory create(int size, String title) {
        return Bukkit.createInventory(null, size, ChatColor.translateAlternateColorCodes('&', title));
    }

    public static String legacyTitle(InventoryView view) {
        try {
            return LegacyComponentSerializer.legacySection().serialize(view.title());
        } catch (Throwable ignored) {
            return view.getTitle();
        }
    }

    public static boolean titleEquals(InventoryView view, String expected) {
        return expected.equals(legacyTitle(view));
    }

    public static boolean titleStartsWith(InventoryView view, String prefix) {
        return legacyTitle(view).startsWith(prefix);
    }

    public static boolean isWanXiangGuiTitle(InventoryView view) {
        if (view == null) {
            return false;
        }
        if (view.getTopInventory().getHolder() instanceof tech.komutech.native_scripts.storage.WanXiangGuiHolder) {
            return true;
        }
        for (String candidate : List.of(legacyTitle(view), view.getTitle())) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            String plain = ChatColor.stripColor(candidate);
            if (containsWanXiangMarker(plain)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWanXiangMarker(String plain) {
        if (plain == null || plain.isEmpty()) {
            return false;
        }
        boolean hasName = plain.contains("萬象") || plain.contains("万象");
        if (!hasName) {
            return false;
        }
        boolean hasContainer = plain.contains("匱") || plain.contains("匣");
        boolean hasPage = plain.contains("第") && (plain.contains("页") || plain.contains("頁"));
        return hasContainer || hasPage;
    }

    public static void applyBorder(Inventory inventory, int... slots) {
        ItemStack border = border();
        for (int slot : slots) {
            inventory.setItem(slot, border.clone());
        }
    }
}
