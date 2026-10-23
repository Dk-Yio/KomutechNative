package tech.komutech.native_scripts.tool;

import tech.komutech.native_scripts.support.EntityQueries;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.AdminToolsSupport;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;

public final class RegionItemFrameScript implements NativeScript {

    private static final String RECORDER_ID = "KOMUTECH_JZ_GJ_方位记录器";
    private static final NamespacedKey MODE_KEY = new NamespacedKey("komutech", "fwzskxgq_mode");
    private static final int BATCH_SIZE = 20;

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        ItemStack item = ctx.item();
        if (!AdminToolsSupport.checkCooldown(player, 1000)) {
            return null;
        }
        if (player.isSneaking()) {
            cycleMode(item, player);
            return null;
        }
        ItemStack recorder = AdminToolsSupport.findInventoryItem(player, RECORDER_ID);
        if (recorder == null) {
            KomutechSupport.send(player, "§c背包中没有「方位记录器」");
            return null;
        }
        ItemMeta meta = recorder.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            KomutechSupport.send(player, "§c记录器上没有坐标信息");
            return null;
        }
        List<String> lore = meta.getLore();
        AdminToolsSupport.BlockPos pos1 = AdminToolsSupport.parsePos(lore, AdminToolsSupport.POS_KEY_FIRST);
        AdminToolsSupport.BlockPos pos2 = AdminToolsSupport.parsePos(lore, AdminToolsSupport.POS_KEY_SECOND);
        if (pos1 == null || pos2 == null) {
            KomutechSupport.send(player, "§c坐标信息不完整");
            return null;
        }
        int minX = Math.min(pos1.x(), pos2.x());
        int maxX = Math.max(pos1.x(), pos2.x());
        int minY = Math.min(pos1.y(), pos2.y());
        int maxY = Math.max(pos1.y(), pos2.y());
        int minZ = Math.min(pos1.z(), pos2.z());
        int maxZ = Math.max(pos1.z(), pos2.z());
        FrameMode mode = FrameMode.fromId(readMode(item));
        List<ItemFrame> frames = collectFrames(player.getWorld(), minX, minY, minZ, maxX, maxY, maxZ);
        if (frames.isEmpty()) {
            KomutechSupport.send(player, "§e区域内无展示框");
            return null;
        }
        Plugin plugin = KomutechSupport.plugin();
        if (plugin == null) {
            return null;
        }
        KomutechSupport.actionBar(player, "§a开始" + mode.label + "区域内展示框...");
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        new BukkitRunnable() {
            int index = 0;
            int processed = 0;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                int batch = 0;
                while (index < frames.size() && batch < BATCH_SIZE) {
                    ItemFrame frame = frames.get(index++);
                    if (frame.isValid() && AdminToolsSupport.hasPermission(player, frame.getLocation())) {
                        mode.apply(frame);
                        processed++;
                    }
                    batch++;
                }
                if (index >= frames.size()) {
                    cancel();
                    KomutechSupport.actionBar(player, "§a操作完成，共处理 " + processed + " 个展示框");
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
        return null;
    }

    private static List<ItemFrame> collectFrames(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Location center = new Location(world, (minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
        double half = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) / 2.0 + 1;
        List<ItemFrame> frames = new ArrayList<>();
        for (Entity entity : EntityQueries.entities(world, center, half)) {
            if (!(entity instanceof ItemFrame frame)) {
                continue;
            }
            Location loc = frame.getLocation();
            if (loc.getBlockX() >= minX && loc.getBlockX() <= maxX
                && loc.getBlockY() >= minY && loc.getBlockY() <= maxY
                && loc.getBlockZ() >= minZ && loc.getBlockZ() <= maxZ) {
                frames.add(frame);
            }
        }
        return frames;
    }

    private static int readMode(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        Integer mode = container.get(MODE_KEY, PersistentDataType.INTEGER);
        return mode == null ? 0 : Math.floorMod(mode, FrameMode.values().length);
    }

    private static void cycleMode(ItemStack item, Player player) {
        int next = (readMode(item) + 1) % FrameMode.values().length;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(MODE_KEY, PersistentDataType.INTEGER, next);
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.removeIf(line -> line != null && line.startsWith("§6§l当前模式:"));
        lore.add(FrameMode.values()[next].loreLine);
        meta.setLore(lore);
        item.setItemMeta(meta);
        KomutechSupport.actionBar(player, "§a已切换至: " + FrameMode.values()[next].label);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    private enum FrameMode {
        HIDE("隐形", "§6§l当前模式: §7隐形", frame -> frame.setVisible(false)),
        SHOW("显形", "§6§l当前模式: §b显形", frame -> frame.setVisible(true)),
        REMOVE("拆除", "§6§l当前模式: §c拆除", Entity::remove);

        final String label;
        final String loreLine;
        private final java.util.function.Consumer<ItemFrame> action;

        FrameMode(String label, String loreLine, java.util.function.Consumer<ItemFrame> action) {
            this.label = label;
            this.loreLine = loreLine;
            this.action = action;
        }

        static FrameMode fromId(int id) {
            FrameMode[] values = values();
            return values[Math.floorMod(id, values.length)];
        }

        void apply(ItemFrame frame) {
            action.accept(frame);
        }
    }
}
