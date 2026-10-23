package tech.komutech.native_scripts.support;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockIterator;
import tech.komutech.native_scripts.cultivation.JianLingShiSupport;
import tech.komutech.native_scripts.machine.UltimateSynthSupport;
import tech.origintech.native_scripts.support.MachineScriptHelper;

public final class PrefabStructurePlacer {

    private static final String DEFAULT_CLUSTER = "KOMUTECH_L_DJ_JPLJ";
    private static final int[] MULTIBLOCK_SLOT_X = {-1, 0, 1, -1, 0, 1, -1, 0, 1};
    private static final int[] MULTIBLOCK_SLOT_Z = {-1, -1, -1, 0, 0, 0, 1, 1, 1};

    private PrefabStructurePlacer() {
    }

    public record PlacementBlock(int x, int y, int z, String material, String sfId, boolean dynamicCluster) {
    }

    public record PlacementSpec(String displayName, List<PlacementBlock> blocks, boolean rotatable) {
    }

    public record PlacementResult(boolean success, String message) {
    }

    public record Dimensions(int width, int height, int depth) {
    }

    public static PlacementSpec spec(PrefabStructureType type) {
        return switch (type) {
            case JIAN_LING_SHI -> jianLingShiSpec();
            case ULTIMATE_SYNTH_CORE -> ultimateSynthCoreSpec();
            case MB_CRAFTSMAN -> multiblockSpec("工匠台", Map.of(
                3, "AMETHYST_CLUSTER", 4, "CRAFTING_TABLE", 5, "AMETHYST_CLUSTER",
                6, "SMITHING_TABLE", 7, "DISPENSER", 8, "SMITHING_TABLE"
            ));
            case MB_SPIRIT_MINE -> multiblockSpec("灵矿提取台", Map.of(
                1, "AMETHYST_CLUSTER", 3, "GRINDSTONE", 4, "SMITHING_TABLE", 5, "GRINDSTONE",
                6, "BLAST_FURNACE", 7, "DISPENSER", 8, "BLAST_FURNACE"
            ));
            case MB_SPIRIT_INFUSE -> multiblockSpec("注灵台", Map.of(
                3, "AMETHYST_CLUSTER", 4, "ENCHANTING_TABLE", 5, "AMETHYST_CLUSTER",
                6, "BUDDING_AMETHYST", 7, "DISPENSER", 8, "BUDDING_AMETHYST"
            ));
        };
    }

    public static Dimensions dimensions(PrefabStructureType type) {
        PlacementSpec spec = spec(type);
        int minX = 0;
        int maxX = 0;
        int minY = 0;
        int maxY = 0;
        int minZ = 0;
        int maxZ = 0;
        boolean first = true;
        for (PlacementBlock block : spec.blocks()) {
            if (first) {
                minX = maxX = block.x();
                minY = maxY = block.y();
                minZ = maxZ = block.z();
                first = false;
                continue;
            }
            minX = Math.min(minX, block.x());
            maxX = Math.max(maxX, block.x());
            minY = Math.min(minY, block.y());
            maxY = Math.max(maxY, block.y());
            minZ = Math.min(minZ, block.z());
            maxZ = Math.max(maxZ, block.z());
        }
        return new Dimensions(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
    }

    public static PlacementResult paste(Player player, PrefabStructureType type, int direction) {
        Block target = getTargetBlock(player);
        if (target == null) {
            return new PlacementResult(false, "§c请看向一个方块！");
        }
        Location anchor = target.getLocation().add(0, 1, 0);
        PlacementSpec spec = spec(type);
        if (!hasSpace(player.getWorld(), anchor, spec, direction)) {
            return new PlacementResult(false, "§c目标区域空间不足或已有方块，请换位置放置。");
        }
        for (PlacementBlock block : spec.blocks()) {
            int[] rotated = rotateXZ(block.x(), block.z(), direction);
            Location point = anchor.clone().add(rotated[0], block.y(), rotated[1]);
            if (!placeBlock(point, block)) {
                return new PlacementResult(false, "§c搭建失败：无法放置方块 (" + block.x() + "," + block.y() + "," + block.z() + ")");
            }
        }
        initializeState(anchor, type, direction);
        return new PlacementResult(true, "§a预制建筑「" + spec.displayName() + "」搭建完成！");
    }

    private static PlacementSpec jianLingShiSpec() {
        List<PlacementBlock> blocks = new ArrayList<>();
        for (JianLingShiSupport.StructureBlock block : JianLingShiSupport.structure()) {
            blocks.add(new PlacementBlock(
                block.x(), block.y(), block.z(),
                block.material(), block.sfId(), block.dynamicCluster()
            ));
        }
        return new PlacementSpec("鉴灵石", blocks, true);
    }

    private static PlacementSpec ultimateSynthCoreSpec() {
        List<PlacementBlock> blocks = new ArrayList<>();
        for (UltimateSynthSupport.StructureBlock block : UltimateSynthSupport.coreStructure()) {
            blocks.add(new PlacementBlock(block.x(), block.y(), block.z(), block.material(), block.sfId(), false));
        }
        return new PlacementSpec("终极合成台核心", blocks, false);
    }

    private static PlacementSpec multiblockSpec(String name, Map<Integer, String> slotMaterials) {
        List<PlacementBlock> blocks = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : slotMaterials.entrySet()) {
            int slot = entry.getKey();
            blocks.add(new PlacementBlock(
                MULTIBLOCK_SLOT_X[slot], 0, MULTIBLOCK_SLOT_Z[slot],
                entry.getValue(), null, false
            ));
        }
        return new PlacementSpec(name, blocks, false);
    }

