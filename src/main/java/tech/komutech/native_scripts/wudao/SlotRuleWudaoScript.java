package tech.komutech.native_scripts.wudao;

import tech.komutech.native_scripts.support.EntityQueries;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechMenuHelper;
import tech.origintech.native_scripts.support.MachineScriptHelper;

public final class SlotRuleWudaoScript implements NativeScript {

    private static final long TIME = 15_000L;
    private static final long INTERVAL = 3_000L;
    private static final int MAX_EMPTY = 5;

    private final String machineId;
    private final String southMachineId;
    private final String outputId;
    private final int[] outputSlots;
    private final List<SlotRule> rules;
    private final String startMsg;
    private final String halfMsg;
    private final String finalMsg;
    private final String successMsg;
    private final String placeMsg;

    private final Map<String, State> states = new HashMap<>();

    public SlotRuleWudaoScript(
        String machineId,
        String southMachineId,
        String outputId,
        int[] outputSlots,
        List<SlotRule> rules,
        String startMsg,
        String halfMsg,
        String finalMsg,
        String successMsg,
        String placeMsg
    ) {
        this.machineId = machineId;
        this.southMachineId = southMachineId;
        this.outputId = outputId;
        this.outputSlots = outputSlots;
        this.rules = rules;
        this.startMsg = startMsg;
        this.halfMsg = halfMsg;
        this.finalMsg = finalMsg;
        this.successMsg = successMsg;
        this.placeMsg = placeMsg;
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
            states.put(blockKey(event.getBlock().getLocation()), new State());
            event.getPlayer().sendMessage(ChatColor.GOLD + placeMsg);
            return null;
        }
        if ("onBreak".equals(functionName) && args[0] instanceof BlockBreakEvent event) {
            states.remove(blockKey(event.getBlock().getLocation()));
            return null;
        }
        return null;
    }

    private void handleTick(Location loc) {
        State state = states.computeIfAbsent(blockKey(loc), ignored -> new State());
        long now = System.currentTimeMillis();
        if (now - state.last < INTERVAL) {
            return;
        }
        state.last = now;
        Player player = findPlayer(loc);
        if (player != null) {
            state.empty = 0;
            process(loc, player, now, state);
        } else if (++state.empty >= MAX_EMPTY) {
            state.timer = null;
            state.empty = 0;
        }
    }

    private void process(Location loc, Player player, long now, State state) {
        Location south = loc.clone().add(0, 0, 1);
        Object menu = MachineScriptHelper.getMenu(south);
        if (menu == null) {
            return;
        }
        if (state.timer == null) {
            SlimefunItem southMachine = MachineScriptHelper.getSfItem(south);
            if (southMachine == null || !southMachineId.equals(southMachine.getId())) {
                player.sendMessage(ChatColor.RED + "南侧机器出错");
                return;
            }
            if (!checkRules(menu) || !canPlaceOutput(menu)) {
                player.sendMessage(ChatColor.RED + "物品放置错误或输出槽位不足");
                return;
            }
            state.timer = new Timer(player.getUniqueId(), now);
            player.sendMessage(ChatColor.AQUA + startMsg);
            return;
        }
        if (!state.timer.playerId.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "玩家已更换，中断！");
            state.timer = null;
            return;
        }
        long elapsed = now - state.timer.start;
        if (!state.timer.half && elapsed >= TIME / 2) {
            player.sendMessage(ChatColor.YELLOW + halfMsg);
            state.timer.half = true;
        } else if (!state.timer.finalMsg && elapsed >= TIME - 3_000L) {
            player.sendMessage(ChatColor.YELLOW + finalMsg);
            state.timer.finalMsg = true;
        } else if (elapsed >= TIME) {
            convert(menu, player);
            state.timer = null;
        }
    }

    private void convert(Object menu, Player player) {
        if (!checkRules(menu)) {
            player.sendMessage(ChatColor.RED + "物品已变更，无法完成");
            return;
        }
        if (convertItems(menu)) {
            player.sendMessage(ChatColor.GOLD + successMsg);
        } else {
            player.sendMessage(ChatColor.RED + "转换失败");
        }
    }

    private boolean convertItems(Object menu) {
        SlimefunItem outputItem = SlimefunItem.getById(outputId);
        if (outputItem == null) {
            return false;
        }
        ItemStack[] contents = MachineScriptHelper.getMenuContents(menu);
        int maxStack = outputItem.getItem().getMaxStackSize();
        int amount = 1 + ThreadLocalRandom.current().nextInt(6);
        List<OutputSlot> available = new ArrayList<>();
        for (int slot : outputSlots) {
            ItemStack item = slot < contents.length ? contents[slot] : null;
            if (item == null || item.getType().isAir()) {
                available.add(new OutputSlot(slot, null, maxStack));
            } else {
                SlimefunItem sf = SlimefunItem.getByItem(item);
                if (sf != null && outputId.equals(sf.getId()) && item.getAmount() < maxStack) {
                    available.add(new OutputSlot(slot, item, maxStack - item.getAmount()));
                }
            }
        }
        int total = available.stream().mapToInt(s -> s.available).sum();
        if (total < amount) {
            return false;
        }
        for (SlotRule rule : rules) {
            for (int slot = rule.start; slot <= rule.end; slot++) {
                KomutechMenuHelper.replaceExistingItem(menu, slot, null);
            }
        }
        Collections.shuffle(available);
        int remaining = amount;
        for (OutputSlot slotInfo : available) {
            if (remaining <= 0) {
                break;
            }
            int give = Math.min(remaining, slotInfo.available);
            if (slotInfo.item != null) {
                slotInfo.item.setAmount(slotInfo.item.getAmount() + give);
                KomutechMenuHelper.replaceExistingItem(menu, slotInfo.slot, slotInfo.item);
            } else {
                ItemStack stack = outputItem.getItem().clone();
                stack.setAmount(give);
                KomutechMenuHelper.replaceExistingItem(menu, slotInfo.slot, stack);
            }
            remaining -= give;
        }
        return true;
    }

    private boolean checkRules(Object menu) {
        ItemStack[] contents = MachineScriptHelper.getMenuContents(menu);
        for (SlotRule rule : rules) {
            for (int i = rule.start; i <= rule.end; i++) {
                int expected = i - rule.start + 1;
                ItemStack item = i < contents.length ? contents[i] : null;
                if (item == null || item.getType().isAir()) {
                    return false;
                }
                SlimefunItem sf = SlimefunItem.getByItem(item);
                if (sf == null || !rule.itemId.equals(sf.getId()) || item.getAmount() != expected) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean canPlaceOutput(Object menu) {
        ItemStack[] contents = MachineScriptHelper.getMenuContents(menu);
        for (int slot : outputSlots) {
            ItemStack item = slot < contents.length ? contents[slot] : null;
            if (item == null || item.getType().isAir()) {
                return true;
            }
            SlimefunItem sf = SlimefunItem.getByItem(item);
            if (sf != null && outputId.equals(sf.getId()) && item.getAmount() < item.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private Player findPlayer(Location loc) {
        if (loc.getWorld() == null) {
            return null;
        }
        Location center = loc.clone().add(0.5, 1.0, 0.5);
        for (Entity entity : EntityQueries.entities(loc.getWorld(), center, 1.0, 2.0, 1.0)) {
            if (entity instanceof Player player && player.isValid() && !player.isDead()) {
                return player;
            }
        }
        return null;
    }

    private static String blockKey(Location loc) {
        World world = loc.getWorld();
        String worldId = world == null ? "null" : world.getUID().toString();
        return worldId + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    public static SlotRuleWudaoScript wuXing() {
        return new SlotRuleWudaoScript(
            "KOMUTECH_L_WD_WXTMS", "KOMUTECH_L_JQ_LXFZCZQ", "KOMUTECH_L_FZ_五行灵曦",
            new int[] {15, 16, 24, 25, 33, 34},
            List.of(
                new SlotRule("KOMUTECH_L_FZ_WZJYSFZ", 0, 4),
                new SlotRule("KOMUTECH_L_FZ_WZMYSFZ", 9, 13),
                new SlotRule("KOMUTECH_L_FZ_WZSYSFZ", 18, 22),
                new SlotRule("KOMUTECH_L_FZ_WZHYSFZ", 27, 31),
                new SlotRule("KOMUTECH_L_FZ_WZTYSFZ", 36, 40)
            ),
            "开始领悟", "五行汇聚", "迷惘渐散", "大道自成", "✓ 已放置五行通明石"
        );
    }

    public static SlotRuleWudaoScript tianXiang() {
        return new SlotRuleWudaoScript(
            "KOMUTECH_L_WD_TXTMS", "KOMUTECH_L_JQ_LXFZCZQ", "KOMUTECH_L_FZ_天象灵曦",
            new int[] {15, 16, 24, 25, 33, 34},
            List.of(
                new SlotRule("KOMUTECH_L_FZ_WZBYSFZ", 0, 4),
                new SlotRule("KOMUTECH_L_FZ_WZFYSFZ", 9, 13),
                new SlotRule("KOMUTECH_L_FZ_WZLYSFZ", 18, 22)
            ),
            "天象初现", "星象汇聚", "天机将显", "天象自成", "✓ 已放置天象通明石"
        );
    }

    public static SlotRuleWudaoScript zaoHua() {
        return new SlotRuleWudaoScript(
            "KOMUTECH_L_WD_ZHTMS", "KOMUTECH_L_JQ_LXFZCZQ", "KOMUTECH_L_FZ_造化灵曦",
            new int[] {15, 16, 24, 25, 33, 34},
            List.of(
                new SlotRule("KOMUTECH_L_FZ_WZJGFZ", 0, 4),
                new SlotRule("KOMUTECH_L_FZ_WZLJFZ", 9, 13),
                new SlotRule("KOMUTECH_L_FZ_WZSYFZ", 18, 22),
                new SlotRule("KOMUTECH_L_FZ_WZKJFZ", 27, 31),
                new SlotRule("KOMUTECH_L_FZ_WZSLFZ", 36, 40)
            ),
            "造化初启", "万物生发", "造化将成", "造化自成", "✓ 已放置造化通明石"
        );
    }

    public record SlotRule(String itemId, int start, int end) {
    }

    private static final class State {
        long last;
        int empty;
        Timer timer;
    }

    private static final class Timer {
        final UUID playerId;
        final long start;
        boolean half;
        boolean finalMsg;

        Timer(UUID playerId, long start) {
            this.playerId = playerId;
            this.start = start;
        }
    }

    private static final class OutputSlot {
        final int slot;
        final ItemStack item;
        final int available;

        OutputSlot(int slot, ItemStack item, int available) {
            this.slot = slot;
            this.item = item;
            this.available = available;
        }
    }
}
