package tech.komutech.native_scripts.wudao;

import tech.komutech.native_scripts.support.EntityQueries;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechMenuHelper;
import tech.origintech.native_scripts.support.MachineScriptHelper;

/**
 * 元素悟道石。各元素感测条件不同；原先 Native 误把全部元素写成金元素高炉逻辑。
 */
public final class ElementWudaoScript implements NativeScript {

    private static final long GENERATION_TIME = 15_000L;
    private static final long DETECTION_INTERVAL = 3_000L;
    private static final long FINAL_COUNTDOWN = 3_000L;
    /** 悟道成功后冷却，防止站着连续刷；期间不清槽，法则留给玩家取走 */
    private static final long SUCCESS_COOLDOWN = 5_000L;
    private static final int MIN_ITEMS = 1;
    private static final int BASE_MAX_ITEMS = 8;
    private static final int MAX_ITEMS_LIMIT = 32;
    /** 连续检测不到玩家的次数后才中断本轮（约 15 秒），避免短暂离位直接失败 */
    private static final int MAX_EMPTY_CHECKS = 5;
    private static final String TARGET_MACHINE_ID = "KOMUTECH_L_JQ_FZCZQ";
    private static final String TLY_ITEM_ID = "KOMUTECH_L_KW_TLY";

    private static final Set<Material> DIRT_TYPES = EnumSet.of(
        Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.DIRT_PATH,
        Material.PODZOL, Material.MYCELIUM, Material.ROOTED_DIRT, Material.MUD, Material.FARMLAND
    );
    private static final Set<Material> ICE_TYPES = EnumSet.of(
        Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE
    );

    public enum SenseKind {
        GOLD(4, "个高炉"),
        WOOD(4, "种树苗"),
        WATER(4, "处流水"),
        FIRE(4, "团火焰"),
        EARTH(4, "种泥土"),
        ICE(8, "种寒冰"),
        WIND(4, "个旋风人"),
        LIGHTNING(8, "个避雷针"),
        MECHANISM(4, "个铁傀儡"),
        ALCHEMY(4, "个女巫"),
        SOUND(4, "个监守者"),
        SPACE(8, "块传送门"),
        KILL(4, "个小岛人");

        final int bonus;
        final String unitLabel;

        SenseKind(int bonus, String unitLabel) {
            this.bonus = bonus;
            this.unitLabel = unitLabel;
        }
    }

    private final String machineId;
    private final String targetItemId;
    private final SenseKind kind;
    private final Map<String, WudaoState> states = new HashMap<>();

    public ElementWudaoScript(String machineId, String targetItemId, SenseKind kind) {
        this.machineId = machineId;
        this.targetItemId = targetItemId;
        this.kind = kind;
    }

    @Override
    public boolean hasTick() {
        return true;
    }
    @Override
    public Object invoke(String functionName, Object... args) {
        if ("tick".equals(functionName)) {
            MachineScriptHelper.MachineContext ctx = MachineScriptHelper.parse(args[0]);
            if (ctx != null && machineId.equals(MachineScriptHelper.getMachineId(ctx.machine()))) {
                handleTick(ctx.block().getLocation());
            }
            return null;
        }
        if ("onPlace".equals(functionName) && args[0] instanceof BlockPlaceEvent event) {
            states.put(blockKey(event.getBlock().getLocation()), new WudaoState());
            event.getPlayer().sendMessage(ChatColor.GREEN + "✓ 已放置悟道石");
            return null;
        }
        if ("onBreak".equals(functionName) && args[0] instanceof BlockBreakEvent event) {
            states.remove(blockKey(event.getBlock().getLocation()));
            return null;
        }
        return null;
    }

    private void handleTick(Location loc) {
        String key = blockKey(loc);
        WudaoState state = states.computeIfAbsent(key, ignored -> new WudaoState());
        long now = System.currentTimeMillis();
        if (now - state.lastCheck < DETECTION_INTERVAL) {
            return;
        }
        state.lastCheck = now;
        Player player = findPlayerOnStone(loc);
        if (player != null) {
            state.emptyChecks = 0;
            processPlayer(loc, player, now, state);
        } else {
            handleNoPlayer(state);
        }
    }

