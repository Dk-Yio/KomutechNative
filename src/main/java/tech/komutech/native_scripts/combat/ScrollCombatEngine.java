package tech.komutech.native_scripts.combat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import tech.komutech.native_scripts.support.KomutechConfigMerge;
import tech.komutech.native_scripts.support.KomutechJson;
import tech.komutech.native_scripts.support.KomutechPaths;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.ParticleBudget;
import tech.komutech.native_scripts.support.PlayerAttributeStore;

public final class ScrollCombatEngine {

    private static java.nio.file.Path scrollConfigPath() {
        return KomutechPaths.scrollConfig();
    }

    private static java.nio.file.Path staffConfigPath() {
        return KomutechPaths.staffConfig();
    }

    private static java.nio.file.Path scrollDataDir() {
        return KomutechPaths.yunZhuanXia();
    }

    private static Map<String, Object> scrollConfig;
    private static Map<String, Object> staffConfig;
    private static long scrollConfigMtime = Long.MIN_VALUE;
    private static long staffConfigMtime = Long.MIN_VALUE;
    /** 冷却就绪时间戳（epoch ms），比 Bukkit tick 更抗重载/异常 */
    private static final Map<String, Long> scrollReadyAtMs = new HashMap<>();
    private static final Map<UUID, Long> staffReadyAtMs = new HashMap<>();
    private static final long DOUBLE_CLICK_WINDOW_MS = 400;
    private static final long ATTACK_DELAY_TICKS = 8L;
    private static final int DEFAULT_MIN_CD_TICKS = 20;
    private static final int DEFAULT_POST_CAST_GAP_TICKS = 20;
    private static final Map<UUID, Long> firstRightClick = new HashMap<>();
    private static final Map<UUID, Integer> pendingAttackTasks = new HashMap<>();
    private static final String SCROLL_CFG_RESOURCE = "addon_configs/Komutech/卷轴属性.json";
    private static final String STAFF_CFG_RESOURCE = "addon_configs/Komutech/灵杖属性.json";

    private ScrollCombatEngine() {
    }

