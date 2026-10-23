package tech.komutech.native_scripts.machine;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import tech.origintech.native_scripts.NativeScript;
import tech.origintech.native_scripts.support.MachineScriptHelper;
import tech.origintech.native_scripts.support.MachineScriptHelper.MachineContext;

public final class CropSeedScript implements NativeScript {

    private record Stage(Material material, long durationMs) {
    }

    private record Config(String machineId, List<Stage> stages, Material requiredBlock,
                          String productItemId, int minDrop, int maxDrop) {
        long totalMs() {
            return stages.stream().mapToLong(Stage::durationMs).sum();
        }

        int stageAt(long elapsedMs) {
            long acc = 0;
            for (int i = 0; i < stages.size(); i++) {
                acc += stages.get(i).durationMs();
                if (elapsedMs < acc) {
                    return i;
                }
            }
            return stages.size() - 1;
        }
    }

    private static final Map<Location, PlantData> PLANT_DATA = new ConcurrentHashMap<>();
    private final Config config;
    private SlimefunItem productItem;

    private CropSeedScript(Config config) {
        this.config = config;
    }

    public static CropSeedScript cotton() {
        return new CropSeedScript(new Config(
            "KOMUTECH_L_LZZZ_MHZZ",
            List.of(
                new Stage(Material.SHORT_GRASS, 3000),
                new Stage(Material.WHITE_TULIP, 5000),
                new Stage(Material.LILY_OF_THE_VALLEY, 3000)
            ),
            Material.FARMLAND,
            "KOMUTECH_L_LZ_MH",
            3,
            8
        ));
    }

    public static CropSeedScript hemp() {
        return new CropSeedScript(new Config(
            "KOMUTECH_L_LZZZ_MZZ",
            List.of(
                new Stage(Material.SHORT_GRASS, 3000),
                new Stage(Material.TALL_GRASS, 5000),
                new Stage(Material.LARGE_FERN, 3000)
            ),
            Material.FARMLAND,
            "KOMUTECH_L_LZ_M",
            3,
            8
        ));
    }

    @Override
    public boolean hasTick() {
        return true;
    }
    @Override
    public Object invoke(String functionName, Object... args) {
        if ("tick".equals(functionName)) {
            MachineContext ctx = MachineScriptHelper.parse(args[0]);
            if (ctx != null && config.machineId().equals(MachineScriptHelper.getMachineId(ctx.machine()))) {
                tick(ctx);
            }
        } else if ("onPlace".equals(functionName) && args[0] instanceof BlockPlaceEvent event) {
            PLANT_DATA.remove(event.getBlock().getLocation());
        } else if ("onBreak".equals(functionName)) {
            handleBreak(args);
        }
        return null;
    }

    private void tick(MachineContext ctx) {
        Location location = ctx.location();
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        long now = System.currentTimeMillis();
        PlantData data = PLANT_DATA.get(location);
        if (data == null) {
            if (world.getBlockAt(x, y - 1, z).getType() != config.requiredBlock()) {
                return;
            }
            PLANT_DATA.put(location, new PlantData(now, -1, false, false));
            return;
        }
        if (data.mature) {
            return;
        }
        long elapsed = now - data.startTime;
        if (!data.checkedForMature && elapsed >= config.totalMs() - 100) {
            if (world.getBlockAt(x, y - 1, z).getType() != config.requiredBlock()) {
                PLANT_DATA.remove(location);
                return;
            }
            data.checkedForMature = true;
        }
        if (elapsed >= config.totalMs()) {
            data.mature = true;
        }
        int targetStage = -1;
        if (elapsed >= config.totalMs()) {
            targetStage = config.stages().size() - 1;
        } else {
            targetStage = config.stageAt(elapsed);
        }
        if (targetStage != data.currentStage && targetStage >= 0) {
            ctx.block().setType(config.stages().get(targetStage).material());
            data.currentStage = targetStage;
        }
    }

    private void handleBreak(Object[] args) {
        try {
            Object event = args[0];
            Location location = ((Block) event.getClass().getMethod("getBlock").invoke(event)).getLocation();
            PlantData data = PLANT_DATA.remove(location);
            if (data == null || !data.mature) {
                return;
            }
            if (productItem == null) {
                productItem = SlimefunItem.getById(config.productItemId());
            }
            if (productItem == null || args.length < 3 || !(args[2] instanceof Collection<?> drops)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Collection<ItemStack> dropList = (Collection<ItemStack>) drops;
            int dropCount = config.minDrop() + ThreadLocalRandom.current().nextInt(config.maxDrop() - config.minDrop() + 1);
            dropList.add(productItem.getItem().clone().asQuantity(dropCount));
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static final class PlantData {
        final long startTime;
        int currentStage;
        boolean mature;
        boolean checkedForMature;

        PlantData(long startTime, int currentStage, boolean mature, boolean checkedForMature) {
            this.startTime = startTime;
            this.currentStage = currentStage;
            this.mature = mature;
            this.checkedForMature = checkedForMature;
        }
    }
}
