package tech.komutech.native_scripts.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

public final class AttributePointLimits {

    private static final String EXEMPT_KEY = "属性上限豁免";
    private static final double SPIRIT_GAIN_BASE = 100.0;
    private static final double SPIRIT_GAIN_CAP = 500.0;
    private static final double SPIRIT_GAIN_CAP_HUASHEN = 2000.0;
    private static final double HEALTH_CAP_BASE = 10000.0;
    private static final double HEALTH_CAP_STEP = 10000.0;
    private static final double HEALTH_CAP_MAX = 50000.0;
    private static final List<String> LIMITED_ATTRS = List.of("血量", "攻击力", "防御力", "速度", "灵气获取");
    private static final Map<String, Double> DEFAULTS = Map.of(
        "血量", HEALTH_CAP_BASE,
        "攻击力", 2000.0,
        "防御力", 1000.0,
        "速度", 100.0,
        "灵气获取", SPIRIT_GAIN_CAP
    );
    private static volatile Map<String, Double> cachedLimits = DEFAULTS;
    private static volatile double cachedSpiritGainHuaShenCap = SPIRIT_GAIN_CAP_HUASHEN;
    private static volatile double cachedHealthBase = HEALTH_CAP_BASE;
    private static volatile double cachedHealthStep = HEALTH_CAP_STEP;
    private static volatile double cachedHealthMax = HEALTH_CAP_MAX;
    private static volatile long cachedAt = 0L;
    private static final long CACHE_MS = 60_000L;

    private AttributePointLimits() {
    }

    public static boolean isLimited(String attr) {
        return LIMITED_ATTRS.contains(attr);
    }

    public static double limit(String attr) {
        reloadIfNeeded();
        return cachedLimits.getOrDefault(attr, DEFAULTS.getOrDefault(attr, Double.MAX_VALUE));
    }

    /** 血量 / 灵气获取会按修为动态上限；其他属性与 {@link #limit(String)} 相同。 */
    public static double limit(Map<String, Object> data, String attr) {
        if ("灵气获取".equals(attr)) {
            return spiritGainCap(data);
        }
        if ("血量".equals(attr)) {
            return healthCap(data);
        }
        return limit(attr);
    }

    public static double spiritGainCap(Map<String, Object> data) {
        reloadIfNeeded();
        if (data != null && CultivationMath.isHuaShenOrAbove(data)) {
            return cachedSpiritGainHuaShenCap;
        }
        return limit("灵气获取");
    }

    /**
     * 血量实际加成上限：化神起为基数，每突破一个大境界 +步进，不超过极限。
     * 化神 10000 → 合体 20000 → 渡劫 30000 → 飞升 40000 → 仙人 50000。
     */
    public static double healthCap(Map<String, Object> data) {
        reloadIfNeeded();
        int tier = data == null ? -1 : CultivationMath.majorRealmIndexFromHuaShen(data);
        if (tier < 0) {
            return cachedHealthBase;
        }
        return Math.min(cachedHealthMax, cachedHealthBase + tier * cachedHealthStep);
    }

    public static double computeActual(Map<String, Object> data, String attr, double points) {
        if (points <= 0) {
            return 0;
        }
        List<String> elements = CultivationMath.splitLinggen(PlayerAttributeStore.getString(data, "灵根", ""));
        double quality = PlayerAttributeStore.getDouble(data, "总品质", 0.01);
        double gengu = PlayerAttributeStore.getDouble(data, "根骨", 1);
        return CultivationMath.computeActualValue(
            points,
            CultivationMath.baseGrowth.getOrDefault(attr, 0.0),
            CultivationMath.getLinggenFinalCoefficient(elements, quality, attr),
            CultivationMath.getRealmCoefficient(data),
            quality,
            gengu
        );
    }

    public static double spiritGainPercent(Map<String, Object> data, int points) {
        if (points <= 0) {
            return SPIRIT_GAIN_BASE;
        }
        return Math.min(spiritGainCap(data), SPIRIT_GAIN_BASE + computeActual(data, "灵气获取", points));
    }

    public static boolean canAllocatePoint(Map<String, Object> data, String attr, int currentPoints) {
        if (!isLimited(attr)) {
            return true;
        }
        if ("灵气获取".equals(attr)) {
            return spiritGainPercent(data, currentPoints + 1) <= spiritGainCap(data) + 1e-6;
        }
        return computeActual(data, attr, currentPoints + 1) <= limit(data, attr) + 1e-6;
    }

