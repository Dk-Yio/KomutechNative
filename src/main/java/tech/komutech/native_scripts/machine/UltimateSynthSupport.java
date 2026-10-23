package tech.komutech.native_scripts.machine;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tech.komutech.native_scripts.storage.WanXiangGuiStorage;
import tech.komutech.native_scripts.support.CultivationMath;
import tech.komutech.native_scripts.support.KomutechMenuHelper;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.PlayerAttributeStore;
import tech.komutech.native_scripts.support.SlimefunBlockPlacer;
import tech.origintech.native_scripts.support.MachineScriptHelper;

public final class UltimateSynthSupport {

    public static final String CORE_ID = "KOMUTECH_L_ZJ_終極合成台核心";
    public static final String PROCESSOR_ID = "KOMUTECH_L_ZJ_終極合成台";
    public static final String DATA_STATE = "KOMUTECH_L_ZJ_ZJHC_zt";
    public static final String DATA_DIR = "KOMUTECH_L_ZJ_ZJHC_fx";
    public static final String DATA_PROJECTION = "KOMUTECH_L_ZJ_ZJHC_ty";
    public static final String GUI_TITLE = "§6太极合成核心";
    public static final int[] INPUT_SLOTS = {0, 1, 2, 9, 10, 11, 18, 19, 20};
    private static final int[] DECORATION_SLOTS = {3, 4, 5, 12, 14, 21, 22, 23};
    public static final int[] OUTPUT_SLOTS = {6, 7, 8, 15, 16, 17, 24, 25, 26};
    public static final int WORK_SLOT = 13;
    public static final String ITEM_WUXING = "KOMUTECH_L_FZ_五行祖炁";
    public static final String ITEM_XXZZ = "Komutech_L_XX_ZZ";
    public static final String ITEM_KONGPU = "KOMUTECH_L_X_空蒲";
    public static final String ITEM_TIANXIANG = "KOMUTECH_L_FZ_天象祖炁";
    public static final String ITEM_ZAOHUA = "KOMUTECH_L_FZ_造化祖炁";
    public static final String ITEM_WU = "KOMUTECH_L_ZJ_無";
    public static final String ITEM_WANXIANG = "KOMUTECH_L_ZJ_萬象匱";
    public static final String ITEM_WANYANYI = "KOMUTECH_L_ZJ_萬衍儀";
    public static final Material ENDER_CHEST = Material.ENDER_CHEST;
    public static final String LING_ID = "KOMUTECH_L_DJ_JPLJ";

    /** 合成「無」所需最低灵气（元婴期） */
    public static final long SPIRIT_REQ_WU = 100_000L;
    /** 合成萬象匱 / 萬衍儀所需最低灵气（元婴期） */
    public static final long SPIRIT_REQ_ADVANCED = 100_000L;

    private static final StructureBlock[] CRAFT_STRUCTURE = buildCraftStructure();
    private static final StructureBlock[] CORE_STRUCTURE = buildCoreStructure();
    private static final Color[][] TRAIL_COLORS = {
        {
            Color.fromRGB(0xfc, 0x60, 0x76), Color.fromRGB(0xfd, 0x7a, 0x5f), Color.fromRGB(0xff, 0x9a, 0x44),
            Color.fromRGB(0xfd, 0x7a, 0x5f), Color.fromRGB(0xfc, 0x60, 0x76)
        },
        {
            Color.fromRGB(0xfb, 0xc2, 0xeb), Color.fromRGB(0xd1, 0xc2, 0xed), Color.fromRGB(0xa6, 0xc1, 0xee),
            Color.fromRGB(0xd1, 0xc2, 0xed), Color.fromRGB(0xfb, 0xc2, 0xeb)
        },
        {
            Color.fromRGB(0x43, 0xe9, 0x7b), Color.fromRGB(0x3e, 0xf1, 0xa9), Color.fromRGB(0x38, 0xf9, 0xd7),
            Color.fromRGB(0x3e, 0xf1, 0xa9), Color.fromRGB(0x43, 0xe9, 0x7b)
        }
    };

    private UltimateSynthSupport() {
    }

