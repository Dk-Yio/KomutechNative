package tech.komutech.native_scripts.tool;

import tech.komutech.native_scripts.support.EntityQueries;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Sound;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.AdminToolsSupport;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;

public final class ItemFrameModifierScript implements NativeScript {

    private static final String COMPRESSED_ID = "KOMUTECH_JZ_GJ_压缩展示框修改器";
    private static final String MODE_MARKER = "§6§l当前模式:";

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
            toggleMode(item, player);
            return null;
        }
        ItemFrame frame = (ItemFrame) AdminToolsSupport.rayTraceEntity(player, 5, entity -> entity instanceof ItemFrame);
        if (frame == null) {
            return null;
        }
        if (!AdminToolsSupport.hasPermission(player, frame.getLocation())) {
            KomutechSupport.send(player, "§c你没有权限修改这个展示框！");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
            return null;
        }
        boolean visible = isShowMode(item);
        SlimefunItem sf = SlimefunItem.getByItem(item);
        if (sf != null && COMPRESSED_ID.equals(sf.getId())) {
            List<ItemFrame> affected = new ArrayList<>();
            affected.add(frame);
            BlockFace facing = frame.getFacing();
            for (Entity entity : EntityQueries.entities(frame, 1.5)) {
                if (!(entity instanceof ItemFrame other) || other.getFacing() != facing) {
                    continue;
                }
                int dx = Math.abs(other.getLocation().getBlockX() - frame.getLocation().getBlockX());
                int dy = Math.abs(other.getLocation().getBlockY() - frame.getLocation().getBlockY());
                int dz = Math.abs(other.getLocation().getBlockZ() - frame.getLocation().getBlockZ());
                if (dx <= 1 && dy <= 1 && dz <= 1
                    && AdminToolsSupport.hasPermission(player, other.getLocation())) {
                    affected.add(other);
                }
            }
            for (ItemFrame target : affected) {
                target.setVisible(visible);
            }
            KomutechSupport.actionBar(player, (visible ? "显形" : "隐形") + " §7(影响了 " + affected.size() + " 个展示框)");
        } else {
            frame.setVisible(visible);
            KomutechSupport.actionBar(player, "§a已" + (visible ? "显形" : "隐形") + "！");
        }
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
        return null;
    }

    private static void toggleMode(ItemStack item, Player player) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        boolean show = isShowMode(item);
        lore.removeIf(line -> line != null && line.contains(MODE_MARKER));
        lore.add(show ? "§6§l当前模式: §7隐形" : "§6§l当前模式: §b显形");
        meta.setLore(lore);
        item.setItemMeta(meta);
        KomutechSupport.actionBar(player, "§a已切换至: " + (show ? "隐形" : "显形"));
    }

    private static boolean isShowMode(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return false;
        }
        for (String line : meta.getLore()) {
            if (line != null && line.contains("§b显形")) {
                return true;
            }
        }
        return false;
    }
}