    public static boolean isAtLimit(Map<String, Object> data, String attr, int currentPoints) {
        if (!isLimited(attr)) {
            return false;
        }
        if ("灵气获取".equals(attr)) {
            return spiritGainPercent(data, currentPoints) >= spiritGainCap(data) - 1e-6;
        }
        return computeActual(data, attr, currentPoints) >= limit(data, attr) - 1e-6;
    }

    public static double clampForApply(Map<String, Object> data, String attr, double actualValue) {
        return clampForApply(data, attr, actualValue, true);
    }

    /**
     * @param respectExempt true 时尊重管理员豁免；个人模式应用应传 false，确保永远不超过配置上限。
     */
    public static double clampForApply(Map<String, Object> data, String attr, double actualValue, boolean respectExempt) {
        if (!isLimited(attr)) {
            return actualValue;
        }
        if (respectExempt && isExempt(data)) {
            return actualValue;
        }
        return Math.min(actualValue, limit(data, attr));
    }

    public static boolean isExempt(Map<String, Object> data) {
        return PlayerAttributeStore.getBoolean(data, EXEMPT_KEY, false);
    }

    public static void markAdminExempt(Map<String, Object> data) {
        data.put(EXEMPT_KEY, true);
    }

    public static void clearExempt(Map<String, Object> data) {
        if (data != null) {
            data.remove(EXEMPT_KEY);
        }
    }

    public static String limitLabel(String attr) {
        return formatLimitLabel(attr, limit(attr));
    }

    public static String limitLabel(Map<String, Object> data, String attr) {
        return formatLimitLabel(attr, limit(data, attr));
    }

    private static String formatLimitLabel(String attr, double cap) {
        if ("速度".equals(attr) || "灵气获取".equals(attr)) {
            return String.format("%.0f%%", cap);
        }
        return String.format("%.0f", cap);
    }

    private static void reloadIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MS && cachedLimits != null) {
            return;
        }
        try {
            if (!Files.exists(KomutechPaths.attributePointLimits())) {
                cachedLimits = DEFAULTS;
                cachedSpiritGainHuaShenCap = SPIRIT_GAIN_CAP_HUASHEN;
                cachedHealthBase = HEALTH_CAP_BASE;
                cachedHealthStep = HEALTH_CAP_STEP;
                cachedHealthMax = HEALTH_CAP_MAX;
            } else {
                Map<String, Object> cfg = KomutechJson.asMap(KomutechJson.parse(
                    Files.readString(KomutechPaths.attributePointLimits(), StandardCharsets.UTF_8)));
                cachedLimits = Map.of(
                    "血量", readLimit(cfg, "血量", DEFAULTS.get("血量")),
                    "攻击力", readLimit(cfg, "攻击力", DEFAULTS.get("攻击力")),
                    "防御力", readLimit(cfg, "防御力", DEFAULTS.get("防御力")),
                    "速度", readLimit(cfg, "速度", DEFAULTS.get("速度")),
                    "灵气获取", readLimit(cfg, "灵气获取", DEFAULTS.get("灵气获取"))
                );
                cachedSpiritGainHuaShenCap = readLimit(cfg, "灵气获取_化神", SPIRIT_GAIN_CAP_HUASHEN);
                cachedHealthBase = readLimit(cfg, "血量", HEALTH_CAP_BASE);
                cachedHealthStep = readLimit(cfg, "血量_步进", HEALTH_CAP_STEP);
                cachedHealthMax = readLimit(cfg, "血量_极限", HEALTH_CAP_MAX);
            }
        } catch (Exception ignored) {
            cachedLimits = DEFAULTS;
            cachedSpiritGainHuaShenCap = SPIRIT_GAIN_CAP_HUASHEN;
            cachedHealthBase = HEALTH_CAP_BASE;
            cachedHealthStep = HEALTH_CAP_STEP;
            cachedHealthMax = HEALTH_CAP_MAX;
        }
        cachedAt = now;
    }

    private static double readLimit(Map<String, Object> cfg, String key, double fallback) {
        double value = KomutechJson.getDouble(cfg, key, fallback);
        return value > 0 ? value : fallback;
    }
}