    public static String locationKey(Location location) {
        return location.getWorld().getName() + ","
            + location.getBlockX() + ","
            + location.getBlockY() + ","
            + location.getBlockZ();
    }

    public static boolean isProcessorInputSlot(int slot) {
        return containsSlot(INPUT_SLOTS, slot);
    }

    public static boolean isProcessorOutputSlot(int slot) {
        return containsSlot(OUTPUT_SLOTS, slot);
    }

    public static boolean isProcessorMaterialSlot(int slot) {
        return isProcessorInputSlot(slot) || isProcessorOutputSlot(slot);
    }

    /** @deprecated use {@link #isProcessorMaterialSlot} */
    public static boolean isProcessorInteractiveSlot(int slot) {
        return isProcessorMaterialSlot(slot);
    }

    public static boolean isProcessorDecorationSlot(int slot) {
        return containsSlot(DECORATION_SLOTS, slot);
    }

    private static boolean containsSlot(int[] slots, int slot) {
        for (int candidate : slots) {
            if (candidate == slot) {
                return true;
            }
        }
        return false;
    }

    public record StructureBlock(int x, int y, int z, String material, String sfId) {
    }

    public record StructureCheck(boolean valid, List<String> errors) {
    }

    public record Rotated(int x, int z) {
    }

    public static Rotated rot(int x, int z, int dir) {
        return switch (dir) {
            case 1 -> new Rotated(-z, x);
            case 2 -> new Rotated(-x, -z);
            case 3 -> new Rotated(z, -x);
            default -> new Rotated(x, z);
        };
    }

    public static StructureCheck checkStructure(Location coreLoc, StructureBlock[] structure) {
        World world = coreLoc.getWorld();
        if (world == null) {
            return new StructureCheck(false, List.of("世界无效"));
        }
        int dir = parseInt(MachineScriptHelper.getData(coreLoc, DATA_DIR), 0);
        List<String> errors = new ArrayList<>();
        for (StructureBlock block : structure) {
            Rotated r = rot(block.x(), block.z(), dir);
            int ax = coreLoc.getBlockX() + r.x();
            int ay = coreLoc.getBlockY() + block.y();
            int az = coreLoc.getBlockZ() + r.z();
            Block worldBlock = world.getBlockAt(ax, ay, az);
            if (!worldBlock.getType().name().equalsIgnoreCase(block.material())) {
                errors.add("位置 (" + (ax - coreLoc.getBlockX()) + "," + (ay - coreLoc.getBlockY()) + ","
                    + (az - coreLoc.getBlockZ()) + ") 需要 " + block.material());
                continue;
            }
            if (block.sfId() != null) {
                SlimefunItem sf = MachineScriptHelper.getSfItem(worldBlock.getLocation());
                String actualId = sf == null ? null : sf.getId();
                if (actualId == null || !idsEqual(block.sfId(), actualId)) {
                    errors.add("位置 (" + (ax - coreLoc.getBlockX()) + "," + (ay - coreLoc.getBlockY()) + ","
                        + (az - coreLoc.getBlockZ()) + ") 需要粘液物品 " + block.sfId()
                        + (actualId == null ? "" : "，找到 " + actualId));
                }
            }
        }
        return new StructureCheck(errors.isEmpty(), errors);
    }

