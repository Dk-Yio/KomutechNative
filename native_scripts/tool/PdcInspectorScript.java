package tech.komutech.native_scripts.tool;

import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechSupport;

public final class PdcInspectorScript implements NativeScript {

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onPlace".equals(functionName) && args[0] instanceof BlockPlaceEvent event) {
            handlePlace(event);
        }
        return null;
    }

    private void handlePlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block below = event.getBlockPlaced().getLocation().subtract(0, 1, 0).getBlock();
        if (below.getType().isAir()) {
            KomutechSupport.send(player, "§c下方无方块或为空气");
            return;
        }
        BlockState state = below.getState();
        if (!(state instanceof TileState tileState)) {
            KomutechSupport.send(player, "§7该方块不支持PDC");
            return;
        }
        PersistentDataContainer container = tileState.getPersistentDataContainer();
        KomutechSupport.send(player, "§6===== 方块PDC信息 =====");
        KomutechSupport.send(player, "§7方块类型: §f" + below.getType().name());
        Set<NamespacedKey> keys = container.getKeys();
        boolean found = false;
        for (NamespacedKey key : keys) {
            String value = container.get(key, PersistentDataType.STRING);
            if (value != null) {
                KomutechSupport.send(player, "§e" + key + ": §f" + value);
                found = true;
            }
        }
        if (!found) {
            KomutechSupport.send(player, "§7该方块没有PDC数据");
        }
    }
}
