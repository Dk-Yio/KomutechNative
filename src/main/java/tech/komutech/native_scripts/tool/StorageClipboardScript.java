package tech.komutech.native_scripts.tool;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.AdminToolsSupport;
import tech.komutech.native_scripts.support.KomutechMenuHelper;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.MachineScriptHelper;

public final class StorageClipboardScript implements NativeScript {

    private static final Map<UUID, Clipboard> clipboards = new ConcurrentHashMap<>();

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onUse".equals(functionName)) {
            handleUse(args[0]);
        }
        return null;
    }

    private void handleUse(Object event) {
        try {
            Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
            Object block = AdminToolsSupport.clickedBlock(event);
            if (!(block instanceof Block target)) {
                KomutechSupport.send(player, "§c请右键机器");
                return;
            }
            Location loc = target.getLocation();
            if (!AdminToolsSupport.hasPermission(player, loc)) {
                KomutechSupport.send(player, "§c你没有权限在此区域操作机器！");
                player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
                return;
            }
            SlimefunItem machine = MachineScriptHelper.getSfItem(loc);
            if (machine == null) {
                KomutechSupport.send(player, "§c不是粘液机器");
                return;
            }
            Object menu = MachineScriptHelper.getMenu(loc);
            if (menu == null) {
                KomutechSupport.send(player, "§c机器无存储");
                return;
            }
            if (player.isSneaking()) {
                copy(player, machine, menu);
            } else {
                apply(player, machine.getId(), menu);
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static void copy(Player player, SlimefunItem machine, Object menu) {
        List<SlotItem> slots = new ArrayList<>();
        int itemCount = 0;
        ItemStack[] contents = KomutechMenuHelper.getContents(menu);
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            slots.add(new SlotItem(slot, item.clone(), item.getAmount()));
            itemCount += item.getAmount();
        }
        String machineName = machine.getItemName();
        clipboards.put(player.getUniqueId(), new Clipboard(machine.getId(), machineName, slots));
        updateLore(player.getInventory().getItemInMainHand(), machineName);
        KomutechSupport.send(player, "§6已复制: §b" + machineName);
        KomutechSupport.send(player, "§7物品: " + itemCount + "个 (" + slots.size() + "槽)");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1f);
    }

    private static void apply(Player player, String targetId, Object menu) {
        Clipboard clipboard = clipboards.get(player.getUniqueId());
        if (clipboard == null) {
            KomutechSupport.send(player, "§c请先复制存储");
            return;
        }
        if (!clipboard.machineId.equals(targetId)) {
            KomutechSupport.send(player, "§c机器不匹配");
            return;
        }
        List<FillSlot> fillSlots = new ArrayList<>();
        int totalNeed = 0;
        for (SlotItem slotItem : clipboard.slots) {
            MachineScriptHelper.ItemStackInMenu current = MachineScriptHelper.getItemInSlot(menu, slotItem.slot);
            int currentAmount = 0;
            if (current != null && current.stack().isSimilar(slotItem.item)) {
                currentAmount = current.stack().getAmount();
            } else if (current != null && !current.stack().getType().isAir()) {
                continue;
            }
            int need = slotItem.amount - currentAmount;
            if (need > 0) {
                totalNeed += need;
                fillSlots.add(new FillSlot(slotItem.slot, slotItem.item, need));
            }
        }
        if (fillSlots.isEmpty()) {
            KomutechSupport.send(player, "§a存储已满足");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2f);
            return;
        }
        int takenTotal = 0;
        for (FillSlot fill : fillSlots) {
            int taken = AdminToolsSupport.takeSimilar(player, fill.item, fill.need);
            if (taken <= 0) {
                continue;
            }
            takenTotal += taken;
            MachineScriptHelper.ItemStackInMenu current = MachineScriptHelper.getItemInSlot(menu, fill.slot);
            if (current != null) {
                ItemStack stack = current.stack();
                stack.setAmount(stack.getAmount() + taken);
                KomutechMenuHelper.replaceExistingItem(menu, fill.slot, stack);
            } else {
                ItemStack stack = fill.item.clone();
                stack.setAmount(taken);
                KomutechMenuHelper.replaceExistingItem(menu, fill.slot, stack);
            }
            if (takenTotal >= totalNeed) {
                break;
            }
        }
        pushMenu(menu);
        if (takenTotal > 0) {
            KomutechSupport.send(player, "§a已补充: " + takenTotal + "个物品");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1f);
        } else {
            KomutechSupport.send(player, "§c背包无匹配物品");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f);
        }
    }

    private static void pushMenu(Object menu) {
        try {
            menu.getClass().getMethod("push").invoke(menu);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static void updateLore(ItemStack item, String machineName) {
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.removeIf(line -> line != null && line.contains("已复制机器:"));
        lore.add("§7已复制机器: §b" + machineName);
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private record Clipboard(String machineId, String machineName, List<SlotItem> slots) {
    }

    private record SlotItem(int slot, ItemStack item, int amount) {
    }

    private record FillSlot(int slot, ItemStack item, int need) {
    }
}