    private void handleNoPlayer(WudaoState state) {
        state.emptyChecks++;
        if (state.emptyChecks < MAX_EMPTY_CHECKS) {
            return;
        }
        // 已生成法则时不清槽，避免「悟了却拿不到」
        state.timer = null;
        state.emptyChecks = 0;
        state.cooldownUntil = 0L;
    }

    private void processPlayer(Location loc, Player player, long now, WudaoState state) {
        if (now < state.cooldownUntil) {
            return;
        }
        WudaoTimer timer = state.timer;
        if (timer == null) {
            SenseResult sense = senseAndConsume(loc);
            if (sense.count() <= 0) {
                return;
            }
            int maxItems = Math.min(BASE_MAX_ITEMS + sense.count() * kind.bonus, MAX_ITEMS_LIMIT);
            state.timer = new WudaoTimer(player.getUniqueId(), now, sense, maxItems);
            player.sendMessage(ChatColor.GREEN + "[悟道石] 感悟" + sense.count() + kind.unitLabel + "，开始悟道！");
            return;
        }
        if (!timer.playerId.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[悟道石] 玩家已更换，悟道中断！");
            state.timer = null;
            return;
        }
        long elapsed = now - timer.startTime;
        if (kind == SenseKind.GOLD && !timer.hasConverted && checkIronDestroyed(timer.sense.furnaces())) {
            player.sendMessage(ChatColor.RED + "[悟道石] 粗铁块被破坏，悟道中断！");
            state.timer = null;
            return;
        }
        if (kind == SenseKind.GOLD && !timer.hasConverted && elapsed >= GENERATION_TIME / 2) {
            convertIronBlocks(timer.sense.furnaces());
            timer.hasConverted = true;
            player.sendMessage(ChatColor.YELLOW + "[悟道石] 唔~唔~唔~");
        } else if (kind != SenseKind.GOLD && !timer.notifiedHalf && elapsed >= GENERATION_TIME / 2) {
            player.sendMessage(ChatColor.YELLOW + "[悟道石] 唔~唔~唔~");
            timer.notifiedHalf = true;
        }
        if (!timer.notifiedFinal && elapsed >= GENERATION_TIME - FINAL_COUNTDOWN) {
            player.sendMessage(ChatColor.YELLOW + "[悟道石] 来啦！");
            timer.notifiedFinal = true;
        }
        if (!timer.generated && elapsed >= GENERATION_TIME) {
            boolean ok = generateItems(loc, player, timer.maxItems, timer.sense.count());
            timer.generated = true;
            state.timer = null;
            if (ok) {
                state.cooldownUntil = now + SUCCESS_COOLDOWN;
            }
        }
    }

    private SenseResult senseAndConsume(Location loc) {
        return switch (kind) {
            case GOLD -> senseGold(loc);
            case WOOD -> SenseResult.of(clearSaplings(loc));
            case WATER -> SenseResult.of(replaceFlowingWater(loc));
            case FIRE -> SenseResult.of(clearBlocks(loc, t -> t == Material.FIRE || t == Material.SOUL_FIRE));
            case EARTH -> SenseResult.of(clearDistinctBlocks(loc, DIRT_TYPES));
            case ICE -> SenseResult.of(clearDistinctBlocks(loc, ICE_TYPES));
            case WIND -> SenseResult.of(clearEntities(loc, EntityType.BREEZE));
            case LIGHTNING -> SenseResult.of(clearBlocks(loc, t -> t == Material.LIGHTNING_ROD));
            case MECHANISM -> SenseResult.of(clearEntities(loc, EntityType.IRON_GOLEM));
            case ALCHEMY -> SenseResult.of(clearEntities(loc, EntityType.WITCH));
            case SOUND -> SenseResult.of(clearEntities(loc, EntityType.WARDEN));
            case SPACE -> SenseResult.of(clearBlocks(loc, t -> t == Material.NETHER_PORTAL));
            case KILL -> SenseResult.of(clearEntities(loc, EntityType.ZOMBIE, EntityType.SKELETON));
        };
    }

