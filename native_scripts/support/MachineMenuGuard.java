package tech.komutech.native_scripts.support;

import java.util.List;
import java.util.function.IntPredicate;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;
import tech.origintech.native_scripts.support.MachineScriptHelper;

public final class MachineMenuGuard {

    private MachineMenuGuard() {
    }

    public static boolean titleContains(InventoryView view, String... markers) {
        String plain = plainTitle(view);
        if (plain == null || plain.isEmpty()) {
            return false;
        }
        for (String marker : markers) {
            if (marker != null && !marker.isEmpty() && plain.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 标题须同时包含全部 marker，避免短词 OR 匹配误伤第三方 GUI。 */
    public static boolean titleContainsAll(InventoryView view, String... markers) {
        String plain = plainTitle(view);
        if (plain == null || plain.isEmpty() || markers == null || markers.length == 0) {
            return false;
        }
        for (String marker : markers) {
            if (marker == null || marker.isEmpty() || !plain.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isOwnMachine(InventoryView view, String machineId) {
        return MachineScriptHelper.hasMachineId(MachineScriptHelper.resolveBlockMachine(view), machineId);
    }

    public static String plainTitle(InventoryView view) {
        if (view == null) {
            return null;
        }
        for (String candidate : List.of(MenuGuiHelper.legacyTitle(view), view.getTitle())) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            String plain = normalizePlainTitle(candidate);
            if (!plain.isEmpty()) {
                return plain;
            }
        }
        return null;
    }

    public static String normalizePlainTitle(String title) {
        if (title == null) {
            return "";
        }
        String plain = org.bukkit.ChatColor.stripColor(title);
        plain = plain.replaceAll("\\{#[0-9A-Fa-f]{6}\\}", "");
        plain = plain.replaceAll("(?i)&[0-9a-fk-or]", "");
        return plain;
    }

    public static boolean isUltimateSynthProcessorMenu(InventoryView view) {
        if (isOwnMachine(view, "KOMUTECH_L_ZJ_終極合成台")) {
            return true;
        }
        String plain = plainTitle(view);
        if (plain == null || plain.isEmpty()) {
            return false;
        }
        return plain.contains("終極合成台") || plain.contains("终极合成台");
    }

    public static boolean isSlotIn(int slot, int... slots) {
        for (int candidate : slots) {
            if (candidate == slot) {
                return true;
            }
        }
        return false;
    }

    public static void protectTopInventory(InventoryClickEvent event, IntPredicate allowedTopSlot) {
        InventoryView view = event.getView();
        int topSize = view.getTopInventory().getSize();
        int rawSlot = event.getRawSlot();

        if (isSwapOrHotbarAction(event) && rawSlot >= 0 && rawSlot < topSize && !allowedTopSlot.test(rawSlot)) {
            event.setCancelled(true);
            return;
        }

        if (rawSlot < 0 || rawSlot >= topSize) {
            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getAction() == InventoryAction.UNKNOWN && event.getClick() == ClickType.DOUBLE_CLICK
                || event.getClick() == ClickType.DOUBLE_CLICK) {
                event.setCancelled(true);
            }
            return;
        }

        // 允许槽：不要强制 uncancel，否则与 Slimefun 菜单二次结算冲突。
        if (allowedTopSlot.test(rawSlot)) {
            return;
        }
        event.setCancelled(true);
    }

    private static boolean isSwapOrHotbarAction(InventoryClickEvent event) {
        ClickType click = event.getClick();
        InventoryAction action = event.getAction();
        return click == ClickType.NUMBER_KEY
            || click == ClickType.SWAP_OFFHAND
            || action == InventoryAction.HOTBAR_MOVE_AND_READD
            || action == InventoryAction.HOTBAR_SWAP;
    }

    public static void protectTopInventoryDrag(InventoryDragEvent event, IntPredicate allowedTopSlot) {
        InventoryView view = event.getView();
        int topSize = view.getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < topSize && !allowedTopSlot.test(rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
