package tech.komutech.native_scripts.tool;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import tech.origintech.native_scripts.NativeScript;
import tech.origintech.native_scripts.support.MachineScriptHelper;

public final class StorageDetectorScript implements NativeScript {

    private static final String MACHINE_ID = "KOMUTECH_JZ_GJ_存储检测器";
    private static final Direction[] DIRECTIONS = {
        new Direction("东", 1, 0),
        new Direction("南", 0, 1),
        new Direction("西", -1, 0),
        new Direction("北", 0, -1)
    };

    private final Map<String, Long> checkTimeCache = new ConcurrentHashMap<>();
    private final Map<String, Long> reportTimeCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> facingCache = new ConcurrentHashMap<>();
    private final Map<String, Long> playerTimeCache = new ConcurrentHashMap<>();

    @Override
    public boolean hasTick() {
        return true;
    }
    @Override
    public Object invoke(String functionName, Object... args) {
        if ("tick".equals(functionName)) {
            MachineScriptHelper.MachineContext ctx = MachineScriptHelper.parse(args[0]);
            if (ctx != null && MACHINE_ID.equals(MachineScriptHelper.getMachineId(ctx.machine()))) {
                handleStone(ctx.location());
            }
        } else if ("onPlace".equals(functionName) && args[0] instanceof BlockPlaceEvent event) {
            Location loc = event.getBlock().getLocation();
            clearCache(loc);
            Player player = event.getPlayer();
            player.sendMessage(ChatColor.GREEN + "已放置存储检测器");
            player.sendMessage(ChatColor.YELLOW + "功能: 检测东南西北四个方向的粘液机器");
            player.sendMessage(ChatColor.GRAY + "站上机器并面向不同方向查看详细信息");
            handleStone(loc);
        } else if ("onBreak".equals(functionName) && args.length >= 1) {
            Location loc = extractLocation(args[0]);
            if (loc != null) {
                clearCache(loc);
                if (args[0] instanceof BlockBreakEvent event) {
                    event.getPlayer().sendMessage(ChatColor.GOLD + "存储检测器已被拆除");
                }
            }
        }
        return null;
    }

    private void handleStone(Location loc) {
        long now = System.currentTimeMillis();
        String key = locationKey(loc);
        if (now - checkTimeCache.getOrDefault(key, 0L) < 200L) {
            return;
        }
        checkTimeCache.put(key, now);
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        Player player = getPlayerOnStone(loc, world, now);
        List<DirectionInfo> machines = checkDirections(loc);
        if (player != null) {
            handlePlayer(loc, player, machines, now);
        } else if (now - reportTimeCache.getOrDefault(key, 0L) >= 10_000L) {
            sendBriefReport(loc, world, machines);
            reportTimeCache.put(key, now);
        }
    }

    private Player getPlayerOnStone(Location loc, World world, long now) {
        Location center = loc.clone().add(0.5, 1, 0.5);
        for (Player player : world.getPlayers()) {
            Location pLoc = player.getLocation();
            if (!pLoc.getWorld().equals(world)) {
                continue;
            }
            if (Math.abs(pLoc.getX() - center.getX()) < 0.7
                && Math.abs(pLoc.getZ() - center.getZ()) < 0.7
                && Math.abs(pLoc.getY() - center.getY()) < 1.5) {
                String playerId = player.getUniqueId().toString();
                long onTime = playerTimeCache.getOrDefault(playerId, 0L);
                playerTimeCache.put(playerId, now);
                if (now - onTime > 1000L) {
                    return player;
                }
            }
        }
        return null;
    }

    private List<DirectionInfo> checkDirections(Location loc) {
        List<DirectionInfo> result = new ArrayList<>();
        for (int i = 0; i < DIRECTIONS.length; i++) {
            Direction dir = DIRECTIONS[i];
            Location targetLoc = loc.clone().add(dir.offsetX, 0, dir.offsetZ);
            SlimefunItem machine = MachineScriptHelper.getSfItem(targetLoc);
            result.add(new DirectionInfo(
                dir.name,
                i,
                machine == null ? null : machine.getId(),
                machine == null ? "无机器" : machineName(machine),
                machine == null ? null : checkStorage(targetLoc)
            ));
        }
        return result;
    }

