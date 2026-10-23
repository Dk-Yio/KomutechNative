package tech.komutech.native_scripts.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.ChatInputService;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;

public final class SpiritItemAdjusterScript implements NativeScript {

    private static final String[] EDIT_MODES = {"功德值", "品阶", "灵力最大值", "熟练度"};
    private static final String[] RANK_KEYWORDS = {"黄阶", "玄阶", "地阶", "天阶", "仙阶"};
    private static final long DOUBLE_CLICK_THRESHOLD = 500L;
    private static final Map<UUID, Long> LAST_CLICK = new ConcurrentHashMap<>();
    private static final Map<UUID, PlayerSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Pattern MERIT = Pattern.compile("§[bc]功德?值：§6(\\d+)");
    private static final Pattern DEMERIT = Pattern.compile("§c缺德值：§6(\\d+)");
    private static final Pattern SPIRIT = Pattern.compile("§b灵力剩余：§6\\d+ §7/ §6(\\d+)");
    private static final Pattern PROF = Pattern.compile("§b熟练度：§6(\\d+) §7/ §6(\\d+)");
    private static final Map<String, RankConfig> RANKS = Map.of(
        "黄阶", new RankConfig("§e§l黄阶", 500),
        "玄阶", new RankConfig("§8§l玄阶", 1500),
        "地阶", new RankConfig("§d§l地阶", 3000),
        "天阶", new RankConfig("§4§l天阶", 10000),
        "仙阶", new RankConfig("§d§l仙阶", 20000)
    );

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onUse".equals(functionName)) {
            return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
        }
        if ("onCommand".equals(functionName) && args.length >= 3) {
            Object sender = args[0];
            Object command = args[1];
            if (sender instanceof CommandSender commandSender
                && command instanceof Command cmd
                && "itemeditor".equalsIgnoreCase(cmd.getName())) {
                if (commandSender instanceof Player player) {
                    showHelp(player);
                } else {
                    commandSender.sendMessage("§6===== 物品属性调整器 =====");
                    commandSender.sendMessage("§e请在游戏中使用此命令。");
                }
                return true;
            }
        }
        return null;
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = LAST_CLICK.getOrDefault(id, 0L);
        if (now - last <= DOUBLE_CLICK_THRESHOLD) {
            resetPlayerState(player);
            LAST_CLICK.put(id, now);
            return null;
        }
        LAST_CLICK.put(id, now);
        PlayerSession session = SESSIONS.get(id);
        if (session != null && session.awaitingInput) {
            KomutechSupport.send(player, "§c⚠️ 请先完成当前输入或输入 'cancel' 取消");
            return null;
        }
        if (player.isSneaking()) {
            if (session == null || session.editMode == null) {
                handleFirstInput(player);
            } else if (session.targetValue == null) {
                handleSecondInput(player, session.editMode);
            } else {
                KomutechSupport.send(player, "§a检测到已设置目标值，自动进入第一次选择");
                SESSIONS.remove(id);
                handleFirstInput(player);
            }
        } else {
            applyToOffhand(player);
        }
        return null;
    }

    private void handleFirstInput(Player player) {
        KomutechSupport.send(player, "§6请选择要修改的属性:");
        KomutechSupport.send(player, "§e功德值 §7| §e品阶 §7| §e灵力最大值 §7| §e熟练度");
        KomutechSupport.send(player, "§a输入属性名称，或输入 'help' 查看帮助");
        setAwaiting(player, true);
        ChatInputService.request(player, "§e>", input -> {
            setAwaiting(player, false);
            input = input.trim();
            if ("cancel".equalsIgnoreCase(input)) {
                KomutechSupport.send(player, "§a✅ 已取消操作");
                return;
            }
            if ("help".equalsIgnoreCase(input)) {
                showHelp(player);
                return;
            }
            String foundMode = null;
            for (String mode : EDIT_MODES) {
                if (input.contains(mode)) {
                    foundMode = mode;
                    break;
                }
            }
            if (foundMode == null) {
                KomutechSupport.send(player, "§c❌ 不支持的属性！请从以下选择:");
                KomutechSupport.send(player, "§e功德值 §7| §e品阶 §7| §e灵力最大值 §7| §e熟练度");
                return;
            }
            PlayerSession session = new PlayerSession(foundMode, null, false);
            SESSIONS.put(player.getUniqueId(), session);
            KomutechSupport.send(player, "§a✅ 已选择: §6" + foundMode);
            if ("品阶".equals(foundMode)) {
                KomutechSupport.send(player, "§a请再次蹲下右键，输入目标品阶 (黄阶/玄阶/地阶/天阶/仙阶)");
            } else {
                KomutechSupport.send(player, "§a请再次蹲下右键，输入目标" + foundMode + "数值");
            }
        });
    }

    private void handleSecondInput(Player player, String mode) {
        if ("品阶".equals(mode)) {
            KomutechSupport.send(player, "§6请输入目标品阶: 黄阶/玄阶/地阶/天阶/仙阶");
        } else {
            KomutechSupport.send(player, "§6请输入目标" + mode + ":");
        }
        setAwaiting(player, true);
        ChatInputService.request(player, "§e>", input -> {
            setAwaiting(player, false);
            input = input.trim();
            if ("cancel".equalsIgnoreCase(input)) {
                KomutechSupport.send(player, "§a✅ 已取消操作");
                SESSIONS.remove(player.getUniqueId());
                return;
            }
            PlayerSession session = SESSIONS.get(player.getUniqueId());
            if (session == null) {
                return;
            }
            Object targetValue;
            if ("品阶".equals(mode)) {
                String foundRank = null;
                for (String rank : RANK_KEYWORDS) {
                    if (input.contains(rank)) {
                        foundRank = rank;
                        break;
                    }
                }
                if (foundRank == null) {
                    KomutechSupport.send(player, "§c❌ 无效的品阶！请输入: 黄阶/玄阶/地阶/天阶/仙阶");
                    return;
                }
                targetValue = foundRank;
            } else {
                try {
                    int num = Integer.parseInt(input);
                    if (!"功德值".equals(mode) && num < 0) {
                        KomutechSupport.send(player, "§c❌ 请输入非负整数！");
                        return;
                    }
                    targetValue = num;
                } catch (NumberFormatException ex) {
                    KomutechSupport.send(player, "§c❌ 请输入有效的数字！");
                    return;
                }
            }
            session.targetValue = targetValue;
            KomutechSupport.send(player, "§a✅ 已设置: §6" + mode + " = " + targetValue);
            KomutechSupport.send(player, "§a请使用普通右键应用到副手物品");
        });
    }

    private void applyToOffhand(Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand == null || offhand.getType().isAir()) {
            KomutechSupport.send(player, "§c❌ 副手未装备物品！请将目标物品放在副手");
            return;
        }
        PlayerSession session = SESSIONS.get(player.getUniqueId());
        if (session == null || session.editMode == null || session.targetValue == null) {
            KomutechSupport.send(player, "§c❌ 未设置修改内容！请先蹲下右键设置");
            return;
        }
        String mode = session.editMode;
        switch (mode) {
            case "功德值" -> {
                int target = (Integer) session.targetValue;
                int old = parseMeritValue(offhand);
                if (setMeritValue(offhand, target)) {
                    KomutechSupport.send(player, "§a✅ 功德值调整成功！\n§6原值: "
                        + formatMerit(old) + " §6→ §e" + formatMerit(target));
                } else {
                    KomutechSupport.send(player, "§c❌ 功德值调整失败！物品数据异常");
                }
            }
            case "品阶" -> {
                String target = (String) session.targetValue;
                String old = parseItemRank(offhand);
                if (old == null) {
                    cleanup(player, "§c❌ 物品无品阶属性！");
                    return;
                }
                if (setItemRank(offhand, target)) {
                    KomutechSupport.send(player, "§a✅ 品阶调整成功！\n§6原品阶: " + old + " §6→ §e" + target);
                } else {
                    KomutechSupport.send(player, "§c❌ 品阶调整失败！物品数据异常");
                }
            }
            case "灵力最大值" -> {
                int target = (Integer) session.targetValue;
                int old = parseSpiritMaxValue(offhand);
                if (old <= 0) {
                    cleanup(player, "§c❌ 物品无灵力最大值属性！");
                    return;
                }
                if (setSpiritMaxValue(offhand, target)) {
                    KomutechSupport.send(player, "§a✅ 灵力最大值调整成功！\n§6原值: " + old + " §6→ §e" + target);
                } else {
                    KomutechSupport.send(player, "§c❌ 灵力最大值调整失败！物品数据异常");
                }
            }
            case "熟练度" -> {
                int target = (Integer) session.targetValue;
                ProficiencyInfo info = parseProficiencyInfo(offhand);
                if (info.max <= 0) {
                    cleanup(player, "§c❌ 物品无熟练度属性！");
                    return;
                }
                if (target > info.max) {
                    cleanup(player, "§c❌ 熟练度(" + target + ")不能超过最大值(" + info.max + ")！");
                    return;
                }
                if (setProficiency(offhand, target, info.max)) {
                    KomutechSupport.send(player, "§a✅ 熟练度调整成功！\n§6原值: "
                        + info.current + "/" + info.max + " §6→ §e" + target + "/" + info.max);
                } else {
                    KomutechSupport.send(player, "§c❌ 熟练度调整失败！物品数据异常");
                }
            }
            default -> {
            }
        }
        cleanup(player, null);
    }

    private static void resetPlayerState(Player player) {
        SESSIONS.remove(player.getUniqueId());
        KomutechSupport.send(player, "§a已重置状态，可以重新选择属性");
    }

    private static void cleanup(Player player, String message) {
        SESSIONS.remove(player.getUniqueId());
        if (message != null) {
            KomutechSupport.send(player, message);
        }
    }

    private static void setAwaiting(Player player, boolean awaiting) {
        SESSIONS.compute(player.getUniqueId(), (id, session) -> {
            if (session == null) {
                return new PlayerSession(null, null, awaiting);
            }
            session.awaitingInput = awaiting;
            return session;
        });
    }

    private static void showHelp(Player player) {
        KomutechSupport.send(player, "§6===== 物品属性调整器 =====");
        KomutechSupport.send(player, "§e支持的功能:");
        KomutechSupport.send(player, "§a功德值 §7- 调整功德/缺德值 (正数=功德，负数=缺德)");
        KomutechSupport.send(player, "§a品阶 §7- 调整物品品阶 (黄阶/玄阶/地阶/天阶/仙阶)");
        KomutechSupport.send(player, "§a灵力最大值 §7- 调整灵力最大值");
        KomutechSupport.send(player, "§a熟练度 §7- 调整熟练度当前值");
        KomutechSupport.send(player, "§6使用方法:");
        KomutechSupport.send(player, "§e1. §7首次蹲下右键选择要修改的属性");
        KomutechSupport.send(player, "§e2. §7再次蹲下右键输入数值/品阶");
        KomutechSupport.send(player, "§e3. §7普通右键应用到副手物品");
        KomutechSupport.send(player, "§c输入 'cancel' 可取消当前操作");
        KomutechSupport.send(player, "§c快速双击右键可强制重置状态");
    }

    private static int parseMeritValue(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return 0;
        }
        for (String line : meta.getLore()) {
            Matcher merit = MERIT.matcher(line);
            if (merit.find()) {
                return Integer.parseInt(merit.group(1));
            }
            Matcher demerit = DEMERIT.matcher(line);
            if (demerit.find()) {
                return -Integer.parseInt(demerit.group(1));
            }
        }
        return 0;
    }

    private static boolean setMeritValue(ItemStack item, int value) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        String line = value >= 0 ? "§b功德值：§6" + Math.abs(value) : "§c缺德值：§6" + Math.abs(value);
        return replaceOrAdd(lore, line, MERIT, DEMERIT) && save(item, meta, lore);
    }

    private static String parseItemRank(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return null;
        }
        for (String line : meta.getLore()) {
            for (String keyword : RANK_KEYWORDS) {
                if (line.contains(keyword)) {
                    return keyword;
                }
            }
        }
        return null;
    }

    private static boolean setItemRank(ItemStack item, String targetRank) {
        RankConfig config = RANKS.get(targetRank);
        if (config == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return false;
        }
        List<String> lore = new ArrayList<>(meta.getLore());
        boolean rankUpdated = false;
        for (int i = 0; i < lore.size(); i++) {
            String line = lore.get(i);
            String newLine = line;
            if (!rankUpdated) {
                for (RankConfig old : RANKS.values()) {
                    if (line.contains(old.display)) {
                        newLine = line.replace(old.display, config.display);
                        rankUpdated = true;
                        break;
                    }
                }
            }
            if (line.contains("§7/ §6")) {
                newLine = newLine.replaceAll("(§7/ §6)\\d+", "$1" + config.profMax);
            }
            if (!newLine.equals(line)) {
                lore.set(i, newLine);
            }
        }
        return rankUpdated && save(item, meta, lore);
    }

    private static int parseSpiritMaxValue(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return 0;
        }
        for (String line : meta.getLore()) {
            Matcher matcher = SPIRIT.matcher(line);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return 0;
    }

    private static boolean setSpiritMaxValue(ItemStack item, int maxValue) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return false;
        }
        List<String> lore = new ArrayList<>(meta.getLore());
        boolean changed = false;
        for (int i = 0; i < lore.size(); i++) {
            if (SPIRIT.matcher(lore.get(i)).find()) {
                lore.set(i, lore.get(i).replaceAll("(§7/ §6)\\d+", "$1" + maxValue));
                changed = true;
            }
        }
        return changed && save(item, meta, lore);
    }

    private static ProficiencyInfo parseProficiencyInfo(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return new ProficiencyInfo(0, 0);
        }
        for (String line : meta.getLore()) {
            Matcher matcher = PROF.matcher(line);
            if (matcher.find()) {
                return new ProficiencyInfo(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))
                );
            }
        }
        return new ProficiencyInfo(0, 0);
    }

    private static boolean setProficiency(ItemStack item, int newProf, int maxVal) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return false;
        }
        List<String> lore = new ArrayList<>(meta.getLore());
        for (int i = 0; i < lore.size(); i++) {
            if (PROF.matcher(lore.get(i)).find()) {
                lore.set(i, "§b熟练度：§6" + newProf + " §7/ §6" + maxVal);
                return save(item, meta, lore);
            }
        }
        return false;
    }

    private static boolean replaceOrAdd(List<String> lore, String line, Pattern... patterns) {
        for (int i = 0; i < lore.size(); i++) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(lore.get(i)).find()) {
                    lore.set(i, line);
                    return true;
                }
            }
        }
        lore.add(line);
        return true;
    }

    private static boolean save(ItemStack item, ItemMeta meta, List<String> lore) {
        meta.setLore(lore);
        item.setItemMeta(meta);
        return true;
    }

    private static String formatMerit(int value) {
        return value >= 0 ? String.valueOf(value) : "缺德" + Math.abs(value);
    }

    private static final class PlayerSession {
        String editMode;
        Object targetValue;
        boolean awaitingInput;

        PlayerSession(String editMode, Object targetValue, boolean awaitingInput) {
            this.editMode = editMode;
            this.targetValue = targetValue;
            this.awaitingInput = awaitingInput;
        }
    }

    private record RankConfig(String display, int profMax) {
    }

    private record ProficiencyInfo(int current, int max) {
    }
}