    public static void castScroll(Player player, ItemStack staff, Map<String, Object> attr, String skillId) {
        String resolvedSkill = normalizeSkillId(skillId);
        ensureScrollConfig();
        Map<String, Object> common = KomutechJson.asMap(scrollConfig.get("公共规则"));
        Map<String, Object> self = KomutechJson.asMap(scrollConfig.get(resolvedSkill));
        if (self.isEmpty()) {
            reloadScrollConfig();
            common = KomutechJson.asMap(scrollConfig.get("公共规则"));
            self = KomutechJson.asMap(scrollConfig.get(resolvedSkill));
        }
        if (self.isEmpty()) {
            KomutechSupport.send(player, "§c卷轴配置缺失: " + resolvedSkill);
            return;
        }
        double baseDamage = KomutechJson.getDouble(self, "基础伤害", 1);
        double maxDist = KomutechJson.getDouble(self, "射程", 16);
        int energyCost = KomutechJson.getInt(self, "基础灵力消耗", 1);
        int baseCooldown = KomutechJson.getInt(self, "基础冷却", 20);
        int maxProf = KomutechJson.getInt(self, "熟练度上限", 1000);
        Map<String, Object> formula = KomutechJson.asMap(common.get("公式参数"));
        double realm = realmCoef(attr, KomutechJson.asMap(common.get("修为倍率")));
        double linggenCo = KomutechJson.getDouble(KomutechJson.asMap(common.get("灵根属性倍率")),
            PlayerAttributeStore.getString(attr, "灵根属性", "单灵根"), 1);
        double attack = PlayerAttributeStore.getDouble(attr, "攻击力_实际", 1);
        double damageScale = getDamageScale(staff, KomutechJson.asMap(common.get("品阶伤害倍率")));
        int prof = getProficiency(player.getName(), resolvedSkill);
        double profDmg = 1 + Math.floor(prof / 100.0) * KomutechJson.getDouble(formula, "熟练度增伤因子", 0.05);
        // 功德不再参与卷轴增伤，仅用于突破雷劫减伤并消耗
        double finalDamage = attack * damageScale * realm * linggenCo * baseDamage * profDmg;
        if (finalDamage <= 0) {
            finalDamage = 5;
        }
        double gengu = PlayerAttributeStore.getDouble(attr, "根骨", 1);
        double profCost = 1 + Math.floor(prof / 100.0) * KomutechJson.getDouble(formula, "熟练度增耗因子", 0.02);
        int finalCost = Math.max(1, (int) Math.round(energyCost * realm * linggenCo * gengu * profCost));
        double wuxing = PlayerAttributeStore.getDouble(attr, "悟性", 1);
        double wxRed = Math.min(KomutechJson.getDouble(formula, "悟性最大减免比例", 0.5),
            (wuxing - 1) * KomutechJson.getDouble(formula, "冷却减免因子_悟性", 0.15));
        double profRed = Math.min(KomutechJson.getDouble(formula, "熟练度最大减免比例", 0.4),
            Math.floor(prof / 100.0) * KomutechJson.getDouble(formula, "冷却减免因子_熟练度", 0.015));
        int minCd = KomutechJson.getInt(formula, "最小冷却", DEFAULT_MIN_CD_TICKS);
        int postGap = KomutechJson.getInt(formula, "释放后额外冷却", DEFAULT_POST_CAST_GAP_TICKS);
        int formulaCd = Math.max(minCd,
            (int) Math.floor(baseCooldown * (1 - Math.min(0.70, wxRed + profRed))));
        int effectTicks = continuousCastLockTicks(resolvedSkill);
        // 特效结束后至少再冷却 postGap（默认 1s）
        int finalCd = Math.max(formulaCd, effectTicks + postGap);
        String cdKey = player.getUniqueId() + "_" + resolvedSkill;
        long nowMs = System.currentTimeMillis();
        long readyAt = scrollReadyAtMs.getOrDefault(cdKey, 0L);
        if (nowMs < readyAt) {
            double remain = (readyAt - nowMs) / 1000.0;
            KomutechSupport.actionBar(player, "§c✖ 冷却中！剩余 §6" + String.format("%.1f", remain) + " §c秒");
            return;
        }
        PlayerAttributeStore.Lingli ling = PlayerAttributeStore.parseLingli(attr.get("灵力"));
        if (ling.current() < finalCost) {
            KomutechSupport.actionBar(player, "§c灵力不足！当前 §6" + String.format("%.2f", ling.current()) + "§c / 需要 §6" + finalCost);
            return;
        }
        attr.put("灵力", PlayerAttributeStore.formatLingli(
            new PlayerAttributeStore.Lingli(ling.current() - finalCost, ling.max(), ling.bonus())));
        scrollReadyAtMs.put(cdKey, nowMs + finalCd * 50L);
        KomutechSupport.actionBar(player, "§a消耗 " + finalCost + " 灵力");
        int gained = addProficiency(player.getName(), resolvedSkill, ThreadLocalRandom.current().nextInt(1, 11), maxProf);
        if (gained > 0) {
            KomutechSupport.send(player, "§b熟练度 +" + gained + " §e" + resolvedSkill);
        }
        player.getWorld().playSound(player.getLocation(), "entity.evoker.cast_spell", 1f, 1f);
        ScrollSpecialEffects.execute(resolvedSkill, player, attr, finalDamage, maxDist,
            KomutechJson.asList(common.get("白名单生物")),
            KomutechJson.asList(common.get("功德变化范围")));
    }

    private static int continuousCastLockTicks(String skillId) {
        return switch (skillId) {
            case "五行必杀" -> 80;
            case "御龙护身决" -> 250;
            case "寒霜锁" -> 35;
            case "星陨劫" -> 45;
            case "游龙惊鸿诀" -> 40;
            case "碎玉闪" -> 8;
            default -> 0;
        };
    }

