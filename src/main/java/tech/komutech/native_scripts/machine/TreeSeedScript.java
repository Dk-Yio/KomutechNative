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

public final class TreeSeedScript implements NativeScript {

    private record LeafLayer(int yOffset, int radius) {
    }

    private record TreeConfig(String machineId, long growTimeMs, Material requiredBlock,
                              Material trunkMaterial, Material leavesMaterial, List<Material> leavesMaterials,
                              String dropItemId, int minHeight, int maxHeight, int spaceCheckHeight) {
    }

    private static final List<LeafLayer> LEAF_PATTERN = List.of(
        new LeafLayer(1, 1),
        new LeafLayer(0, 2),
        new LeafLayer(-1, 1),
        new LeafLayer(-2, 3),
        new LeafLayer(-3, 2),
        new LeafLayer(-4, 4)
    );

    private static final Map<Location, TreeState> TREE_STATES = new ConcurrentHashMap<>();
    private final TreeConfig config;
    private SlimefunItem dropItem;

    private TreeSeedScript(TreeConfig config) {
        this.config = config;
    }

    public static TreeSeedScript pine() {
        return new TreeSeedScript(new TreeConfig(
            "KOMUTECH_L_LZZZ_SSSM",
            150_000L,
            Material.GRASS_BLOCK,
            Material.SPRUCE_LOG,
            Material.SPRUCE_LEAVES,
            null,
            "KOMUTECH_L_LZ_SM",
            7,
            11,
            12
        ));
    }

    public static TreeSeedScript peach() {
        return new TreeSeedScript(new TreeConfig(
            "KOMUTECH_L_LZZZ_TSSM",
            150_000L,
            Material.GRASS_BLOCK,
            Material.CHERRY_LOG,
            Material.CHERRY_LEAVES,
            List.of(Material.CHERRY_LEAVES, Material.FLOWERING_AZALEA_LEAVES),
            "KOMUTECH_L_LZ_TM",
            5,
            7,
            12
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
            TREE_STATES.remove(event.getBlock().getLocation());
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
        TreeState state = TREE_STATES.get(location);
        if (state != null && (state.mature || state.failed)) {
            return;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (state == null) {
            if (world.getBlockAt(x, y - 1, z).getType() != config.requiredBlock()) {
                TREE_STATES.put(location, TreeState.failed());
                return;
            }
            TREE_STATES.put(location, TreeState.started());
            return;
        }
        if (ctx.block().getType().isAir()) {
            TREE_STATES.remove(location);
            return;
        }
        if (System.currentTimeMillis() - state.startTime < config.growTimeMs()) {
            return;
        }
        if (world.getBlockAt(x, y - 1, z).getType() != config.requiredBlock()) {
            state.failed = true;
            return;
        }
        if (!canGenerateTree(world, x, y, z)) {
            state.failed = true;
            return;
        }
        int treeHeight = generateTree(world, location);
        if (treeHeight > 0) {
            state.mature = true;
            state.treeHeight = treeHeight;
        } else {
            state.failed = true;
        }
    }

    private int generateTree(World world, Location root) {
        int x = root.getBlockX();
        int y = root.getBlockY();
        int z = root.getBlockZ();
        int treeHeight = config.minHeight() + ThreadLocalRandom.current().nextInt(config.maxHeight() - config.minHeight() + 1);
        for (int i = 0; i < treeHeight; i++) {
            world.getBlockAt(x, y + i, z).setType(config.trunkMaterial());
        }
        Material leaves = config.leavesMaterial();
        if (config.leavesMaterials() != null && !config.leavesMaterials().isEmpty()) {
            leaves = config.leavesMaterials().get(ThreadLocalRandom.current().nextInt(config.leavesMaterials().size()));
        }
        generateLeaves(world, x, y + treeHeight - 1, z, leaves);
        return treeHeight;
    }

    private static void generateLeaves(World world, int centerX, int centerY, int centerZ, Material leavesMaterial) {
        for (LeafLayer layer : LEAF_PATTERN) {
            int currentY = centerY + layer.yOffset();
            int radiusSquared = layer.radius() * layer.radius();
            for (int dx = -layer.radius(); dx <= layer.radius(); dx++) {
                for (int dz = -layer.radius(); dz <= layer.radius(); dz++) {
                    if (dx * dx + dz * dz <= radiusSquared) {
                        Block leafBlock = world.getBlockAt(centerX + dx, currentY, centerZ + dz);
                        if (leafBlock.getType().isAir()) {
                            leafBlock.setType(leavesMaterial);
                        }
                    }
                }
            }
        }
    }

    private boolean canGenerateTree(World world, int x, int y, int z) {
        for (int i = 1; i <= config.spaceCheckHeight(); i++) {
            Material type = world.getBlockAt(x, y + i, z).getType();
            if (!type.isAir()
                && type != Material.SPRUCE_SAPLING
                && type != Material.CHERRY_SAPLING
                && type != Material.AZALEA) {
                return false;
            }
        }
        return true;
    }

    private void handleBreak(Object[] args) {
        try {
            Object event = args[0];
            Location location = ((Block) event.getClass().getMethod("getBlock").invoke(event)).getLocation();
            TreeState state = TREE_STATES.remove(location);
            if (state == null || !state.mature || state.treeHeight <= 0 || args.length < 3 || !(args[2] instanceof Collection<?> drops)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Collection<ItemStack> dropList = (Collection<ItemStack>) drops;
            if ("KOMUTECH_L_LZZZ_TSSM".equals(config.machineId())) {
                SlimefunItem sapling = SlimefunItem.getById("KOMUTECH_L_LZZZ_TSSM");
                if (sapling != null) {
                    dropList.add(sapling.getItem().clone());
                }
            }
            if (dropItem == null) {
                dropItem = SlimefunItem.getById(config.dropItemId());
            }
            if (dropItem != null) {
                dropList.add(dropItem.getItem().clone().asQuantity(state.treeHeight));
            }
            World world = location.getWorld();
            int x = location.getBlockX();
            int y = location.getBlockY();
            int z = location.getBlockZ();
            for (int i = 1; i < state.treeHeight; i++) {
                world.getBlockAt(x, y + i, z).setType(Material.AIR);
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static final class TreeState {
        final long startTime;
        boolean mature;
        boolean failed;
        int treeHeight;

        private TreeState(long startTime) {
            this.startTime = startTime;
        }

        static TreeState started() {
            return new TreeState(System.currentTimeMillis());
        }

        static TreeState failed() {
            TreeState state = new TreeState(0);
            state.failed = true;
            return state;
        }
    }
}
