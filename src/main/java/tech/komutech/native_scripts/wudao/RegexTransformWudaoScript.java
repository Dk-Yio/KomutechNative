package tech.komutech.native_scripts.wudao;

import tech.komutech.native_scripts.support.EntityQueries;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

public final class RegexTransformWudaoScript implements NativeScript {

    private static final long TIME = 15_000L;
    private static final long INTERVAL = 3_000L;
    private static final int MAX_EMPTY = 5;

    private final String machineId;
    private final String southMachineId;
    private final int targetSlot;
    private final int startSlot;
    private final int endSlot;
    private final int baseAmount;
    private final Pattern pattern;
    private final String replacement;
    private final String startMsg;
    private final String halfMsg;
    private final String finalMsg;
    private final String successMsg;
    private final String placeMsg;

    private final Map<String, State> states = new HashMap<>();

    public RegexTransformWudaoScript(
        String machineId,
        String southMachineId,
        Pattern pattern,
        String replacement,
        String startMsg,
        String halfMsg,
        String finalMsg,
        String successMsg,
        String placeMsg
    ) {
        this(machineId, southMachineId, 49, 9, 44, 1, pattern, replacement,
            startMsg, halfMsg, finalMsg, successMsg, placeMsg);
    }

    public RegexTransformWudaoScript(
        String machineId,
        String southMachineId,
        int targetSlot,
        int startSlot,
        int endSlot,
        int baseAmount,
        Pattern pattern,
        String replacement,
        String startMsg,
        String halfMsg,
        String finalMsg,
        String successMsg,
        String placeMsg
    ) {
        this.machineId = machineId;
        this.southMachineId = southMachineId;
        this.targetSlot = targetSlot;
        this.startSlot = startSlot;
        this.endSlot = endSlot;
        this.baseAmount = baseAmount;
        this.pattern = pattern;
        this.replacement = replacement;
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
            // 仅中断本轮，不永久停用石头
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
            CheckResult check = checkCondition(menu);
            if (!check.valid) {
                player.sendMessage(ChatColor.RED + check.message);
                return;
            }
            if (!canPlace(menu, check.targetId)) {
                player.sendMessage(ChatColor.RED + "目标槽位不可用");
                return;
            }
            state.timer = new Timer(player.getUniqueId(), now, check.sourceId, check.targetId);
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
            convert(south, player, menu, state.timer);
            state.timer = null;
        }
    }

    private void convert(Location south, Player player, Object menu, Timer timer) {
        CheckResult check = checkCondition(menu);
        if (!check.valid || !check.sourceId.equals(timer.from)) {
            player.sendMessage(ChatColor.RED + "转换条件不满足");
            return;
        }
        SlimefunItem result = SlimefunItem.getById(timer.to);
        if (result == null) {
            return;
        }
        ItemStack[] contents = MachineScriptHelper.getMenuContents(menu);
        ItemStack targetItem = targetSlot < contents.length ? contents[targetSlot] : null;
        if (targetItem != null && !targetItem.getType().isAir()) {
            SlimefunItem sf = SlimefunItem.getByItem(targetItem);
            if (sf == null || !timer.to.equals(sf.getId()) || targetItem.getAmount() >= targetItem.getMaxStackSize()) {
                player.sendMessage(ChatColor.RED + "转换条件不满足");
                return;
            }
        }
        for (int slot = startSlot; slot <= endSlot; slot++) {
            KomutechMenuHelper.replaceExistingItem(menu, slot, null);
        }
        ItemStack stack = result.getItem().clone();
        stack.setAmount(1);
        if (targetItem != null && !targetItem.getType().isAir()) {
            targetItem.setAmount(targetItem.getAmount() + 1);
            KomutechMenuHelper.replaceExistingItem(menu, targetSlot, targetItem);
        } else {
            KomutechMenuHelper.replaceExistingItem(menu, targetSlot, stack);
        }
        player.sendMessage(ChatColor.GOLD + successMsg);
    }

    private CheckResult checkCondition(Object menu) {
        ItemStack[] contents = MachineScriptHelper.getMenuContents(menu);
        if (startSlot >= contents.length) {
            return CheckResult.fail("物品放置错误");
        }
        ItemStack first = contents[startSlot];
        if (first == null || first.getType().isAir()) {
            return CheckResult.fail("物品放置错误: 有空槽位");
        }
        SlimefunItem firstSf = SlimefunItem.getByItem(first);
        if (firstSf == null) {
            return CheckResult.fail("物品放置错误: 物品类型错误");
        }
        String firstId = firstSf.getId();
        Matcher matcher = pattern.matcher(firstId);
        if (!matcher.matches()) {
            return CheckResult.fail("物品放置错误: 物品不符合规则");
        }
        String targetId = matcher.replaceAll(replacement);
        if (first.getAmount() != baseAmount) {
            return CheckResult.fail("物品放置错误: 物品数量错误");
        }
        for (int i = startSlot + 1; i <= endSlot; i++) {
            int expected = i - startSlot + baseAmount;
            ItemStack item = i < contents.length ? contents[i] : null;
            if (item == null || item.getType().isAir()) {
                return CheckResult.fail("物品放置错误: 有空槽位");
            }
            SlimefunItem sf = SlimefunItem.getByItem(item);
            if (sf == null || !firstId.equals(sf.getId()) || item.getAmount() != expected) {
                return CheckResult.fail("物品放置错误: 物品类型或数量不一致");
            }
        }
        return CheckResult.ok(firstId, targetId);
    }

    private boolean canPlace(Object menu, String itemId) {
        ItemStack[] contents = MachineScriptHelper.getMenuContents(menu);
        if (targetSlot >= contents.length) {
            return false;
        }
        ItemStack item = contents[targetSlot];
        if (item == null || item.getType().isAir()) {
            return true;
        }
        SlimefunItem sf = SlimefunItem.getByItem(item);
        return sf != null && itemId.equals(sf.getId()) && item.getAmount() < item.getMaxStackSize();
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

    public static RegexTransformWudaoScript mingWu() {
        return new RegexTransformWudaoScript(
            "KOMUTECH_L_WD_MWS", "KOMUTECH_L_JQ_FZCZQ",
            Pattern.compile("^(KOMUTECH_L_FZ_)(YS)(.*)$"), "$1XX$3",
            "渐渐明悟", "心有所感", "渐入佳境", "明悟通达", "✓ 已放置明悟石"
        );
    }

    public static RegexTransformWudaoScript poWang() {
        return new RegexTransformWudaoScript(
            "KOMUTECH_L_WD_PWS", "KOMUTECH_L_JQ_FZCZQ",
            Pattern.compile("^(KOMUTECH_L_FZ_)(XX)(.*)$"), "$1WZ$3",
            "破妄开始", "渐识真我", "即将破妄", "破妄成功", "✓ 已放置破妄石"
        );
    }

    public static RegexTransformWudaoScript yuanQi() {
        return new RegexTransformWudaoScript(
            "KOMUTECH_L_WD_YQS", "KOMUTECH_L_JQ_ZQFZCZQ",
            Pattern.compile("^(KOMUTECH_L_FZ_)(五行|天象|造化)灵曦$"), "$1$2祖炁",
            "元炁汇聚", "炁流涌动", "即将成炁", "祖炁已成", "✓ 已放置元炁石"
        );
    }

    private static final class State {
        long last;
        int empty;
        Timer timer;
    }

    private static final class Timer {
        final UUID playerId;
        final long start;
        final String from;
        final String to;
        boolean half;
        boolean finalMsg;

        Timer(UUID playerId, long start, String from, String to) {
            this.playerId = playerId;
            this.start = start;
            this.from = from;
            this.to = to;
        }
    }

    private record CheckResult(boolean valid, String message, String sourceId, String targetId) {
        static CheckResult fail(String message) {
            return new CheckResult(false, message, null, null);
        }

        static CheckResult ok(String sourceId, String targetId) {
            return new CheckResult(true, null, sourceId, targetId);
        }
    }
}