    private static boolean hasSpace(World world, Location anchor, PlacementSpec spec, int direction) {
        for (PlacementBlock block : spec.blocks()) {
            int[] rotated = rotateXZ(block.x(), block.z(), direction);
            Block existing = world.getBlockAt(
                anchor.getBlockX() + rotated[0],
                anchor.getBlockY() + block.y(),
                anchor.getBlockZ() + rotated[1]
            );
            if (!existing.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    private static boolean placeBlock(Location location, PlacementBlock block) {
        if (block.dynamicCluster()) {
            return SlimefunBlockPlacer.place(location, DEFAULT_CLUSTER);
        }
        if (block.sfId() != null && !block.sfId().isEmpty()) {
            return SlimefunBlockPlacer.place(location, block.sfId());
        }
        return SlimefunBlockPlacer.placeVanilla(location, block.material());
    }

    private static void initializeState(Location anchor, PrefabStructureType type, int direction) {
        switch (type) {
            case JIAN_LING_SHI -> {
                MachineScriptHelper.setData(anchor, JianLingShiSupport.PREFIX + "fx", String.valueOf(direction));
                MachineScriptHelper.setData(anchor, JianLingShiSupport.PREFIX + "zt", "0");
                MachineScriptHelper.setData(anchor, JianLingShiSupport.PREFIX + "ty", "0");
            }
            case ULTIMATE_SYNTH_CORE -> {
                MachineScriptHelper.setData(anchor, UltimateSynthSupport.DATA_DIR, "0");
                MachineScriptHelper.setData(anchor, UltimateSynthSupport.DATA_STATE, "0");
                MachineScriptHelper.setData(anchor, UltimateSynthSupport.DATA_PROJECTION, "0");
            }
            default -> {
            }
        }
    }

    private static int[] rotateXZ(int x, int z, int direction) {
        return switch (direction & 3) {
            case 1 -> new int[] {-z, x};
            case 2 -> new int[] {-x, -z};
            case 3 -> new int[] {z, -x};
            default -> new int[] {x, z};
        };
    }

    private static Block getTargetBlock(Player player) {
        BlockIterator iterator = new BlockIterator(player, 8);
        while (iterator.hasNext()) {
            Block block = iterator.next();
            if (!block.getType().isAir()) {
                return block;
            }
        }
        return null;
    }

    public static String directionLabel(int direction) {
        return switch (direction & 3) {
            case 1 -> "§f东";
            case 2 -> "§f南";
            case 3 -> "§f西";
            default -> "§f北";
        };
    }

    public static SlimefunItem resolveItem(org.bukkit.inventory.ItemStack stack) {
        return stack == null ? null : SlimefunItem.getByItem(stack);
    }
}
