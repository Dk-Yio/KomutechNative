package tech.komutech.native_scripts.tool;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.PrefabStructurePlacer;
import tech.komutech.native_scripts.support.PrefabStructureType;
import tech.origintech.native_scripts.support.UseEvents;

public final class PrefabStructureScript implements NativeScript {

    private static final long COOLDOWN_MS = 3000L;
    private static final Map<UUID, Integer> DIRECTIONS = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        if (ctx.hand() != EquipmentSlot.HAND) {
            KomutechSupport.send(player, "§c请使用主手右键放置。");
            return null;
        }
        SlimefunItem sfItem = PrefabStructurePlacer.resolveItem(ctx.item());
        PrefabStructureType type = PrefabStructureType.fromItem(sfItem);
        if (type == null) {
            KomutechSupport.send(player, "§c无效的预制建筑道具。");
            return null;
        }
        UUID uuid = player.getUniqueId();
        int direction = DIRECTIONS.getOrDefault(uuid, 0);

        if (player.isSneaking()) {
            if (type == PrefabStructureType.JIAN_LING_SHI) {
                direction = (direction + 1) % 4;
                DIRECTIONS.put(uuid, direction);
                KomutechSupport.send(player, "§e方向已切换为 " + PrefabStructurePlacer.directionLabel(direction));
            }
            showPreview(player, type, direction);
            return null;
        }

        if (!player.isOp() && !checkCooldown(player)) {
            return null;
        }

        PrefabStructurePlacer.PlacementResult result = PrefabStructurePlacer.paste(player, type, direction);
        KomutechSupport.send(player, result.message());
        if (!result.success()) {
            return null;
        }
        if (!player.isOp()) {
            COOLDOWNS.put(uuid, System.currentTimeMillis());
            UseEvents.consumeOne(ctx);
            player.updateInventory();
        }
        return null;
    }

    private void showPreview(Player player, PrefabStructureType type, int direction) {
        PrefabStructurePlacer.PlacementSpec spec = PrefabStructurePlacer.spec(type);
        PrefabStructurePlacer.Dimensions dims = PrefabStructurePlacer.dimensions(type);
        player.sendMessage("§8§m=========================");
        player.sendMessage("§b预制建筑预览：§f" + spec.displayName());
        player.sendMessage("§7方块数量：§f" + spec.blocks().size());
        player.sendMessage("§7占地尺寸：§fX " + dims.width() + " · Y " + dims.height() + " · Z " + dims.depth());
        if (spec.rotatable()) {
            player.sendMessage("§7当前方向：§f" + PrefabStructurePlacer.directionLabel(direction) + " §7(Shift+右键切换)");
        }
        player.sendMessage("§7放置方式：§f看向方块 §7→ §f右键在其上方搭建");
        player.sendMessage("§8§m=========================");
    }

    private boolean checkCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = COOLDOWNS.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            long remain = (COOLDOWN_MS - (now - last)) / 1000L + 1L;
            KomutechSupport.send(player, "§c冷却中，请等待 " + remain + " 秒。");
            return false;
        }
        return true;
    }
}
