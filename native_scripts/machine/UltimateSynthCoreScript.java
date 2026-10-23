package tech.komutech.native_scripts.machine;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.MenuGuiHelper;
import tech.origintech.native_scripts.support.MachineScriptHelper;

public final class UltimateSynthCoreScript implements NativeLifecycleScript, NativeScript, KomutechMenuHandler {

    private static final String[] DIRS = {"§f北", "§f东", "§f南", "§f西"};
    private static final Boolean BLOCK_CLICK = Boolean.FALSE;

    private final Set<Player> openPlayers = ConcurrentHashMap.newKeySet();
    private final Map<Player, Location> coreMap = new ConcurrentHashMap<>();

    @Override
    public void registerLifecycle(Plugin plugin) {
        KomutechMenuRouter.registerHandler(this);
    }

    @Override
    public boolean handles(InventoryView view) {
        return MenuGuiHelper.titleEquals(view, UltimateSynthSupport.GUI_TITLE);
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onUse".equals(functionName)) {
            handleUse(args[0]);
        } else if ("onBreak".equals(functionName)) {
            handleBreak(args[0]);
        } else if ("onOpen".equals(functionName) && args[0] instanceof Player player) {
            openPlayers.add(player);
        } else if ("onClick".equals(functionName) && args.length >= 2 && args[0] instanceof Player player) {
            int slot = args[1] instanceof Number number ? number.intValue() : -1;
            if (!handles(player.getOpenInventory()) || slot < 0 || slot >= 9) {
                return BLOCK_CLICK;
            }
            Location loc = coreMap.get(player);
            if (loc == null) {
                return BLOCK_CLICK;
            }
            handleButton(player, loc, player.getOpenInventory().getTopInventory(), slot);
            return BLOCK_CLICK;
        }
        return null;
    }

    private void handleUse(Object event) {
        try {
            Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
            Object blockOpt = event.getClass().getMethod("getClickedBlock").invoke(event);
            if (blockOpt == null) {
                return;
            }
            Object block = blockOpt.getClass().getMethod("get").invoke(blockOpt);
            Location loc = ((org.bukkit.block.Block) block).getLocation();
            String sfId = MachineScriptHelper.getSfItem(loc) == null
                ? null : MachineScriptHelper.getSfItem(loc).getId();
            if (!UltimateSynthSupport.CORE_ID.equals(sfId)) {
                return;
            }
            if (MachineScriptHelper.getData(loc, UltimateSynthSupport.DATA_DIR) == null) {
                MachineScriptHelper.setData(loc, UltimateSynthSupport.DATA_DIR, "0");
            }
            if (MachineScriptHelper.getData(loc, UltimateSynthSupport.DATA_STATE) == null) {
                MachineScriptHelper.setData(loc, UltimateSynthSupport.DATA_STATE, "0");
            }
            int state = UltimateSynthSupport.parseInt(MachineScriptHelper.getData(loc, UltimateSynthSupport.DATA_STATE), 0);
            int dir = UltimateSynthSupport.parseInt(MachineScriptHelper.getData(loc, UltimateSynthSupport.DATA_DIR), 0);
            coreMap.put(player, loc);
            openPlayers.add(player);
            Inventory menu = buildMenu(state, dir, loc);
            KomutechMenuRouter.bindInventory(menu, this);
            player.openInventory(menu);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private void handleBreak(Object event) {
        try {
            Location loc = ((org.bukkit.block.Block) event.getClass().getMethod("getBlock").invoke(event)).getLocation();
            String sfId = MachineScriptHelper.getSfItem(loc) == null
                ? null : MachineScriptHelper.getSfItem(loc).getId();
            if (UltimateSynthSupport.CORE_ID.equals(sfId)) {
                MachineScriptHelper.setData(loc, UltimateSynthSupport.DATA_STATE, null);
                MachineScriptHelper.setData(loc, UltimateSynthSupport.DATA_DIR, null);
                MachineScriptHelper.setData(loc, UltimateSynthSupport.DATA_PROJECTION, null);
                UltimateSynthProjection.clearForCoreBreak(loc);
            } else if (UltimateSynthSupport.PROCESSOR_ID.equals(sfId)) {
                Location core = loc.clone().add(0, -1, 0);
                MachineScriptHelper.setData(core, UltimateSynthSupport.DATA_STATE, "0");
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!handles(event.getView())) {
            return;
        }
        event.setCancelled(true);
        openPlayers.add(player);

        if (isHotbarSwap(event)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (rawSlot < 0 || rawSlot >= topSize) {
            return;
        }

        Location loc = coreMap.get(player);
        if (loc == null) {
            return;
        }

        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir()) {
            return;
        }

        handleButton(player, loc, event.getInventory(), rawSlot);
    }

    private void handleButton(Player player, Location loc, Inventory inv, int slot) {
        int state = UltimateSynthSupport.parseInt(MachineScriptHelper.getData(loc, UltimateSynthSupport.DATA_STATE), 0);
        int dir = UltimateSynthSupport.parseInt(MachineScriptHelper.getData(loc, UltimateSynthSupport.DATA_DIR), 0);
        if (slot == 8) {
            player.closeInventory();
        } else if (slot == 0) {
            UltimateSynthProjection.toggleProjection(loc, dir);
            inv.setItem(0, projectionButton(loc));
        } else if (slot == 1 || (slot == 4 && state != 1)) {
            handleActivate(player, loc, inv);
        } else if (slot == 2 && state == 1) {
            MachineScriptHelper.setData(loc, UltimateSynthSupport.DATA_STATE, "0");
            UltimateSynthProjection.removeProjection(loc);
            KomutechSupport.send(player, "§c已停止");
            refreshMenu(inv, loc);
        } else if (slot == 3) {
            int nextDir = (dir + 1) % 4;
            MachineScriptHelper.setData(loc, UltimateSynthSupport.DATA_DIR, String.valueOf(nextDir));
            if (UltimateSynthProjection.hasProjection(loc)) {
                UltimateSynthProjection.spawnProjection(loc, nextDir);
            }
            refreshMenu(inv, loc);
        }
    }

    private void handleActivate(Player player, Location loc, Inventory inv) {
        if (UltimateSynthProjection.hasProjection(loc)) {
            UltimateSynthProjection.removeProjection(loc);
        }
        UltimateSynthSupport.StructureCheck check = UltimateSynthSupport.checkCoreStructure(loc);
        if (check.valid()) {
            MachineScriptHelper.setData(loc, UltimateSynthSupport.DATA_STATE, "1");
            KomutechSupport.send(player, "§a激活成功！");
        } else {
            MachineScriptHelper.setData(loc, UltimateSynthSupport.DATA_STATE, "0");
            KomutechSupport.send(player, "§c结构错误");
            for (int i = 0; i < Math.min(5, check.errors().size()); i++) {
                KomutechSupport.send(player, "§7 - " + check.errors().get(i));
            }
            if (check.errors().size() > 5) {
                KomutechSupport.send(player, "§7... 等" + (check.errors().size() - 5) + "处错误");
            }
        }
        refreshMenu(inv, loc);
    }

    private void refreshMenu(Inventory inv, Location loc) {
        int state = UltimateSynthSupport.parseInt(MachineScriptHelper.getData(loc, UltimateSynthSupport.DATA_STATE), 0);
        int dir = UltimateSynthSupport.parseInt(MachineScriptHelper.getData(loc, UltimateSynthSupport.DATA_DIR), 0);
        inv.setItem(0, projectionButton(loc));
        inv.setItem(1, MenuGuiHelper.item(Material.EMERALD_BLOCK, "§a启动", List.of("§7验证并激活")));
        inv.setItem(3, MenuGuiHelper.item(Material.COMPASS, "§b方向: " + DIRS[dir % 4], List.of("§7点击切换")));
        inv.setItem(8, MenuGuiHelper.item(Material.BARRIER, "§c关闭", List.of()));
        if (state == 1) {
            inv.setItem(2, MenuGuiHelper.item(Material.REDSTONE_BLOCK, "§c停止", List.of("§7停止机器")));
            inv.setItem(4, MenuGuiHelper.item(Material.PAINTING, "§a● 已激活", List.of("§7运行中")));
        } else {
            inv.setItem(2, null);
            inv.setItem(4, MenuGuiHelper.item(Material.PAINTING, "§c○ 未激活", List.of("§7点击启动检测激活")));
        }
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        if (handles(event.getView())) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!MenuGuiHelper.titleEquals(event.getView(), UltimateSynthSupport.GUI_TITLE)) {
            return;
        }
        coreMap.remove(player);
        openPlayers.remove(player);
    }

    private static boolean isHotbarSwap(InventoryClickEvent event) {
        ClickType click = event.getClick();
        InventoryAction action = event.getAction();
        return click == ClickType.NUMBER_KEY
            || click == ClickType.SWAP_OFFHAND
            || action == InventoryAction.HOTBAR_MOVE_AND_READD
            || action == InventoryAction.HOTBAR_SWAP
            || click == ClickType.DOUBLE_CLICK;
    }

    private ItemStack projectionButton(Location core) {
        if (UltimateSynthProjection.hasProjection(core)) {
            return MenuGuiHelper.item(Material.ENDER_PEARL, "§a投影已开启", List.of("§7点击关闭"));
        }
        return MenuGuiHelper.item(Material.ENDER_PEARL, "§b投影", List.of("§7开启/关闭"));
    }

    private Inventory buildMenu(int state, int dir, Location core) {
        Inventory inv = MenuGuiHelper.create(9, UltimateSynthSupport.GUI_TITLE);
        refreshMenu(inv, core);
        return inv;
    }
}
