package tech.komutech.native_scripts.machine;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import tech.origintech.native_scripts.NativeScript;
import tech.origintech.native_scripts.support.MachineScriptHelper;
import tech.origintech.native_scripts.support.MachineScriptHelper.MachineContext;

public final class SongMuScript implements NativeScript {

    private static final int STATE_GROWING = 0;
    private static final int STATE_MATURE = 1;
    private static final int STATE_DAMAGED = 2;

    private static final long GROWTH_TIME_MS = 8000L;
    private static final long DAMAGE_DELAY_MS = 2000L;
    private static final Material REQUIRED_BELOW = Material.CAMPFIRE;
    private static final Material REQUIRED_ABOVE = Material.SMOKER;
    private static final Material MATURE_BLOCK = Material.COAL_BLOCK;
    private static final Material DAMAGED_BLOCK = Material.GRAY_WOOL;
    private static final String REQUIRED_ITEM_ID = "KOMUTECH_L_JCWP_JZJS";
    private static final String DROP_ITEM_ID = "KOMUTECH_L_JCWP_SYM";

    private static final Map<Location, int[]> GROWTH_STATES = new ConcurrentHashMap<>();
    private static SlimefunItem requiredItem;
    private static SlimefunItem dropItem;

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
            GROWTH_STATES.remove(event.getBlock().getLocation());
        } else if ("onBreak".equals(functionName) && args.length >= 1) {
            handleSlimefunBreak(args);
        } else if ("onBlockBreak".equals(functionName) && args[0] instanceof BlockBreakEvent event) {
            handleBlockBreak(event);
        } else if ("onBlockPhysics".equals(functionName) && args[0] instanceof BlockPhysicsEvent event) {
            if (GROWTH_STATES.containsKey(event.getBlock().getLocation())) {
                event.setCancelled(true);
            }
        }
        return null;
    }

    private static void tick(MachineContext ctx) {
        Location location = ctx.location();
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (world.getBlockAt(x, y - 1, z).getType() != REQUIRED_BELOW
            || world.getBlockAt(x, y + 1, z).getType() != REQUIRED_ABOVE) {
            GROWTH_STATES.remove(location);
            return;
        }
        int[] stateData = GROWTH_STATES.get(location);
        long currentTime = System.currentTimeMillis();
        if (stateData == null) {
            GROWTH_STATES.put(location, new int[] {STATE_GROWING, (int) currentTime});
            return;
        }
        if (stateData[0] != STATE_GROWING) {
            return;
        }
        long elapsed = currentTime - stateData[1];
        Block aboveBlock = world.getBlockAt(x, y + 1, z);
        if (elapsed >= DAMAGE_DELAY_MS && !checkItem(aboveBlock, false)) {
            ctx.block().setType(DAMAGED_BLOCK);
            stateData[0] = STATE_DAMAGED;
            return;
        }
        if (elapsed >= GROWTH_TIME_MS) {
            if (checkItem(aboveBlock, true)) {
                ctx.block().setType(MATURE_BLOCK);
                stateData[0] = STATE_MATURE;
            } else {
                ctx.block().setType(DAMAGED_BLOCK);
                stateData[0] = STATE_DAMAGED;
            }
        }
    }

    private static boolean checkItem(Block aboveBlock, boolean consume) {
        if (!(aboveBlock.getState() instanceof Container container)) {
            return false;
        }
        Inventory inventory = container.getInventory();
        ItemStack item = inventory.getItem(0);
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (requiredItem == null) {
            requiredItem = SlimefunItem.getById(REQUIRED_ITEM_ID);
        }
        if (requiredItem == null) {
            return false;
        }
        SlimefunItem slimefunItem = SlimefunItem.getByItem(item);
        if (slimefunItem == null || !requiredItem.getId().equals(slimefunItem.getId())) {
            return false;
        }
        if (item.getAmount() < 1) {
            return false;
        }
        if (consume) {
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
            } else {
                inventory.clear(0);
            }
        }
        return true;
    }

    private static void handleSlimefunBreak(Object[] args) {
        try {
            Object event = args[0];
            Location location = ((Block) event.getClass().getMethod("getBlock").invoke(event)).getLocation();
            int[] stateData = GROWTH_STATES.get(location);
            if (stateData == null) {
                return;
            }
            event.getClass().getMethod("setDropItems", boolean.class).invoke(event, false);
            if (args.length >= 3 && args[2] instanceof Collection<?> drops) {
                drops.clear();
            }
            if (stateData[0] == STATE_MATURE) {
                dropProduct(location);
            } else if (stateData[0] == STATE_DAMAGED) {
                event.getClass().getMethod("setExpToDrop", int.class).invoke(event, 0);
            }
            GROWTH_STATES.remove(location);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static void handleBlockBreak(BlockBreakEvent event) {
        Location location = event.getBlock().getLocation();
        int[] stateData = GROWTH_STATES.get(location);
        if (stateData == null) {
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        if (stateData[0] == STATE_MATURE) {
            dropProduct(location);
        }
        GROWTH_STATES.remove(location);
    }

    private static void dropProduct(Location location) {
        if (dropItem == null) {
            dropItem = SlimefunItem.getById(DROP_ITEM_ID);
        }
        if (dropItem == null) {
            return;
        }
        int dropCount = 1 + ThreadLocalRandom.current().nextInt(3);
        location.getWorld().dropItemNaturally(location, dropItem.getItem().clone().asQuantity(dropCount));
    }
}
