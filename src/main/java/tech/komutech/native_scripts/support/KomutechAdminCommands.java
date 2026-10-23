package tech.komutech.native_scripts.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tech.origintech.NativeAddonConfig;
import tech.origintech.commands.NativeAddonCommand;

/**
 * OP 指令：/komutech set|add|take &lt;玩家&gt; 灵气|属性点 &lt;数量&gt;
 * 仅注册到 KomutechNative，不写入其他附属。
 */
public final class KomutechAdminCommands {

    private static final Pattern SPIRIT_CURRENT = Pattern.compile("^(\\d+(?:\\.\\d+)?)");

    private enum Mode {
        SET, ADD, TAKE
    }

    private KomutechAdminCommands() {
    }

    public static void register() {
        NativeAddonCommand.setExtension(KomutechAdminCommands::handle);
        NativeAddonCommand.setTabExtension(KomutechAdminCommands::tabComplete);
    }

    private static boolean handle(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return false;
        }
        String action0 = args[0].toLowerCase(Locale.ROOT);
        if ("putuan".equals(action0) || "蒲团".equals(args[0]) || "蒲团清理".equals(args[0])) {
            return handlePutuan(sender, args);
        }
        Mode mode = parseMode(args[0]);
        if (mode == null) {
            return false;
        }
        if (!sender.hasPermission(NativeAddonConfig.COMMAND_PERMISSION)) {
            sender.sendMessage("§c你没有权限执行这个命令。");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length < 4) {
            sender.sendMessage("§c用法: /komutech " + action + " <玩家名> <灵气|属性点> <数量>");
            return true;
        }
        String playerName = args[1];
        String field = args[2];
        String amountRaw = args[3];

        Map<String, Object> data = PlayerAttributeStore.load(playerName);
        if (data == null) {
            sender.sendMessage("§c找不到玩家属性数据: §f" + playerName);
            return true;
        }
        CultivationMath.ensureDataComplete(playerName, data);

