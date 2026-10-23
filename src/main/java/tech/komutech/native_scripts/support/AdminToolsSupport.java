package tech.komutech.native_scripts.support;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.RayTraceResult;

public final class AdminToolsSupport {

    public static final String POS_KEY_FIRST = "§6§l位置一：";
    public static final String POS_KEY_SECOND = "§6§l位置二：";
    private static final Pattern POS_PATTERN = Pattern.compile("X:(-?\\d+)\\s+Y:(-?\\d+)\\s+Z:(-?\\d+)");
    private static final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    private AdminToolsSupport() {
    }

    public record BlockPos(int x, int y, int z) {
    }

    public static boolean checkCooldown(Player player, long ms) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < ms) {
            return false;
        }
        cooldowns.put(player.getUniqueId(), now);
        return true;
    }

    public static boolean hasPermission(Player player, Location location) {
        if (player.hasPermission("slimefun.inventory.bypass")
            || player.hasPermission("komutech.hologram.bypass")
            || player.isOp()) {
            return true;
        }
        return Slimefun.getProtectionManager().hasPermission(player, location, Interaction.BREAK_BLOCK)
            && Slimefun.getProtectionManager().hasPermission(player, location, Interaction.INTERACT_BLOCK);
    }

    public static Block rayTraceBlock(Player player, double distance) {
        RayTraceResult result = player.getWorld().rayTraceBlocks(
            player.getEyeLocation(),
            player.getEyeLocation().getDirection(),
            distance,
            FluidCollisionMode.NEVER,
            true
        );
        return result == null ? null : result.getHitBlock();
    }

    public static Entity rayTraceEntity(Player player, double distance, Predicate<Entity> filter) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
            player.getEyeLocation(),
            player.getEyeLocation().getDirection(),
            distance,
            0.1,
            filter
        );
        return result == null ? null : result.getHitEntity();
    }

    public static BlockPos parsePos(List<String> lore, String key) {
        if (lore == null) {
            return null;
        }
        for (String line : lore) {
            if (line != null && line.startsWith(key)) {
                Matcher matcher = POS_PATTERN.matcher(line.substring(key.length()).trim());
                if (matcher.find()) {
                    return new BlockPos(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))
                    );
                }
            }
        }
        return null;
    }

    public static void writePos(ItemStack item, String key, String label, BlockPos pos) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || pos == null) {
            return;
        }
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        String value = key + "X:" + pos.x() + " Y:" + pos.y() + " Z:" + pos.z();
        boolean found = false;
        for (int i = 0; i < lore.size(); i++) {
            if (lore.get(i).startsWith(key)) {
                lore.set(i, value);
                found = true;
                break;
            }
        }
        if (!found) {
            lore.add(value);
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    public static String loreMode(List<String> lore, String marker, String singleDefault, String rangeMarker) {
        if (lore == null) {
            return singleDefault;
        }
        for (String line : lore) {
            if (line != null && line.contains(marker)) {
                return line.contains(rangeMarker) ? "range" : "single";
            }
        }
        return singleDefault;
    }

    public static void switchLoreMode(ItemStack item, String marker, String singleLine, String rangeLine) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        boolean range = "range".equals(loreMode(lore, marker, "single", "范围"));
        lore.removeIf(line -> line != null && line.contains(marker));
        lore.add(range ? singleLine : rangeLine);
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    public static boolean isSlimefunItem(ItemStack item, String id) {
        SlimefunItem sf = SlimefunItem.getByItem(item);
        return sf != null && id.equals(sf.getId());
    }

    public static ItemStack findInventoryItem(Player player, String slimefunId) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && isSlimefunItem(stack, slimefunId)) {
                return stack;
            }
        }
        return null;
    }

    public static int takeSimilar(Player player, ItemStack template, int need) {
        int taken = 0;
        for (int slot = 0; slot < 36 && taken < need; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.getType().isAir() || !stack.isSimilar(template)) {
                continue;
            }
            int take = Math.min(stack.getAmount(), need - taken);
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() <= 0) {
                player.getInventory().setItem(slot, null);
            }
            taken += take;
        }
        return taken;
    }

    public static Object clickedBlock(Object event) {
        if (event == null) {
            return null;
        }
        try {
            Object blockOpt = event.getClass().getMethod("getClickedBlock").invoke(event);
            if (blockOpt == null) {
                return null;
            }
            return blockOpt.getClass().getMethod("get").invoke(blockOpt);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }
}