    private SenseResult senseGold(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return SenseResult.of(0);
        }
        List<FurnaceSpot> furnaces = new ArrayList<>();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (world.getBlockAt(x + dx, y + dy, z + dz).getType() == Material.BLAST_FURNACE
                        && world.getBlockAt(x + dx, y + dy + 1, z + dz).getType() == Material.RAW_IRON_BLOCK) {
                        furnaces.add(new FurnaceSpot(world, x + dx, y + dy + 1, z + dz));
                    }
                }
            }
        }
        return SenseResult.furnaces(furnaces);
    }

    private int clearSaplings(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return 0;
        }
        Set<String> types = new HashSet<>();
        forEachInArea(loc, block -> {
            String name = block.getType().name();
            if (name.contains("SAPLING") && !"DEAD_BUSH".equals(name)) {
                types.add(name);
                block.setType(Material.AIR);
            }
        });
        return types.size();
    }

    private int replaceFlowingWater(Location loc) {
        int[] count = {0};
        forEachInArea(loc, block -> {
            if (block.getType() != Material.WATER) {
                return;
            }
            BlockData data = block.getBlockData();
            if (data instanceof Levelled levelled && levelled.getLevel() > 0) {
                levelled.setLevel(0);
                block.setBlockData(levelled);
                count[0]++;
            }
        });
        return count[0];
    }

    private int clearBlocks(Location loc, Predicate<Material> matcher) {
        int[] count = {0};
        forEachInArea(loc, block -> {
            if (matcher.test(block.getType())) {
                count[0]++;
                block.setType(Material.AIR);
            }
        });
        return count[0];
    }

    private int clearDistinctBlocks(Location loc, Set<Material> allowed) {
        Set<Material> found = EnumSet.noneOf(Material.class);
        forEachInArea(loc, block -> {
            Material type = block.getType();
            if (allowed.contains(type)) {
                found.add(type);
                block.setType(Material.AIR);
            }
        });
        return found.size();
    }

    private int clearEntities(Location loc, EntityType... types) {
        World world = loc.getWorld();
        if (world == null) {
            return 0;
        }
        Set<EntityType> wanted = EnumSet.noneOf(EntityType.class);
        for (EntityType type : types) {
            wanted.add(type);
        }
        Location center = loc.clone().add(0.5, 1.0, 0.5);
        int count = 0;
        for (Entity entity : EntityQueries.entities(world, center, 2.5, 1.5, 2.5)) {
            if (wanted.contains(entity.getType()) && entity.isValid() && !entity.isDead()) {
                entity.remove();
                count++;
            }
        }
        return count;
    }

    private void forEachInArea(Location loc, java.util.function.Consumer<Block> action) {
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        int cx = loc.getBlockX();
        int cy = loc.getBlockY();
        int cz = loc.getBlockZ();
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int y = cy - 1; y <= cy + 1; y++) {
                for (int z = cz - 2; z <= cz + 2; z++) {
                    action.accept(world.getBlockAt(x, y, z));
                }
            }
        }
    }

    private Player findPlayerOnStone(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return null;
        }
        Location center = loc.clone().add(0.5, 1.0, 0.5);
        for (Entity entity : EntityQueries.entities(world, center, 1.0, 2.0, 1.0)) {
            if (entity instanceof Player player && player.isValid() && !player.isDead()) {
                return player;
            }
        }
        return null;
    }

    private boolean checkIronDestroyed(List<FurnaceSpot> furnaces) {
        for (FurnaceSpot furnace : furnaces) {
            Material expected = furnace.converted ? Material.IRON_BLOCK : Material.RAW_IRON_BLOCK;
            if (furnace.world.getBlockAt(furnace.x, furnace.y, furnace.z).getType() != expected) {
                return true;
            }
        }
        return false;
    }

    private void convertIronBlocks(List<FurnaceSpot> furnaces) {
        for (FurnaceSpot furnace : furnaces) {
            Block block = furnace.world.getBlockAt(furnace.x, furnace.y, furnace.z);
            if (block.getType() == Material.RAW_IRON_BLOCK) {
                block.setType(Material.IRON_BLOCK);
                furnace.converted = true;
            }
        }
    }

    private boolean generateItems(Location wudaoLoc, Player player, int maxItems, int senseCount) {
        Location targetLoc = wudaoLoc.clone().add(0, 0, 1);
        SlimefunItem targetMachine = MachineScriptHelper.getSfItem(targetLoc);
        if (targetMachine == null || !TARGET_MACHINE_ID.equals(targetMachine.getId())) {
            player.sendMessage(ChatColor.RED + "[悟道石] 目标机器不存在！请在悟道石 +Z（南）侧放置法则承载器");
            return false;
        }
        Object menu = MachineScriptHelper.getMenu(targetLoc);
        if (menu == null) {
            player.sendMessage(ChatColor.RED + "[悟道石] 无法打开法则承载器菜单，请重放机器后再试");
            return false;
        }
        if (kind == SenseKind.LIGHTNING && !consumeTly(menu)) {
            player.sendMessage(ChatColor.RED + "[悟道石] 目标机器中没有霆雷玉！");
            return false;
        }
        SlimefunItem output = SlimefunItem.getById(targetItemId);
        if (output == null) {
            player.sendMessage(ChatColor.RED + "[悟道石] 无法找到物品: " + targetItemId);
            return false;
        }
        ArrayList<Integer> emptySlots = new ArrayList<>();
        for (int slot = 9; slot <= 44; slot++) {
            MachineScriptHelper.ItemStackInMenu item = MachineScriptHelper.getItemInSlot(menu, slot);
            if (item == null || item.stack() == null || item.stack().getType().isAir()) {
                emptySlots.add(slot);
            }
        }
        if (emptySlots.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[悟道石] 法则承载器已满，请先取出物品后再悟道");
            return false;
        }
        int count = Math.min(
            MIN_ITEMS + ThreadLocalRandom.current().nextInt(maxItems - MIN_ITEMS + 1),
            emptySlots.size()
        );
        ItemStack stack = output.getItem().clone();
        stack.setAmount(1);
        for (int i = 0; i < count; i++) {
            KomutechMenuHelper.replaceExistingItem(menu, emptySlots.get(i), stack.clone());
        }
        player.sendMessage(ChatColor.GOLD + "[悟道石] 我悟了！！！");
        player.sendMessage(ChatColor.GREEN + "[悟道石] 感悟" + senseCount + kind.unitLabel
            + "，生成 " + count + " 个物品（请从南侧法则承载器取出）");
        return true;
    }

    private boolean consumeTly(Object menu) {
        ItemStack[] contents = MachineScriptHelper.getMenuContents(menu);
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            SlimefunItem sf = SlimefunItem.getByItem(item);
            if (sf == null || !TLY_ITEM_ID.equals(sf.getId())) {
                continue;
            }
            if (item.getAmount() > 1) {
                ItemStack copy = item.clone();
                copy.setAmount(item.getAmount() - 1);
                KomutechMenuHelper.replaceExistingItem(menu, slot, copy);
            } else {
                KomutechMenuHelper.replaceExistingItem(menu, slot, null);
            }
            return true;
        }
        return false;
    }

    private static String blockKey(Location loc) {
        World world = loc.getWorld();
        String worldId = world == null ? "null" : world.getUID().toString();
        return worldId + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private static final class WudaoState {
        long lastCheck;
        int emptyChecks;
        long cooldownUntil;
        WudaoTimer timer;
    }

    private static final class WudaoTimer {
        final UUID playerId;
        final long startTime;
        final SenseResult sense;
        final int maxItems;
        boolean hasConverted;
        boolean notifiedHalf;
        boolean notifiedFinal;
        boolean generated;

        WudaoTimer(UUID playerId, long startTime, SenseResult sense, int maxItems) {
            this.playerId = playerId;
            this.startTime = startTime;
            this.sense = sense;
            this.maxItems = maxItems;
        }
    }

    private record SenseResult(int count, List<FurnaceSpot> furnaces) {
        static SenseResult of(int count) {
            return new SenseResult(count, List.of());
        }

        static SenseResult furnaces(List<FurnaceSpot> furnaces) {
            return new SenseResult(furnaces.size(), furnaces);
        }
    }

    private static final class FurnaceSpot {
        final World world;
        final int x;
        final int y;
        final int z;
        boolean converted;

        FurnaceSpot(World world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
