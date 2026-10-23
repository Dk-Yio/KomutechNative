package tech.komutech.native_scripts.cultivation;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.CultivationMath;
import tech.komutech.native_scripts.support.KomutechAsyncScheduler;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.AttributeApplier;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.MenuGuiHelper;
import tech.komutech.native_scripts.support.PlayerAttributeStore;
import tech.origintech.native_scripts.support.MachineScriptHelper;

public final class JianLingShiScript implements NativeLifecycleScript, KomutechMenuHandler {

    private static final String[] DIRS = {"§f北", "§f东", "§f南", "§f西"};
    private static final long COOLDOWN_MS = 10_000L;

    private final Set<Player> openPlayers = ConcurrentHashMap.newKeySet();
    private final Map<Player, Location> coreMap = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Set<String> processingCores = ConcurrentHashMap.newKeySet();
    private Plugin plugin;

    @Override
    public void registerLifecycle(Plugin plugin) {
        this.plugin = plugin;
        KomutechMenuRouter.registerHandler(this);
    }

    @Override
    public boolean handles(InventoryView view) {
        return MenuGuiHelper.titleEquals(view, JianLingShiSupport.GUI_TITLE);
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onUse".equals(functionName)) {
            handleUse(args[0]);
        } else if ("onBreak".equals(functionName)) {
            handleBreak(args[0]);
        }
        return null;
    }

    private void handleUse(Object event) {
        try {
            Object blockOpt = event.getClass().getMethod("getClickedBlock").invoke(event);
            if (blockOpt == null) {
                return;
            }
            Block block = (Block) blockOpt.getClass().getMethod("get").invoke(blockOpt);
            Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
            SlimefunItem sf = MachineScriptHelper.getSfItem(block.getLocation());
            if (sf == null) {
                return;
            }
            if (JianLingShiSupport.CORE_ID.equals(sf.getId())) {
                openCoreMenu(player, block.getLocation());
            } else if (JianLingShiSupport.TRIGGER_IDS.contains(sf.getId())) {
                handleTrigger(player, block.getLocation());
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private void openCoreMenu(Player player, Location loc) {
        if (MachineScriptHelper.getData(loc, JianLingShiSupport.PREFIX + "fx") == null) {
            MachineScriptHelper.setData(loc, JianLingShiSupport.PREFIX + "fx", "0");
        }
        if (MachineScriptHelper.getData(loc, JianLingShiSupport.PREFIX + "zt") == null) {
            MachineScriptHelper.setData(loc, JianLingShiSupport.PREFIX + "zt", "0");
        }
        int state = parseInt(MachineScriptHelper.getData(loc, JianLingShiSupport.PREFIX + "zt"), 0);
        int dir = parseInt(MachineScriptHelper.getData(loc, JianLingShiSupport.PREFIX + "fx"), 0);
        coreMap.put(player, loc);
        openPlayers.add(player);
        player.openInventory(buildMenu(state, dir, loc));
    }

    private void handleTrigger(Player player, Location triggerLoc) {
        Map<String, Object> existing = PlayerAttributeStore.load(player.getName());
        if (existing != null && !PlayerAttributeStore.getBoolean(existing, "定向", false)) {
            KomutechSupport.send(player, "§c你已经鉴别过了");
            return;
        }
        String cooldownKey = player.getUniqueId().toString();
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(cooldownKey);
        if (last != null && now - last < COOLDOWN_MS) {
            KomutechSupport.actionBar(player, "§c操作过快");
            return;
        }
        cooldowns.put(cooldownKey, now);
        String coreKey = MachineScriptHelper.getData(triggerLoc, JianLingShiSupport.PREFIX + "core");
        if (coreKey == null) {
            KomutechSupport.send(player, "§c未找到核心标记，请先激活鉴灵石核心");
            return;
        }
        Location coreLoc = parseLocation(coreKey);
        if (coreLoc == null) {
            KomutechSupport.send(player, "§c核心坐标无效");
            return;
        }
        if (!"1".equals(MachineScriptHelper.getData(coreLoc, JianLingShiSupport.PREFIX + "zt"))) {
            KomutechSupport.send(player, "§c核心未激活");
            return;
        }
        String processingKey = JianLingShiSupport.locationKey(coreLoc);
        if (!processingCores.add(processingKey)) {
            KomutechSupport.send(player, "§c核心处理中");
            return;
        }
        int dir = parseInt(MachineScriptHelper.getData(coreLoc, JianLingShiSupport.PREFIX + "fx"), 0);
        JianLingShiSupport.StructureCheck check = JianLingShiSupport.checkStructure(coreLoc, dir);
        if (!check.valid()) {
            KomutechSupport.send(player, "§c结构异常");
            MachineScriptHelper.setData(coreLoc, JianLingShiSupport.PREFIX + "zt", "0");
            processingCores.remove(processingKey);
            return;
        }
        Map<String, Object> directed = existing != null && PlayerAttributeStore.getBoolean(existing, "定向", false)
            ? existing : null;
        final Map<String, Object>[] rolled = new Map[1];
        KomutechAsyncScheduler.submit(
            () -> rolled[0] = JianLingShiSupport.rollAttributes(directed),
            activePlugin -> {
                Map<String, Object> data = rolled[0];
                if (data == null) {
                    processingCores.remove(processingKey);
                    return;
                }
                String linggenStr = PlayerAttributeStore.getString(data, "灵根", "");
                if (directed != null) {
                    KomutechSupport.send(player, "§d定向标记生效，灵根锁定为：" + linggenStr);
                }
                JianLingShiSupport.showHologram(plugin, coreLoc, player, linggenStr);
                KomutechSupport.send(player, "§a开始检验");
                JianLingShiSupport.startEffect(plugin, coreLoc,
                    JianLingShiSupport.elementsFromData(data),
                    JianLingShiSupport.qualitiesFromData(data),
                    () -> {
                    JianLingShiSupport.consumeClusters(coreLoc, player);
                    CultivationMath.ensureDataComplete(player.getName(), data);
                    PlayerAttributeStore.save(player.getName(), data);
                    AttributeApplier.applyStoredAttributes(player);
                    KomutechSupport.send(player, "§a鉴灵完成！灵根：" + linggenStr
                        + "，总品质：" + PlayerAttributeStore.getString(data, "总品质", ""));
                    processingCores.remove(processingKey);
                });
            }
        );
    }

    private void handleBreak(Object event) {
        try {
            Location loc = ((Block) event.getClass().getMethod("getBlock").invoke(event)).getLocation();
            SlimefunItem sf = MachineScriptHelper.getSfItem(loc);
            if (sf == null || !JianLingShiSupport.CORE_ID.equals(sf.getId())) {
                return;
            }
            int dir = parseInt(MachineScriptHelper.getData(loc, JianLingShiSupport.PREFIX + "fx"), 0);
            MachineScriptHelper.setData(loc, JianLingShiSupport.PREFIX + "zt", null);
            MachineScriptHelper.setData(loc, JianLingShiSupport.PREFIX + "fx", null);
            JianLingShiSupport.clearTriggers(loc, dir);
            JianLingShiProjection.clearForCoreBreak(loc);
            processingCores.remove(JianLingShiSupport.locationKey(loc));
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !openPlayers.contains(player)) {
            return;
        }
        if (!MenuGuiHelper.titleEquals(event.getView(), JianLingShiSupport.GUI_TITLE)) {
            return;
        }
        event.setCancelled(true);
        Location loc = coreMap.get(player);
        if (loc == null) {
            return;
        }
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir()) {
            return;
        }
        int slot = event.getSlot();
        int state = parseInt(MachineScriptHelper.getData(loc, JianLingShiSupport.PREFIX + "zt"), 0);
        int dir = parseInt(MachineScriptHelper.getData(loc, JianLingShiSupport.PREFIX + "fx"), 0);
        Inventory inv = event.getInventory();
        if (slot == 8) {
            player.closeInventory();
        } else if (slot == 0) {
            JianLingShiProjection.toggleProjection(loc, dir);
            inv.setItem(0, projectionButton(loc));
        } else if (slot == 1) {
            JianLingShiSupport.StructureCheck check = JianLingShiSupport.checkStructure(loc, dir);
            if (check.valid()) {
                MachineScriptHelper.setData(loc, JianLingShiSupport.PREFIX + "zt", "1");
                JianLingShiSupport.markTriggers(loc, dir, JianLingShiSupport.locationKey(loc));
                KomutechSupport.send(player, "§a激活成功！");
            } else {
                MachineScriptHelper.setData(loc, JianLingShiSupport.PREFIX + "zt", "0");
                KomutechSupport.send(player, "§c结构错误: " + (check.errors().isEmpty() ? "未知错误" : check.errors().get(0)));
            }
            player.closeInventory();
        } else if (slot == 2 && state == 1) {
            MachineScriptHelper.setData(loc, JianLingShiSupport.PREFIX + "zt", "0");
            JianLingShiSupport.clearTriggers(loc, dir);
            JianLingShiProjection.removeProjection(loc);
            KomutechSupport.send(player, "§c已停止");
            player.closeInventory();
        } else if (slot == 3) {
            int nextDir = (dir + 1) % 4;
            MachineScriptHelper.setData(loc, JianLingShiSupport.PREFIX + "fx", String.valueOf(nextDir));
            inv.setItem(3, MenuGuiHelper.item(Material.COMPASS, "§b方向: " + DIRS[nextDir], List.of("§7点击切换")));
            if (JianLingShiProjection.hasProjection(loc)) {
                JianLingShiProjection.spawnProjection(loc, nextDir);
                inv.setItem(0, projectionButton(loc));
            }
        }
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
            && openPlayers.contains(player)
            && MenuGuiHelper.titleEquals(event.getView(), JianLingShiSupport.GUI_TITLE)) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!MenuGuiHelper.titleEquals(event.getView(), JianLingShiSupport.GUI_TITLE)) {
            return;
        }
        coreMap.remove(player);
        openPlayers.remove(player);
    }

    private ItemStack projectionButton(Location core) {
        if (JianLingShiProjection.hasProjection(core)) {
            return MenuGuiHelper.item(Material.ENDER_PEARL, "§a投影已开启", List.of("§7点击关闭"));
        }
        return MenuGuiHelper.item(Material.ENDER_PEARL, "§b投影", List.of("§7开启/关闭"));
    }

    private Inventory buildMenu(int state, int dir, Location core) {
        Inventory inv = MenuGuiHelper.create(9, JianLingShiSupport.GUI_TITLE);
        if (state == 1) {
            inv.setItem(4, MenuGuiHelper.item(Material.PAINTING, "§a● 已激活", List.of("§7运行中")));
            inv.setItem(2, MenuGuiHelper.item(Material.REDSTONE_BLOCK, "§c停止", List.of("§7停止机器")));
        } else {
            inv.setItem(4, MenuGuiHelper.item(Material.PAINTING, "§c○ 未激活", List.of("§7点击启动检测激活")));
        }
        inv.setItem(0, projectionButton(core));
        inv.setItem(1, MenuGuiHelper.item(Material.EMERALD_BLOCK, "§a启动", List.of("§7验证并激活")));
        inv.setItem(3, MenuGuiHelper.item(Material.COMPASS, "§b方向: " + DIRS[dir % 4], List.of("§7点击切换")));
        inv.setItem(8, MenuGuiHelper.item(Material.BARRIER, "§c关闭", List.of()));
        return inv;
    }

    private static Location parseLocation(String key) {
        String[] parts = key.split(",");
        if (parts.length != 4) {
            return null;
        }
        org.bukkit.World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