    private StorageInfo checkStorage(Location loc) {
        Object menu = MachineScriptHelper.getMenu(loc);
        if (menu == null) {
            return null;
        }
        ItemStack[] contents = MachineScriptHelper.getMenuContents(menu);
        boolean hasItems = false;
        int itemCount = 0;
        int itemTypes = 0;
        int usedSlots = 0;
        List<ItemSummary> items = new ArrayList<>();
        for (ItemStack item : contents) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            hasItems = true;
            itemCount += item.getAmount();
            itemTypes++;
            usedSlots++;
            String name = formatItemName(item);
            items.add(new ItemSummary(name, item.getAmount()));
        }
        return new StorageInfo(hasItems, itemCount, itemTypes, usedSlots, contents.length, items);
    }

    private void handlePlayer(Location loc, Player player, List<DirectionInfo> machines, long now) {
        var dirVector = player.getEyeLocation().getDirection();
        int dirIndex = getDirection(dirVector.getX(), dirVector.getZ());
        String facingKey = player.getUniqueId() + "_" + locationKey(loc);
        int lastDir = facingCache.getOrDefault(facingKey, -1);
        boolean changed = lastDir != dirIndex;
        facingCache.put(facingKey, dirIndex);
        String key = locationKey(loc);
        if (changed || now - reportTimeCache.getOrDefault(key, 0L) >= 2000L) {
            sendDetailedReport(player, machines.get(dirIndex), changed);
            reportTimeCache.put(key, now);
        }
    }

    private static int getDirection(double x, double z) {
        return Math.abs(x) > Math.abs(z) ? (x > 0 ? 0 : 2) : (z > 0 ? 1 : 3);
    }

    private void sendDetailedReport(Player player, DirectionInfo info, boolean isNewDir) {
        if (isNewDir) {
            player.sendMessage(ChatColor.GOLD + "=== 您正面向" + info.direction + "方 ===");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.2f);
        }
        if (info.machineId == null) {
            player.sendMessage(ChatColor.YELLOW + info.direction + "方: " + ChatColor.RED + "没有检测到粘液机器");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.8f);
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append(ChatColor.YELLOW).append(info.direction).append("方机器: ")
            .append(ChatColor.AQUA).append(ChatColor.stripColor(info.machineName)).append("\n");
        if (info.storageInfo != null) {
            StorageInfo storage = info.storageInfo;
            if (storage.hasItems) {
                int percent = storage.totalSlots > 0
                    ? Math.round((storage.usedSlots * 100f) / storage.totalSlots) : 0;
                msg.append(ChatColor.WHITE).append("存储: ").append(ChatColor.GREEN)
                    .append(storage.usedSlots).append("/").append(storage.totalSlots)
                    .append("槽位 (").append(percent).append("%)\n");
                msg.append(ChatColor.WHITE).append("物品: ").append(ChatColor.YELLOW)
                    .append(storage.itemCount).append("个, ").append(storage.itemTypes).append("种\n");
                if (!storage.items.isEmpty()) {
                    msg.append(ChatColor.WHITE).append("主要物品:\n");
                    for (int j = 0; j < Math.min(storage.items.size(), 5); j++) {
                        ItemSummary item = storage.items.get(j);
                        msg.append(ChatColor.GRAY).append("  • ").append(ChatColor.WHITE)
                            .append(item.name).append(ChatColor.GRAY).append(" x").append(item.amount).append("\n");
                    }
                    if (storage.items.size() > 5) {
                        msg.append(ChatColor.GRAY).append("  等").append(storage.items.size() - 5).append("种物品\n");
                    }
                }
            } else {
                msg.append(ChatColor.GRAY).append("存储: 空\n");
            }
        } else {
            msg.append(ChatColor.DARK_GRAY).append("该机器没有存储空间\n");
        }
        player.sendMessage(msg.toString());
        if (info.storageInfo != null && info.storageInfo.hasItems) {
            int percent = info.storageInfo.totalSlots > 0
                ? Math.round((info.storageInfo.usedSlots * 100f) / info.storageInfo.totalSlots) : 0;
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.3f, 0.5f + (percent / 100f) * 0.5f);
        }
    }

    private void sendBriefReport(Location loc, World world, List<DirectionInfo> machines) {
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distance(loc) > 15) {
                continue;
            }
            StringBuilder msg = new StringBuilder();
            msg.append(ChatColor.GOLD).append("=== 存储检测器 - 四向机器检测 ===\n");
            boolean anyFound = false;
            for (int j = 0; j < machines.size(); j++) {
                DirectionInfo machine = machines.get(j);
                msg.append(ChatColor.YELLOW).append(machine.direction).append(ChatColor.WHITE).append("方: ");
                if (machine.machineId != null) {
                    anyFound = true;
                    msg.append(ChatColor.AQUA).append(ChatColor.stripColor(machine.machineName)).append(" ");
                    if (machine.storageInfo != null) {
                        msg.append(machine.storageInfo.hasItems
                            ? ChatColor.GREEN + "[" + machine.storageInfo.itemCount + "个物品]"
                            : ChatColor.GRAY + "[空]");
                    } else {
                        msg.append(ChatColor.DARK_GRAY).append("[无存储]");
                    }
                } else {
                    msg.append(ChatColor.RED).append("无机器");
                }
                if (j < machines.size() - 1) {
                    msg.append("\n");
                }
            }
            msg.append(ChatColor.GRAY).append("\n").append(ChatColor.ITALIC)
                .append("站上机器并面向不同方向查看详细信息");
            player.sendMessage(msg.toString());
            if (anyFound) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.3f, 1.0f);
            }
        }
    }

    private static String machineName(SlimefunItem machine) {
        String name = machine.getItemName();
        return name == null || name.isBlank() ? machine.getId() : name;
    }

    private static String formatItemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        String name = meta != null && meta.hasDisplayName()
            ? meta.getDisplayName()
            : formatMaterialName(item.getType().name());
        return name.length() > 20 ? name.substring(0, 17) + "..." : name;
    }

    private static String formatMaterialName(String materialName) {
        String[] parts = materialName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private void clearCache(Location loc) {
        String key = locationKey(loc);
        checkTimeCache.remove(key);
        reportTimeCache.remove(key);
    }

    private static String locationKey(Location location) {
        return location.getWorld().getName() + ","
            + location.getBlockX() + ","
            + location.getBlockY() + ","
            + location.getBlockZ();
    }

    private static Location extractLocation(Object arg) {
        if (arg instanceof BlockBreakEvent event) {
            return event.getBlock().getLocation();
        }
        if (arg instanceof BlockPlaceEvent event) {
            return event.getBlock().getLocation();
        }
        return null;
    }

    private record Direction(String name, int offsetX, int offsetZ) {
    }

    private record DirectionInfo(String direction, int index, String machineId, String machineName,
                                 StorageInfo storageInfo) {
    }

    private record StorageInfo(boolean hasItems, int itemCount, int itemTypes, int usedSlots, int totalSlots,
                               List<ItemSummary> items) {
    }

    private record ItemSummary(String name, int amount) {
    }
}