        if ("灵气".equals(field)) {
            return adjustSpirit(sender, playerName, data, amountRaw, mode);
        }
        if ("属性点".equals(field)) {
            return adjustAttributePoints(sender, playerName, data, amountRaw, mode);
        }
        sender.sendMessage("§c未知字段: §f" + field + " §7(仅支持 灵气 / 属性点)");
        return true;
    }

    /**
     * /komutech putuan cleanup|all — 全图已加载区块清理残留修炼显示
     * /komutech putuan here — 清理准星方块或脚下蒲团
     * /komutech putuan near [半径] — 清理附近残留（默认 16）
     */
    private static boolean handlePutuan(CommandSender sender, String[] args) {
        if (!sender.hasPermission(NativeAddonConfig.COMMAND_PERMISSION)) {
            sender.sendMessage("§c你没有权限执行这个命令。");
            return true;
        }
        var script = tech.komutech.native_scripts.NativeScriptRegistry.puTuan();
        if (script == null) {
            sender.sendMessage("§c蒲团脚本尚未初始化。");
            return true;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "cleanup";
        if ("蒲团清理".equals(args[0])) {
            sub = "cleanup";
        }
        switch (sub) {
            case "cleanup", "all", "sweep", "清理" -> {
                int n = script.adminSweepAll();
                sender.sendMessage("§a[蒲团] 已扫描清理残留修炼显示，移除 §f" + n + " §a个实体。");
                return true;
            }
            case "here", "这里" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c仅玩家可使用 here。");
                    return true;
                }
                org.bukkit.block.Block target = player.getTargetBlockExact(6);
                org.bukkit.Location loc = target != null
                    ? target.getLocation()
                    : player.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN).getLocation();
                script.adminCleanupAt(loc);
                sender.sendMessage("§a[蒲团] 已强制清理坐标 §f"
                    + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
                return true;
            }
            case "near", "附近" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c仅玩家可使用 near。");
                    return true;
                }
                double radius = 16;
                if (args.length >= 3) {
                    try {
                        radius = Double.parseDouble(args[2]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                int n = script.adminCleanupNearby(player.getLocation(), Math.max(1, Math.min(radius, 64)));
                sender.sendMessage("§a[蒲团] 附近清理完成，移除 §f" + n + " §a个残留实体。");
                return true;
            }
            default -> {
                sender.sendMessage("§c用法: /komutech putuan <cleanup|here|near [半径]>");
                return true;
            }
        }
    }

    private static Mode parseMode(String raw) {
        String action = raw.toLowerCase(Locale.ROOT);
        return switch (action) {
            case "set" -> Mode.SET;
            case "add" -> Mode.ADD;
            case "take" -> Mode.TAKE;
            default -> null;
        };
    }

    private static String modeVerb(Mode mode) {
        return switch (mode) {
            case SET -> "设置";
            case ADD -> "增加";
            case TAKE -> "扣除";
        };
    }

    private static double applyAmount(double current, double amount, Mode mode) {
        return switch (mode) {
            case SET -> amount;
            case ADD -> current + amount;
            case TAKE -> current - Math.abs(amount);
        };
    }

    private static int applyAmount(int current, int amount, Mode mode) {
        return switch (mode) {
            case SET -> amount;
            case ADD -> current + amount;
            case TAKE -> current - Math.abs(amount);
        };
    }

    private static boolean adjustSpirit(CommandSender sender, String playerName, Map<String, Object> data,
                                        String amountRaw, Mode mode) {
        double amount;
        try {
            amount = Double.parseDouble(amountRaw);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c数量必须是数字");
            return true;
        }
        if (!Double.isFinite(amount)) {
            sender.sendMessage("§c数量无效");
            return true;
        }

        double current = parseSpiritCurrent(PlayerAttributeStore.getString(data, "灵气", "0"));
        double next = applyAmount(current, amount, mode);
        if (next < 0) {
            next = 0;
        }
        long spiritLong = (long) Math.floor(next);
        CultivationMath.CultivationInfo info = CultivationMath.getCultivationInfo(spiritLong);
        String spiritText = formatSpirit(next, info.maxStr());
        data.put("灵气", spiritText);
        data.put("修为", info.stage());
        syncLingliBase(data);
        data.put("灵气获取_实际", AttributePointLimits.spiritGainPercent(
            data, PlayerAttributeStore.getInt(data, "灵气获取", 0)));

        if (!PlayerAttributeStore.save(playerName, data)) {
            sender.sendMessage("§c保存失败: §f" + playerName);
            return true;
        }
        applyIfOnline(playerName, data);
        sender.sendMessage("§a已" + modeVerb(mode) + " §f" + playerName
            + " §a的灵气为 §e" + spiritText + " §7(" + info.stage() + ")");
        notifyTarget(playerName, "§a管理员已调整你的灵气: §e" + spiritText);
        return true;
    }

    private static boolean adjustAttributePoints(CommandSender sender, String playerName, Map<String, Object> data,
                                                 String amountRaw, Mode mode) {
        int amount;
        try {
            amount = Integer.parseInt(amountRaw);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c属性点数量必须是整数");
            return true;
        }
        int current = PlayerAttributeStore.getInt(data, "属性点", 0);
        int next = applyAmount(current, amount, mode);
        if (next < 0) {
            next = 0;
        }
        data.put("属性点", next);
        if (!PlayerAttributeStore.save(playerName, data)) {
            sender.sendMessage("§c保存失败: §f" + playerName);
            return true;
        }
        sender.sendMessage("§a已" + modeVerb(mode) + " §f" + playerName
            + " §a的属性点为 §e" + next);
        notifyTarget(playerName, "§a管理员已调整你的属性点: §e" + next);
        return true;
    }

    private static void syncLingliBase(Map<String, Object> data) {
        PlayerAttributeStore.Lingli ling = PlayerAttributeStore.parseLingli(data.get("灵力"));
        int newBase = CultivationMath.computeBaseMaxLingli(data);
        data.put("灵力", PlayerAttributeStore.formatLingli(
            new PlayerAttributeStore.Lingli(ling.current(), newBase, ling.bonus())));
    }

    private static double parseSpiritCurrent(String spirit) {
        if (spirit == null || spirit.isBlank()) {
            return 0;
        }
        Matcher m = SPIRIT_CURRENT.matcher(spirit.trim());
        if (!m.find()) {
            return 0;
        }
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String formatSpirit(double current, String maxStr) {
        if (Math.abs(current - Math.rint(current)) < 1e-9) {
            return ((long) Math.rint(current)) + "/" + maxStr;
        }
        return String.format(Locale.ROOT, "%.2f/%s", current, maxStr);
    }

    private static void applyIfOnline(String playerName, Map<String, Object> data) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null && online.isOnline()) {
            AttributeApplier.applyPlayerAttributes(online, data);
            PlayerAttributeStore.save(playerName, data);
        }
    }

    private static void notifyTarget(String playerName, String message) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null && online.isOnline()) {
            online.sendMessage(message);
        }
    }

    private static List<String> tabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(NativeAddonConfig.COMMAND_PERMISSION)) {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return filter(List.of("reload", "set", "add", "take", "putuan", "蒲团"), prefix);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if ("putuan".equals(action) || "蒲团".equals(args[0])) {
            if (args.length == 2) {
                return filter(List.of("cleanup", "here", "near"), prefix);
            }
            return List.of();
        }
        if (!"set".equals(action) && !"add".equals(action) && !"take".equals(action)) {
            return List.of();
        }
        if (args.length == 2) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return filter(names, prefix);
        }
        if (args.length == 3) {
            return filter(List.of("灵气", "属性点"), prefix);
        }
        if (args.length == 4) {
            return filter(List.of("0", "1", "10", "100", "1000"), prefix);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix) || option.startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
