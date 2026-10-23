package tech.komutech.native_scripts.machine;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.MachineMenuGuard;

public final class SuPeiTaiScript implements NativeScript, NativeLifecycleScript, KomutechMenuHandler {

    private static final Map<Integer, String> RECIPE = Map.of(
        12, "KOMUTECH_L_DJ_JPLS",
        13, "KOMUTECH_L_DJ_蕴灵身",
        14, "KOMUTECH_L_DJ_JPLS",
        21, "KOMUTECH_L_JCWP_SPLNHX",
        22, "KOMUTECH_L_WD_WDS",
        23, "KOMUTECH_L_JCWP_SPLNHX",
        30, "KOMUTECH_L_DJ_JPLS",
        31, "KOMUTECH_L_JQ_FZCZQ",
        32, "KOMUTECH_L_DJ_JPLS"
    );
    private static final String OUTPUT_ITEM = "KOMUTECH_L_JQ_万相悟道仪";
    private static final int WORK_SLOT = 41;
    private static final int OUTPUT_SLOT = 49;
    private static final int[] RECIPE_SLOTS = {12, 13, 14, 21, 22, 23, 30, 31, 32};

    @Override
    public void registerLifecycle(Plugin plugin) {
        KomutechMenuRouter.registerHandler(this);
    }

    @Override
    public boolean handles(InventoryView view) {
        if (MachineMenuGuard.isOwnMachine(view, "KOMUTECH_L_WD_WXZZY")) {
            return true;
        }
        return MachineMenuGuard.titleContainsAll(view, "万相", "制造");
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onOpen".equals(functionName) && args[0] instanceof Player player) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (handles(player.getOpenInventory())) {
                KomutechMenuRouter.bindInventory(top, this);
            }
            return null;
        }
        if ("onClick".equals(functionName) && args.length >= 4 && args[0] instanceof Player player) {
            int slot = (Integer) args[1];
            Object clickAction = args[3];
            if (isAllowedSlot(slot)) {
                return null;
            }
            if (slot == WORK_SLOT && !KomutechSupport.isRightClick(clickAction) && !KomutechSupport.isShiftClick(clickAction)) {
                tryCraft(player);
            }
            return Boolean.FALSE;
        }
        return null;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!handles(event.getView())) {
            return;
        }
        int rawSlot = event.getRawSlot();
        MachineMenuGuard.protectTopInventory(event, this::isAllowedSlot);
        if (event.isCancelled() && rawSlot == WORK_SLOT
            && !KomutechSupport.isRightClick(event) && !KomutechSupport.isShiftClick(event)) {
            tryCraft(player);
        }
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        if (handles(event.getView())) {
            MachineMenuGuard.protectTopInventoryDrag(event, this::isAllowedSlot);
        }
    }

    private boolean isAllowedSlot(int slot) {
        return MachineMenuGuard.isSlotIn(slot, RECIPE_SLOTS) || slot == OUTPUT_SLOT;
    }

    private void tryCraft(Player player) {
        try {
            Inventory inv = player.getOpenInventory().getTopInventory();
            ItemStack outputItem = inv.getItem(OUTPUT_SLOT);
            if (outputItem != null && outputItem.getAmount() >= 64) {
                KomutechSupport.send(player, "§c输出槽位已满！");
                return;
            }
            for (Map.Entry<Integer, String> entry : RECIPE.entrySet()) {
                ItemStack item = inv.getItem(entry.getKey());
                SlimefunItem sfItem = item == null ? null : SlimefunItem.getByItem(item);
                if (sfItem == null || !entry.getValue().equals(sfItem.getId())) {
                    KomutechSupport.send(player, "§c材料不正确！");
                    return;
                }
                if (entry.getKey() == 13 && !isItemBoundToPlayer(item, player)) {
                    KomutechSupport.send(player, "§c大胆！何人竟敢行苟且之事！");
                    return;
                }
            }
            SlimefunItem outputSfItem = SlimefunItem.getById(OUTPUT_ITEM);
            if (outputSfItem == null) {
                KomutechSupport.send(player, "§c合成失败！");
                return;
            }
            if (outputItem != null) {
                SlimefunItem existing = SlimefunItem.getByItem(outputItem);
                if (existing == null || !OUTPUT_ITEM.equals(existing.getId())) {
                    KomutechSupport.send(player, "§c输出槽位已有其他物品！");
                    return;
                }
            }
            for (Map.Entry<Integer, String> entry : RECIPE.entrySet()) {
                if (entry.getKey() == 13) {
                    continue;
                }
                ItemStack item = inv.getItem(entry.getKey());
                if (item.getAmount() == 1) {
                    inv.setItem(entry.getKey(), null);
                } else {
                    item.setAmount(item.getAmount() - 1);
                }
            }
            ItemStack output = outputSfItem.getItem().clone();
            if (outputItem == null) {
                inv.setItem(OUTPUT_SLOT, output);
            } else {
                outputItem.setAmount(outputItem.getAmount() + 1);
            }
            KomutechSupport.send(player, "§a合成成功！");
        } catch (RuntimeException exception) {
            KomutechSupport.send(player, "§c合成失败！");
        }
    }

    private static boolean isItemBoundToPlayer(ItemStack item, Player player) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return false;
        }
        String marker = "§b已绑定：§a" + player.getName();
        for (String line : meta.getLore()) {
            if (line != null && line.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