    /** 统一成配置键：去前缀、兼容大小写 */
    public static String normalizeSkillId(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return "";
        }
        String trimmed = skillId.trim();
        String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        String prefix = "KOMUTECH_L_JZ_";
        if (upper.startsWith(prefix) && trimmed.length() >= prefix.length()) {
            return trimmed.substring(prefix.length());
        }
        return trimmed;
    }

    static int getIntAttr(Map<String, Object> attr, String key, int fallback) {
        return PlayerAttributeStore.getInt(attr, key, fallback);
    }

    public static void staffAttack(Player player, ItemStack staff, Map<String, Object> attr, String staffKey) {
        if (!tryStaffAttack(player, staff, attr, staffKey)) {
            return;
        }
        switch (staffKey) {
            case "蜉蝣梦" -> StaffSpecialAttacks.fuyouMeng(player, staff, attr);
            case "空引津" -> StaffSpecialAttacks.kongYinJin(player, staff, attr);
            case "归墟" -> StaffSpecialAttacks.guiXu(player, staff, attr);
            case "烧火棍" -> staffRayAttack(player, attr, staff, staffKey,
                Color.fromRGB(255, 255, 255), Color.fromRGB(255, 255, 255), 1.0f, 0.2);
            default -> staffRayAttack(player, attr, staff, staffKey,
                Color.fromRGB(0, 255, 255), Color.fromRGB(0, 255, 77), 1.0f, 0.1);
        }
    }

    public static void staffRayAttack(Player player, Map<String, Object> attr, ItemStack staff, String staffKey,
                                      Color startColor, Color endColor, float size) {
        staffRayAttack(player, attr, staff, staffKey, startColor, endColor, size, 0.25);
    }

    public static void staffRayAttack(Player player, Map<String, Object> attr, ItemStack staff, String staffKey,
                                      Color startColor, Color endColor, float size, double step) {
        ensureStaffConfig();
        double maxDist = staffMaxDistance(staffKey);
        double damage = calcStaffDamage(player, staff, attr);
        rayDamage(player, attr, staff, damage, maxDist,
            KomutechJson.asList(staffConfig.get("白名单生物")),
            KomutechJson.asList(staffConfig.get("功德变化范围")),
            startColor, endColor, size, step);
    }

    public static boolean tryStaffAttack(Player player, ItemStack staff, Map<String, Object> attr, String staffKey) {
        ensureStaffConfig();
        Map<String, Object> staffSection = KomutechJson.asMap(staffConfig.get("灵杖"));
        Map<String, Object> custom = KomutechJson.asMap(staffConfig.get(staffKey));
        if (custom.isEmpty() && !"灵杖".equals(staffKey) && !"烧火棍".equals(staffKey)) {
            // 磁盘缺条目时强制与 jar 合并一次
            reloadStaffConfig();
            custom = KomutechJson.asMap(staffConfig.get(staffKey));
        }
        int baseCd = KomutechJson.getInt(custom.isEmpty() ? staffSection : custom, "基础冷却", 20);
        int baseCost = KomutechJson.getInt(custom.isEmpty() ? staffSection : custom, "基础消耗", 3);
        Map<String, Object> formula = KomutechJson.asMap(staffConfig.get("公式参数"));
        double wuxing = PlayerAttributeStore.getDouble(attr, "悟性", 1);
        double maxRed = KomutechJson.getDouble(formula, "冷却最大减免比例", 0.50);
        double reduction = Math.min(maxRed, Math.max(0,
            (wuxing - 1) * KomutechJson.getDouble(formula, "冷却减免因子", 0.08)));
        int minCd = KomutechJson.getInt(formula, "最小冷却", DEFAULT_MIN_CD_TICKS);
        int postGap = KomutechJson.getInt(formula, "释放后额外冷却", DEFAULT_POST_CAST_GAP_TICKS);
        int formulaCd = Math.max(minCd, (int) Math.floor(baseCd * (1 - reduction)));
        int effectTicks = staffEffectTicks(staffKey);
        // 上一次特效结束后，至少再冷却 postGap（默认 1s）
        int totalCd = Math.max(formulaCd, effectTicks + postGap);
        int cost = Math.max(1, (int) Math.round(baseCost * realmCoef(attr, KomutechJson.asMap(staffConfig.get("修为倍率")))
            * KomutechJson.getDouble(KomutechJson.asMap(staffConfig.get("灵根属性倍率")),
                PlayerAttributeStore.getString(attr, "灵根属性", "单灵根"), 1)
            * PlayerAttributeStore.getDouble(attr, "根骨", 1) * KomutechJson.getDouble(formula, "根骨消耗因子", 0.1)));
        long nowMs = System.currentTimeMillis();
        long readyAt = staffReadyAtMs.getOrDefault(player.getUniqueId(), 0L);
        if (nowMs < readyAt) {
            double remain = (readyAt - nowMs) / 1000.0;
            KomutechSupport.actionBar(player, "§c✖ 冷却中！剩余 §6" + String.format("%.1f", remain) + " §c秒");
            return false;
        }
        PlayerAttributeStore.Lingli ling = PlayerAttributeStore.parseLingli(attr.get("灵力"));
        if (ling.current() < cost) {
            KomutechSupport.actionBar(player, "§c灵力不足！当前 §6" + String.format("%.2f", ling.current()) + "§c / 需要 §6" + cost);
            return false;
        }
        attr.put("灵力", PlayerAttributeStore.formatLingli(
            new PlayerAttributeStore.Lingli(ling.current() - cost, ling.max(), ling.bonus())));
        staffReadyAtMs.put(player.getUniqueId(), nowMs + totalCd * 50L);
        KomutechSupport.actionBar(player, "§a消耗 " + cost + " 灵力 §7| §f剩余 "
            + String.format("%.0f", ling.current() - cost) + "/" + (long) ling.totalMax());
        player.getWorld().playSound(player.getLocation(), "entity.evoker.cast_spell", 1f, 1f);
        return true;
    }

    /** 持续特效时长（tick），用于“特效结束后再冷却” */
    private static int staffEffectTicks(String staffKey) {
        return switch (staffKey) {
            case "归墟" -> 9;
            case "花葬" -> 40;
            case "蜉蝣梦" -> 45;
            case "空引津" -> 16;
            default -> 0;
        };
    }

    public static int calcGenericSpiritCost(Map<String, Object> attr, double basePerUnit, int units, double genguScale) {
        if (units <= 0) {
            return 0;
        }
        ensureStaffConfig();
        Map<String, Object> formula = KomutechJson.asMap(staffConfig.get("公式参数"));
        double genguFactor = KomutechJson.getDouble(formula, "根骨消耗因子", 0.1) * genguScale;
        double realm = realmCoef(attr, KomutechJson.asMap(staffConfig.get("修为倍率")));
        double linggen = KomutechJson.getDouble(
            KomutechJson.asMap(staffConfig.get("灵根属性倍率")),
            PlayerAttributeStore.getString(attr, "灵根属性", "单灵根"),
            1
        );
        double gengu = PlayerAttributeStore.getDouble(attr, "根骨", 1);
        return Math.max(1, (int) Math.ceil(
            basePerUnit * units * realm * linggen * Math.max(0.5, gengu * genguFactor)
        ));
    }

    public static boolean tryConsumeSpiritPower(Player player, Map<String, Object> attr, int cost) {
        if (cost <= 0) {
            return true;
        }
        PlayerAttributeStore.Lingli ling = PlayerAttributeStore.parseLingli(attr.get("灵力"));
        if (ling.current() < cost) {
            KomutechSupport.actionBar(player, "§c灵力不足！需要 §6" + cost
                + " §c，当前 §6" + String.format("%.0f", ling.current()));
            return false;
        }
        attr.put("灵力", PlayerAttributeStore.formatLingli(
            new PlayerAttributeStore.Lingli(ling.current() - cost, ling.max(), ling.bonus())));
        return true;
    }

    public static double calcStaffDamage(Player player, ItemStack staff, Map<String, Object> attr) {
        ensureStaffConfig();
        // 功德不再参与灵杖增伤，仅用于突破雷劫减伤并消耗
        return PlayerAttributeStore.getDouble(attr, "攻击力_实际", 0)
            * getDamageScale(staff, KomutechJson.asMap(staffConfig.get("伤害倍率")))
            * realmCoef(attr, KomutechJson.asMap(staffConfig.get("修为倍率")))
            * KomutechJson.getDouble(KomutechJson.asMap(staffConfig.get("灵根属性倍率")),
                PlayerAttributeStore.getString(attr, "灵根属性", "单灵根"), 1);
    }

    public static double staffMaxDistance(String staffKey) {
        ensureStaffConfig();
        Map<String, Object> staffSection = KomutechJson.asMap(staffConfig.get("灵杖"));
        Map<String, Object> custom = KomutechJson.asMap(staffConfig.get(staffKey));
        return KomutechJson.getDouble(custom.isEmpty() ? staffSection : custom, "射程", 15);
    }

    public static void switchScroll(Player player, ItemStack staff) {
        List<String> owned = getOwnedScrollIds(player.getName());
        if (owned.isEmpty()) {
            KomutechSupport.send(player, "§c云篆匣中没有卷轴");
            return;
        }
        String current = getBoundSkill(staff);
        int idx = -1;
        for (int i = 0; i < owned.size(); i++) {
            if (extractSkillName(owned.get(i)).equals(current)) {
                idx = i;
                break;
            }
        }
        String nextId = owned.get((idx + 1) % owned.size());
        String nextSkill = extractSkillName(nextId);
        setBoundSkill(staff, nextSkill);
        KomutechSupport.send(player, "§a切换卷轴: §f" + nextSkill);
    }

    public static void applyMeritChange(Map<String, Object> attr, String entityType) {
        meritChangeDelta(attr, entityType);
    }

    public static int meritChangeDelta(Map<String, Object> attr, String entityType) {
        ensureStaffConfig();
        List<Object> whiteList = KomutechJson.asList(staffConfig.get("白名单生物"));
        List<Object> meritRange = KomutechJson.asList(staffConfig.get("功德变化范围"));
        int min = meritRange.size() >= 2 ? parseIntValue(meritRange.get(0), 1) : 1;
        int max = meritRange.size() >= 2 ? parseIntValue(meritRange.get(1), 10) : 10;
        int change = ThreadLocalRandom.current().nextInt(min, max + 1);
        boolean white = whiteList.stream().anyMatch(v -> entityType.equals(String.valueOf(v)));
        if (white) {
            attr.put("煞气", PlayerAttributeStore.getInt(attr, "煞气", 0) + change);
            attr.put("功德", Math.max(0, PlayerAttributeStore.getInt(attr, "功德", 0) - change));
            return -change;
        }
        attr.put("功德", PlayerAttributeStore.getInt(attr, "功德", 0) + change);
        return change;
    }

    /**
     * 延迟判定单击攻击，避免第一次右键立即攻击导致双击切换失败。
     *
     * @return true 表示检测到双击，应切换卷轴
     */
    public static boolean handleStaffRightClick(Player player, Runnable attackAction) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long first = firstRightClick.get(id);
        if (first != null && now - first <= DOUBLE_CLICK_WINDOW_MS) {
            firstRightClick.remove(id);
            cancelPendingAttack(id);
            return true;
        }
        firstRightClick.put(id, now);
        cancelPendingAttack(id);
        Plugin plugin = KomutechSupport.plugin();
        if (plugin == null) {
            attackAction.run();
            return false;
        }
        int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingAttackTasks.remove(id);
            firstRightClick.remove(id);
            if (player.isOnline()) {
                attackAction.run();
            }
        }, ATTACK_DELAY_TICKS).getTaskId();
        pendingAttackTasks.put(id, taskId);
        return false;
    }

    private static void cancelPendingAttack(UUID id) {
        Integer taskId = pendingAttackTasks.remove(id);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    public static String getBoundSkill(ItemStack staff) {
        if (staff == null || !staff.hasItemMeta()) {
            return null;
        }
        List<String> lore = staff.getItemMeta().getLore();
        if (lore == null) {
            return null;
        }
        for (String line : lore) {
            if (line.startsWith("§b绑定卷轴：")) {
                return line.substring("§b绑定卷轴：§f".length()).trim();
            }
        }
        return null;
    }

    public static void setBoundSkill(ItemStack staff, String skill) {
        ItemMeta meta = staff.getItemMeta();
        List<String> lore = meta.getLore() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(meta.getLore());
        String line = "§b绑定卷轴：§f" + skill;
        boolean found = false;
        for (int i = 0; i < lore.size(); i++) {
            if (lore.get(i).startsWith("§b绑定卷轴：")) {
                lore.set(i, line);
                found = true;
                break;
            }
        }
        if (!found) {
            lore.add(line);
        }
        meta.setLore(lore);
        staff.setItemMeta(meta);
    }

    private static void rayDamage(Player player, Map<String, Object> attr, ItemStack staff, double damage, double maxDist,
                                  List<Object> whiteList, List<Object> meritRange,
                                  Color startColor, Color endColor, float size, double step) {
        var eye = player.getEyeLocation();
        var dir = eye.getDirection();
        var start = eye.clone().add(dir);
        ParticleBudget.spawnRayTrail(player.getWorld(), start, dir, maxDist, startColor, endColor, size, step);
        RayTraceResult hit = player.getWorld().rayTrace(start, dir, maxDist, FluidCollisionMode.NEVER, false, 0.1,
            entity -> entity != player && entity instanceof LivingEntity living && !living.getType().name().equals("ARMOR_STAND"));
        if (hit == null || !(hit.getHitEntity() instanceof LivingEntity target)) {
            return;
        }
        target.damage(damage, player);
        if (!(target instanceof Player)) {
            applyMeritChange(attr, target.getType().name());
        }
    }

    private static List<String> getOwnedScrollIds(String playerName) {
        Map<String, Object> data = loadScrollData(playerName);
        if (data.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object raw : KomutechJson.asList(data.get("卷轴数据"))) {
            if (raw != null) {
                ids.add(String.valueOf(raw));
            }
        }
        return ids;
    }

    private static String extractSkillName(String fullId) {
        return normalizeSkillId(fullId);
    }

    private static int parseIntValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double getDamageScale(ItemStack item, Map<String, Object> map) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta().getLore() == null) {
            return 1.0;
        }
        for (String line : item.getItemMeta().getLore()) {
            String clean = line.replaceAll("§.", "");
            var m = java.util.regex.Pattern.compile("『([^』]+)阶』").matcher(clean);
            if (m.find()) {
                return KomutechJson.getDouble(map, m.group(1) + "阶", 1.0);
            }
        }
        return 1.0;
    }

    private static double realmCoef(Map<String, Object> attr, Map<String, Object> table) {
        String spirit = PlayerAttributeStore.getString(attr, "灵气", "0");
        var m = java.util.regex.Pattern.compile("^(\\d+)").matcher(spirit);
        double value = m.find() ? Double.parseDouble(m.group(1)) : 0;
        if (value < 100) {
            return 0.1;
        }
        if (value >= 100_000_000_000L) {
            List<Object> immortal = KomutechJson.asList(table.get("仙人"));
            if (!immortal.isEmpty()) {
                return Double.parseDouble(String.valueOf(immortal.get(0)));
            }
            return 48;
        }
        double[] thresholds = {100, 1000, 10000, 100000, 1000000, 10000000, 100000000, 1000000000};
        String[] names = {"引气入体", "练气", "筑基", "金丹", "元婴", "化神", "合体", "渡劫", "飞升", "仙人"};
        int idx = 0;
        for (int i = 1; i < thresholds.length; i++) {
            if (value < thresholds[i]) {
                idx = i - 1;
                break;
            }
            idx = i;
        }
        if (value >= 1_000_000_000) {
            idx = 7;
        }
        List<Object> arr = KomutechJson.asList(table.get(names[idx]));
        if (arr.isEmpty() && "合体".equals(names[idx])) {
            arr = KomutechJson.asList(table.get("大成"));
        }
        if (arr.isEmpty() && "仙人".equals(names[idx])) {
            arr = KomutechJson.asList(table.get("神人"));
        }
        double base = idx == 0 ? 100 : thresholds[idx];
        double range = idx == 7 ? 100_000_000_000L - 1_000_000_000 : thresholds[idx + 1] - base;
        int step = (int) Math.floor(range / 9.0);
        int rank = (int) Math.floor((value - base) / step);
        if (rank >= arr.size()) {
            rank = arr.size() - 1;
        }
        return arr.isEmpty() ? 1 : Double.parseDouble(String.valueOf(arr.get(Math.max(0, rank))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadScrollData(String playerName) {
        try {
            var path = scrollDataDir().resolve("[" + playerName + "]云篆匣.json");
            if (!Files.exists(path)) {
                return new HashMap<>();
            }
            return KomutechJson.asMap(KomutechJson.parse(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            return new HashMap<>();
        }
    }

    private static int getProficiency(String playerName, String skillId) {
        Map<String, Object> data = loadScrollData(playerName);
        Map<String, Object> prof = KomutechJson.asMap(data.get("熟练度记录"));
        return KomutechJson.getInt(prof, skillId, 0);
    }

    private static int addProficiency(String playerName, String skillId, int amount, int max) {
        Map<String, Object> data = loadScrollData(playerName);
        // asMap 返回拷贝，必须 put 回 data 后才能持久化
        Map<String, Object> prof = new HashMap<>(KomutechJson.asMap(data.get("熟练度记录")));
        int old = KomutechJson.getInt(prof, skillId, 0);
        int added = Math.min(amount, Math.max(0, max - old));
        if (added <= 0) {
            return 0;
        }
        prof.put(skillId, old + added);
        data.put("熟练度记录", prof);
        try {
            Files.createDirectories(scrollDataDir());
            Files.writeString(scrollDataDir().resolve("[" + playerName + "]云篆匣.json"),
                KomutechJson.stringify(data), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        return added;
    }

    private static void ensureScrollConfig() {
        java.nio.file.Path path = scrollConfigPath();
        long mtime = fileMtime(path);
        if (scrollConfig == null || mtime != scrollConfigMtime) {
            reloadScrollConfig();
        }
    }

    private static void reloadScrollConfig() {
        java.nio.file.Path path = scrollConfigPath();
        scrollConfig = KomutechConfigMerge.loadMerged(path, SCROLL_CFG_RESOURCE);
        scrollConfigMtime = fileMtime(path);
    }

    private static void ensureStaffConfig() {
        java.nio.file.Path path = staffConfigPath();
        long mtime = fileMtime(path);
        if (staffConfig == null || mtime != staffConfigMtime) {
            reloadStaffConfig();
        }
    }

    private static void reloadStaffConfig() {
        java.nio.file.Path path = staffConfigPath();
        staffConfig = KomutechConfigMerge.loadMerged(path, STAFF_CFG_RESOURCE);
        staffConfigMtime = fileMtime(path);
    }

    private static long fileMtime(java.nio.file.Path path) {
        try {
            if (Files.exists(path)) {
                return Files.getLastModifiedTime(path).toMillis();
            }
        } catch (IOException ignored) {
        }
        return -1L;
    }

    private static String trim(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
