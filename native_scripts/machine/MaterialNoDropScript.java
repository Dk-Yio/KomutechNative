package tech.komutech.native_scripts.machine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import tech.origintech.native_scripts.NativeScript;

public final class MaterialNoDropScript implements NativeScript {

    private static final Map<Location, String> CACHE = new ConcurrentHashMap<>();

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onPlace".equals(functionName) && args[0] instanceof BlockPlaceEvent event) {
            CACHE.put(event.getBlock().getLocation(), event.getBlock().getType().name());
            return null;
        }
        if ("onBreak".equals(functionName) && args[0] instanceof BlockBreakEvent event) {
            Location loc = event.getBlock().getLocation();
            String placed = CACHE.get(loc);
            if (placed != null && !placed.equals(event.getBlock().getType().name())) {
                event.setDropItems(false);
                event.setExpToDrop(0);
            }
            CACHE.remove(loc);
            return null;
        }
        return null;
    }
}
