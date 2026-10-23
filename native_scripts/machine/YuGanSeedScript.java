package tech.komutech.native_scripts.machine;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import tech.origintech.native_scripts.NativeScript;
import tech.origintech.native_scripts.support.MachineScriptHelper;
import tech.origintech.native_scripts.support.MachineScriptHelper.MachineContext;

public final class YuGanSeedScript implements NativeScript {

    private static final long SEED_TO_SAPLING_MS = 3000L;
    private static final long SAPLING_TO_BAMBOO_MS = 5000L;
    private static final String TARGET_PDC_ID = "KOMUTECH_L_DJ_XSDLY";
    private static final String PRODUCT_ID = "KOMUTECH_L_LZ_YG";
    private static final int MAX_PRODUCTS = 16;
    private static final long TICK_INTERVAL_MS = 1000L;

    private static final Map<Location, SeedState> STATE_MAP = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> PDC_CACHE = new ConcurrentHashMap<>();
    private static SlimefunItem productItem;

    @Override
    public boolean hasTick() {
        return true;
    }
    @Override
    public Object invoke(String functionName, Object... args) {
        if ("tick".equals(functionName)) {
            MachineContext ctx = MachineScriptHelper.parse(args[0]);
            if (ctx != null) {
                tick(ctx);
            }
        } else if ("onPlace".equals(functionName) && args[0] instanceof BlockPlaceEvent event) {
            STATE_MAP.put(event.getBlock().getLocation(), SeedState.create());
        } else if ("onBreak".equals(functionName)) {
            handleBreak(args);
        }
        return null;
    }

    private static void tick(MachineContext ctx) {
        Location location = ctx.location();
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        SeedState state = STATE_MAP.computeIfAbsent(location, ignored -> SeedState.create());
        long now = System.currentTimeMillis();
        if (now - state.lastCheck < TICK_INTERVAL_MS) {
            return;
        }
        state.lastCheck = now;
        if (world.getBlockAt(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ()).getType() != Material.GRASS_BLOCK) {
            return;
        }
        Block block = ctx.block();
        if (state.stage == 0) {
            if (state.lastTime == 0) {
                state.lastTime = now;
            }
            if (now - state.lastTime >= SEED_TO_SAPLING_MS) {
                block.setType(Material.BAMBOO_SAPLING);
                state.stage = 1;
                state.lastTime = now;
            }
        } else if (state.stage == 1) {
            if (now - state.lastTime >= SAPLING_TO_BAMBOO_MS) {
                state.pdcCount = consumePdcBlocks(location);
                block.setType(Material.BAMBOO);
                state.stage = 2;
            }
        }
    }

    private static int consumePdcBlocks(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return 0;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        int consumed = 0;
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] offset : offsets) {
            Block block = world.getBlockAt(x + offset[0], y, z + offset[1]);
            if (hasTargetPdc(block)) {
                block.setType(Material.POPPY);
                PDC_CACHE.remove(block.getLocation().toString());
                consumed++;
            }
        }
        return consumed;
    }

    private static boolean hasTargetPdc(Block block) {
        String key = block.getLocation().toString();
        Boolean cached = PDC_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        try {
            BlockState state = block.getState();
            if (!(state instanceof TileState tileState)) {
                PDC_CACHE.put(key, false);
                return false;
            }
            PersistentDataContainer pdc = tileState.getPersistentDataContainer();
            for (org.bukkit.NamespacedKey pdcKey : pdc.getKeys()) {
                String value = pdc.get(pdcKey, PersistentDataType.STRING);
                if (value != null && value.contains(TARGET_PDC_ID)) {
                    result = true;
                    break;
                }
            }
        } catch (RuntimeException ignored) {
        }
        PDC_CACHE.put(key, result);
        return result;
    }

    private static int getBambooHeight(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return 0;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        int height = 0;
        for (int currentY = y; currentY < world.getMaxHeight(); currentY++) {
            if (world.getBlockAt(x, currentY, z).getType() == Material.BAMBOO) {
                height++;
            } else {
                break;
            }
        }
        return height;
    }

    private static void handleBreak(Object[] args) {
        try {
            Object event = args[0];
            Location location = ((Block) event.getClass().getMethod("getBlock").invoke(event)).getLocation();
            SeedState state = STATE_MAP.remove(location);
            if (state == null || state.stage != 2 || args.length < 3 || !(args[2] instanceof Collection<?> drops)) {
                return;
            }
            if (productItem == null) {
                productItem = SlimefunItem.getById(PRODUCT_ID);
            }
            if (productItem == null) {
                return;
            }
            int bambooHeight = getBambooHeight(location);
            if (bambooHeight <= 0) {
                return;
            }
            int dropCount = (int) Math.floor(bambooHeight * (1 + state.pdcCount * 0.25));
            dropCount = Math.min(Math.max(dropCount, 1), MAX_PRODUCTS);
            @SuppressWarnings("unchecked")
            Collection<ItemStack> dropList = (Collection<ItemStack>) drops;
            dropList.add(productItem.getItem().clone().asQuantity(dropCount));
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static final class SeedState {
        int stage;
        long lastTime;
        int pdcCount;
        long lastCheck;

        static SeedState create() {
            return new SeedState();
        }
    }
}