    private static boolean idsEqual(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    public static StructureCheck checkCraftStructure(Location coreLoc) {
        return checkStructure(coreLoc, CRAFT_STRUCTURE);
    }

    public static StructureCheck checkCoreStructure(Location coreLoc) {
        return checkStructure(coreLoc, CORE_STRUCTURE);
    }

    public static StructureBlock[] coreStructure() {
        return CORE_STRUCTURE;
    }

    public static StructureBlock[] craftStructure() {
        return CRAFT_STRUCTURE;
    }

    public static Location getTargetProcessor(Player player) {
        Block block = player.getTargetBlockExact(5);
        if (block == null) {
            return null;
        }
        SlimefunItem sf = MachineScriptHelper.getSfItem(block.getLocation());
        return sf != null && PROCESSOR_ID.equals(sf.getId()) ? block.getLocation() : null;
    }

    public static boolean preCheck(Player player, Location procLoc, Object menu) {
        Location coreLoc = procLoc.clone().add(0, -1, 0);
        SlimefunItem coreSf = MachineScriptHelper.getSfItem(coreLoc);
        if (coreSf == null || !CORE_ID.equals(coreSf.getId())) {
            KomutechSupport.send(player, "§c核心机器无效！");
            return false;
        }
        if (parseInt(MachineScriptHelper.getData(coreLoc, DATA_STATE), 0) != 1) {
            KomutechSupport.send(player, "§c核心未激活！");
            return false;
        }
        for (int slot : OUTPUT_SLOTS) {
            if (MachineScriptHelper.getItemInSlot(menu, slot) == null) {
                break;
            }
            KomutechSupport.send(player, "§c输出槽已满！");
            return false;
        }
        MatchedRecipe recipe = matchRecipe(menu);
        if (recipe == null) {
            KomutechSupport.send(player, "§c没有匹配的配方！");
            return false;
        }
        if (recipe.spiritReq() > 0) {
            return checkCultivation(player, recipe.spiritReq());
        }
        return true;
    }

    public static boolean checkCultivation(Player player, long requiredSpirit) {
        Map<String, Object> attr = PlayerAttributeStore.load(player.getName());
        if (attr == null) {
            KomutechSupport.send(player, "§c无法读取修仙属性，无法合成");
            return false;
        }
        long spirit = CultivationMath.spiritValue(attr);
        if (spirit < requiredSpirit) {
            CultivationMath.CultivationInfo info = CultivationMath.getCultivationInfo(requiredSpirit);
            KomutechSupport.send(player, "§c修为不足！至少需要 §6" + info.stage() + " §c方可进行此合成");
            return false;
        }
        return true;
    }

    public static void finalCraft(Player player, Location procLoc, Object menu) {
        Location coreLoc = procLoc.clone().add(0, -1, 0);
        StructureCheck check = checkCraftStructure(coreLoc);
        if (!check.valid()) {
            KomutechSupport.send(player, "§c合成失败：合成期间结构被破坏！");
            return;
        }
        SlimefunItem coreSf = MachineScriptHelper.getSfItem(coreLoc);
        if (coreSf == null || !CORE_ID.equals(coreSf.getId())) {
            KomutechSupport.send(player, "§c核心机器已变更，合成失败！");
            return;
        }
        if (parseInt(MachineScriptHelper.getData(coreLoc, DATA_STATE), 0) != 1) {
            KomutechSupport.send(player, "§c核心已关闭，合成失败！");
            return;
        }
        MatchedRecipe recipe = matchRecipe(menu);
        if (recipe == null) {
            KomutechSupport.send(player, "§c合成失败：配方不匹配！");
            return;
        }
        if (recipe.spiritReq() > 0 && !checkCultivation(player, recipe.spiritReq())) {
            return;
        }
        ItemStack outputItem = getItemStack(recipe.outputId());
        if (outputItem == null) {
            KomutechSupport.send(player, "§c合成失败：输出物品无效！");
            return;
        }
        consumeRecipe(menu, recipe);
        prepareGraduationOutput(player, recipe.outputId(), outputItem);
        deliverCraftOutput(player, menu, outputItem);
        int consumedLing = consumeRandomLing(player, coreLoc);
        MachineScriptHelper.setData(coreLoc, DATA_STATE, "0");
        if (consumedLing > 0) {
            KomutechSupport.send(player, "§a合成成功！消耗了 " + consumedLing + " 个灵晶，核心已关闭。");
        } else {
            KomutechSupport.send(player, "§a合成成功！核心已关闭。");
        }
    }

    private static void prepareGraduationOutput(Player player, String outputId, ItemStack outputItem) {
        if (outputItem == null || player == null || !ITEM_WANXIANG.equalsIgnoreCase(outputId)) {
            return;
        }
        String baseName = "毕业-" + player.getName();
        String storageName = baseName;
        int suffix = 1;
        while (WanXiangGuiStorage.storageFileExists(player.getName(), storageName)) {
            storageName = baseName + "-" + suffix++;
        }
        WanXiangGuiStorage.setStorageName(outputItem, storageName);
        if (!WanXiangGuiStorage.storageFileExists(player.getName(), storageName)) {
            WanXiangGuiStorage.write(player.getName(), storageName, WanXiangGuiStorage.emptyData());
        }
    }

    private static void deliverCraftOutput(Player player, Object menu, ItemStack outputItem) {
        if (outputItem == null) {
            return;
        }
        if (player != null && player.isOnline()) {
            KomutechSupport.giveOrDrop(player, outputItem.clone());
            return;
        }
        for (int slot : OUTPUT_SLOTS) {
            if (MachineScriptHelper.getItemInSlot(menu, slot) == null) {
                KomutechMenuHelper.replaceExistingItem(menu, slot, outputItem.clone());
                break;
            }
        }
    }

    private static int consumeRandomLing(Player player, Location coreLoc) {
        World world = coreLoc.getWorld();
        if (world == null) {
            return 0;
        }
        int dir = parseInt(MachineScriptHelper.getData(coreLoc, DATA_DIR), 0);
        List<Location> lingLocations = new ArrayList<>();
        for (StructureBlock block : CRAFT_STRUCTURE) {
            if (!LING_ID.equals(block.sfId())) {
                continue;
            }
            Rotated r = rot(block.x(), block.z(), dir);
            lingLocations.add(coreLoc.clone().add(r.x(), block.y(), r.z()));
        }
        if (lingLocations.isEmpty()) {
            return 0;
        }
        int consumeCount = Math.min(ThreadLocalRandom.current().nextInt(8, 25), lingLocations.size());
        Set<Integer> selected = new HashSet<>();
        while (selected.size() < consumeCount) {
            selected.add(ThreadLocalRandom.current().nextInt(lingLocations.size()));
        }
        int removed = 0;
        for (int index : selected) {
            Location blockLoc = lingLocations.get(index);
            Block block = world.getBlockAt(blockLoc);
            SlimefunItem sf = MachineScriptHelper.getSfItem(blockLoc);
            if (block.getType() == Material.AMETHYST_CLUSTER
                && sf != null && LING_ID.equalsIgnoreCase(sf.getId())) {
                SlimefunBlockPlacer.remove(blockLoc);
                removed++;
            }
        }
        return removed;
    }

    public static List<Location> lingLocations(Location coreLoc) {
        int dir = parseInt(MachineScriptHelper.getData(coreLoc, DATA_DIR), 0);
        List<Location> locations = new ArrayList<>();
        for (StructureBlock block : CRAFT_STRUCTURE) {
            if (LING_ID.equals(block.sfId())) {
                Rotated r = rot(block.x(), block.z(), dir);
                locations.add(coreLoc.clone().add(r.x(), block.y(), r.z()));
            }
        }
        return locations;
    }

    public static List<Location> beaconLocations(Location coreLoc) {
        int dir = parseInt(MachineScriptHelper.getData(coreLoc, DATA_DIR), 0);
        List<Location> locations = new ArrayList<>();
        for (StructureBlock block : CRAFT_STRUCTURE) {
            if ("beacon".equals(block.material())) {
                Rotated r = rot(block.x(), block.z(), dir);
                locations.add(coreLoc.clone().add(r.x(), block.y(), r.z()));
            }
        }
        return locations;
    }

    public static Color trailColor(int trailId, int index) {
        Color[] colors = TRAIL_COLORS[Math.floorMod(trailId, TRAIL_COLORS.length)];
        return colors[Math.floorMod(index, colors.length)];
    }

    public static Location trailPosition(Location center, int trailId, double time) {
        double baseAngle = time * 0.5 + trailId * 2.0;
        double radius = 6.0 + Math.sin(time * 0.3 + trailId);
        double height = Math.sin(time * 0.8 + trailId) * 2.0;
        return new Location(
            center.getWorld(),
            center.getX() + radius * Math.cos(baseAngle),
            center.getY() + 2.0 + height,
            center.getZ() + radius * Math.sin(baseAngle)
        );
    }

    private record SlotRequirement(int slot, String slimefunId, Material material, int amount) {
        static SlotRequirement sf(int slot, String id, int amount) {
            return new SlotRequirement(slot, id, null, amount);
        }

        static SlotRequirement mat(int slot, Material material, int amount) {
            return new SlotRequirement(slot, null, material, amount);
        }
    }

    private record MatchedRecipe(String outputId, long spiritReq, SlotRequirement[] consumes) {
    }

    private static MatchedRecipe matchRecipe(Object menu) {
        int[] emptyForWu = {0, 2, 9, 11, 19};
        int[] surroundForAdvanced = {0, 1, 2, 9, 11, 18, 19, 20};
        if (slotsEmpty(menu, emptyForWu)
            && slotMatches(menu, SlotRequirement.sf(1, ITEM_WUXING, 1))
            && slotMatches(menu, SlotRequirement.sf(10, ITEM_XXZZ, 1))
            && slotMatches(menu, SlotRequirement.sf(18, ITEM_TIANXIANG, 1))
            && slotMatches(menu, SlotRequirement.sf(20, ITEM_ZAOHUA, 1))) {
            return new MatchedRecipe(ITEM_WU, SPIRIT_REQ_WU, new SlotRequirement[] {
                SlotRequirement.sf(1, ITEM_WUXING, 1),
                SlotRequirement.sf(10, ITEM_XXZZ, 1),
                SlotRequirement.sf(18, ITEM_TIANXIANG, 1),
                SlotRequirement.sf(20, ITEM_ZAOHUA, 1)
            });
        }
        if (slotMatches(menu, SlotRequirement.sf(10, ITEM_WU, 1))
            && slotsMatchMaterial(menu, surroundForAdvanced, ENDER_CHEST)) {
            SlotRequirement[] consumes = buildSurroundConsumes(surroundForAdvanced, ENDER_CHEST);
            SlotRequirement[] all = mergeRequirements(new SlotRequirement[] {SlotRequirement.sf(10, ITEM_WU, 1)}, consumes);
            return new MatchedRecipe(ITEM_WANXIANG, SPIRIT_REQ_ADVANCED, all);
        }
        if (slotMatches(menu, SlotRequirement.sf(10, ITEM_WU, 1))
            && slotsMatchSlimefun(menu, surroundForAdvanced, ITEM_XXZZ)) {
            SlotRequirement[] consumes = buildSurroundConsumesSf(surroundForAdvanced, ITEM_XXZZ);
            SlotRequirement[] all = mergeRequirements(new SlotRequirement[] {SlotRequirement.sf(10, ITEM_WU, 1)}, consumes);
            return new MatchedRecipe(ITEM_WANYANYI, SPIRIT_REQ_ADVANCED, all);
        }
        if (matchesKongPu(menu)) {
            return new MatchedRecipe(ITEM_KONGPU, 0L, new SlotRequirement[] {
                SlotRequirement.sf(0, ITEM_WUXING, 1),
                SlotRequirement.sf(1, ITEM_TIANXIANG, 1),
                SlotRequirement.sf(2, ITEM_ZAOHUA, 1),
                SlotRequirement.sf(9, "KOMUTECH_L_X_莲台", 1),
                SlotRequirement.sf(10, ITEM_WU, 64),
                SlotRequirement.sf(11, "KOMUTECH_L_X_莲台", 1),
                SlotRequirement.sf(18, "Komutech_L_KW_TXY", 1),
                SlotRequirement.sf(19, "KOMUTECH_L_X_云榻", 1),
                SlotRequirement.sf(20, "Komutech_L_KW_TXY", 1)
            });
        }
        return null;
    }

    private static boolean matchesKongPu(Object menu) {
        return slotMatches(menu, SlotRequirement.sf(0, ITEM_WUXING, 1))
            && slotMatches(menu, SlotRequirement.sf(1, ITEM_TIANXIANG, 1))
            && slotMatches(menu, SlotRequirement.sf(2, ITEM_ZAOHUA, 1))
            && slotMatches(menu, SlotRequirement.sf(9, "KOMUTECH_L_X_莲台", 1))
            && slotMatches(menu, SlotRequirement.sf(10, ITEM_WU, 64))
            && slotMatches(menu, SlotRequirement.sf(11, "KOMUTECH_L_X_莲台", 1))
            && slotMatches(menu, SlotRequirement.sf(18, "Komutech_L_KW_TXY", 1))
            && slotMatches(menu, SlotRequirement.sf(19, "KOMUTECH_L_X_云榻", 1))
            && slotMatches(menu, SlotRequirement.sf(20, "Komutech_L_KW_TXY", 1));
    }

    private static SlotRequirement[] buildSurroundConsumes(int[] slots, Material material) {
        SlotRequirement[] consumes = new SlotRequirement[slots.length];
        for (int i = 0; i < slots.length; i++) {
            consumes[i] = SlotRequirement.mat(slots[i], material, 1);
        }
        return consumes;
    }

    private static SlotRequirement[] buildSurroundConsumesSf(int[] slots, String id) {
        SlotRequirement[] consumes = new SlotRequirement[slots.length];
        for (int i = 0; i < slots.length; i++) {
            consumes[i] = SlotRequirement.sf(slots[i], id, 1);
        }
        return consumes;
    }

    private static SlotRequirement[] mergeRequirements(SlotRequirement[] first, SlotRequirement[] second) {
        SlotRequirement[] merged = new SlotRequirement[first.length + second.length];
        System.arraycopy(first, 0, merged, 0, first.length);
        System.arraycopy(second, 0, merged, first.length, second.length);
        return merged;
    }

    private static void consumeRecipe(Object menu, MatchedRecipe recipe) {
        for (SlotRequirement requirement : recipe.consumes()) {
            consumeSlot(menu, requirement);
        }
    }

    private static void consumeSlot(Object menu, SlotRequirement requirement) {
        MachineScriptHelper.ItemStackInMenu inSlot = MachineScriptHelper.getItemInSlot(menu, requirement.slot());
        if (inSlot == null) {
            return;
        }
        ItemStack item = inSlot.stack();
        if (item.getAmount() > requirement.amount()) {
            item.setAmount(item.getAmount() - requirement.amount());
            KomutechMenuHelper.replaceExistingItem(menu, requirement.slot(), item);
        } else {
            KomutechMenuHelper.replaceExistingItem(menu, requirement.slot(), null);
        }
    }

    private static boolean slotMatches(Object menu, SlotRequirement requirement) {
        MachineScriptHelper.ItemStackInMenu inSlot = MachineScriptHelper.getItemInSlot(menu, requirement.slot());
        if (inSlot == null) {
            return false;
        }
        ItemStack stack = inSlot.stack();
        if (stack.getAmount() < requirement.amount()) {
            return false;
        }
        if (requirement.material() != null) {
            return stack.getType() == requirement.material();
        }
        SlimefunItem sf = SlimefunItem.getByItem(stack);
        return sf != null && idsEqual(sf.getId(), requirement.slimefunId());
    }

    private static boolean slotsEmpty(Object menu, int[] slots) {
        for (int slot : slots) {
            if (MachineScriptHelper.getItemInSlot(menu, slot) != null) {
                return false;
            }
        }
        return true;
    }

    private static boolean slotsMatchSlimefun(Object menu, int[] slots, String expectedId) {
        for (int slot : slots) {
            if (!slotMatches(menu, SlotRequirement.sf(slot, expectedId, 1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean slotsMatchMaterial(Object menu, int[] slots, Material expected) {
        for (int slot : slots) {
            MachineScriptHelper.ItemStackInMenu inSlot = MachineScriptHelper.getItemInSlot(menu, slot);
            if (inSlot == null || inSlot.stack().getType() != expected) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack getItemStack(String id) {
        SlimefunItem sf = SlimefunItem.getById(id);
        // Must clone: SlimefunItemStack templates are immutable (setItemMeta throws WrongItemStackException).
        return sf == null ? null : sf.getItem().clone();
    }

    public static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static StructureBlock[] buildCraftStructure() {
        String[] rows = {
            "-5,0,-1,black_concrete", "-5,0,-2,black_concrete", "-4,0,-2,black_concrete", "-4,0,-3,black_concrete",
            "-4,0,-4,black_concrete", "-3,0,-3,black_concrete", "-3,0,-4,black_concrete", "-2,0,-3,black_concrete",
            "-2,0,-4,black_concrete", "-2,0,-5,black_concrete", "-1,0,-2,black_concrete", "-1,0,-3,black_concrete",
            "-1,0,-4,black_concrete", "-1,0,-5,black_concrete", "0,0,-1,black_concrete", "0,0,-2,black_concrete",
            "0,0,-3,black_concrete", "0,0,-4,black_concrete", "0,0,-5,black_concrete", "1,0,1,black_concrete",
            "1,0,0,black_concrete", "1,0,-1,black_concrete", "1,0,-2,black_concrete", "1,0,-3,black_concrete",
            "1,0,-4,black_concrete", "1,0,-5,black_concrete", "2,0,2,black_concrete", "2,0,1,black_concrete",
            "2,0,0,black_concrete", "2,0,-1,black_concrete", "2,0,-3,black_concrete", "2,0,-4,black_concrete",
            "2,0,-5,black_concrete", "3,0,2,black_concrete", "3,0,1,black_concrete", "3,0,0,black_concrete",
            "3,0,-1,black_concrete", "3,0,-2,black_concrete", "3,0,-3,black_concrete", "3,0,-4,black_concrete",
            "4,0,1,black_concrete", "4,0,0,black_concrete", "4,0,-1,black_concrete", "4,0,-2,black_concrete",
            "4,0,-3,black_concrete", "4,0,-4,black_concrete", "5,0,0,black_concrete", "5,0,-1,black_concrete",
            "5,0,-2,black_concrete", "-5,0,0,white_concrete", "-5,0,1,white_concrete", "-5,0,2,white_concrete",
            "-4,0,-1,white_concrete", "-4,0,0,white_concrete", "-4,0,1,white_concrete", "-4,0,2,white_concrete",
            "-4,0,3,white_concrete", "-4,0,4,white_concrete", "-3,0,-2,white_concrete", "-3,0,-1,white_concrete",
            "-3,0,0,white_concrete", "-3,0,1,white_concrete", "-3,0,2,white_concrete", "-3,0,3,white_concrete",
            "-3,0,4,white_concrete", "-2,0,-2,white_concrete", "-2,0,-1,white_concrete", "-2,0,0,white_concrete",
            "-2,0,1,white_concrete", "-2,0,3,white_concrete", "-2,0,4,white_concrete", "-2,0,5,white_concrete",
            "-1,0,-1,white_concrete", "-1,0,0,white_concrete", "-1,0,1,white_concrete", "-1,0,2,white_concrete",
            "-1,0,3,white_concrete", "-1,0,4,white_concrete", "-1,0,5,white_concrete", "0,0,1,white_concrete",
            "0,0,2,white_concrete", "0,0,3,white_concrete", "0,0,4,white_concrete", "0,0,5,white_concrete",
            "1,0,2,white_concrete", "1,0,3,white_concrete", "1,0,4,white_concrete", "1,0,5,white_concrete",
            "2,0,3,white_concrete", "2,0,4,white_concrete", "2,0,5,white_concrete", "3,0,3,white_concrete",
            "3,0,4,white_concrete", "4,0,2,white_concrete", "4,0,3,white_concrete", "4,0,4,white_concrete",
            "5,0,1,white_concrete", "5,0,2,white_concrete", "-2,0,2,black_concrete", "2,0,-2,white_concrete",
            "-5,5,-5,beacon", "5,5,-5,beacon", "-5,5,5,beacon", "5,5,5,beacon",
            "-5,5,-6,amethyst_cluster," + LING_ID, "-5,5,-4,amethyst_cluster," + LING_ID,
            "-6,5,-5,amethyst_cluster," + LING_ID, "-4,5,-5,amethyst_cluster," + LING_ID,
            "-5,6,-5,amethyst_cluster," + LING_ID, "5,5,-6,amethyst_cluster," + LING_ID,
            "5,5,-4,amethyst_cluster," + LING_ID, "6,5,-5,amethyst_cluster," + LING_ID,
            "4,5,-5,amethyst_cluster," + LING_ID, "5,6,-5,amethyst_cluster," + LING_ID,
            "-5,5,6,amethyst_cluster," + LING_ID, "-5,5,4,amethyst_cluster," + LING_ID,
            "-6,5,5,amethyst_cluster," + LING_ID, "-4,5,5,amethyst_cluster," + LING_ID,
            "-5,6,5,amethyst_cluster," + LING_ID, "5,5,6,amethyst_cluster," + LING_ID,
            "5,5,4,amethyst_cluster," + LING_ID, "6,5,5,amethyst_cluster," + LING_ID,
            "4,5,5,amethyst_cluster," + LING_ID, "5,6,5,amethyst_cluster," + LING_ID,
            "-5,1,-2,amethyst_cluster," + LING_ID, "-4,1,-4,amethyst_cluster," + LING_ID,
            "-2,1,-5,amethyst_cluster," + LING_ID, "5,1,-2,amethyst_cluster," + LING_ID,
            "4,1,-4,amethyst_cluster," + LING_ID, "2,1,-5,amethyst_cluster," + LING_ID,
            "-5,1,2,amethyst_cluster," + LING_ID, "-4,1,4,amethyst_cluster," + LING_ID,
            "-2,1,5,amethyst_cluster," + LING_ID, "5,1,2,amethyst_cluster," + LING_ID,
            "4,1,4,amethyst_cluster," + LING_ID, "2,1,5,amethyst_cluster," + LING_ID
        };
        return parseRows(rows);
    }

    private static StructureBlock[] buildCoreStructure() {
        List<StructureBlock> blocks = new ArrayList<>(Arrays.asList(CRAFT_STRUCTURE));
        String[] extra = {
            "0,0,0,shroomlight," + CORE_ID, "0,1,0,player_head," + PROCESSOR_ID,
            "-6,0,2,crying_obsidian", "-6,0,1,crying_obsidian", "-6,0,0,crying_obsidian", "-6,0,-1,crying_obsidian",
            "-6,0,-2,crying_obsidian", "-5,0,4,crying_obsidian", "-5,0,3,crying_obsidian", "-5,0,-4,crying_obsidian",
            "-5,0,-3,crying_obsidian", "-4,0,5,crying_obsidian", "-4,0,-5,crying_obsidian", "-3,0,5,crying_obsidian",
            "-3,0,-5,crying_obsidian", "-2,0,6,crying_obsidian", "-2,0,-6,crying_obsidian", "-1,0,6,crying_obsidian",
            "-1,0,-6,crying_obsidian", "0,0,6,crying_obsidian", "0,0,-6,crying_obsidian", "1,0,6,crying_obsidian",
            "1,0,-6,crying_obsidian", "2,0,6,crying_obsidian", "2,0,-6,crying_obsidian", "3,0,5,crying_obsidian",
            "3,0,-5,crying_obsidian", "4,0,5,crying_obsidian", "4,0,-5,crying_obsidian", "5,0,4,crying_obsidian",
            "5,0,3,crying_obsidian", "5,0,-4,crying_obsidian", "5,0,-3,crying_obsidian", "6,0,2,crying_obsidian",
            "6,0,1,crying_obsidian", "6,0,0,crying_obsidian", "6,0,-1,crying_obsidian", "6,0,-2,crying_obsidian",
            "-5,3,-5,end_rod", "-5,4,-5,lantern", "5,3,-5,end_rod", "5,4,-5,lantern",
            "-5,3,5,end_rod", "-5,4,5,lantern", "5,3,5,end_rod", "5,4,5,lantern"
        };
        blocks.addAll(Arrays.asList(parseRows(extra)));
        return blocks.toArray(StructureBlock[]::new);
    }

    private static StructureBlock[] parseRows(String[] rows) {
        StructureBlock[] blocks = new StructureBlock[rows.length];
        for (int i = 0; i < rows.length; i++) {
            String[] parts = rows[i].split(",");
            blocks[i] = new StructureBlock(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                parts[3].toLowerCase(Locale.ROOT),
                parts.length > 4 ? parts[4] : null
            );
        }
        return blocks;
    }
}
