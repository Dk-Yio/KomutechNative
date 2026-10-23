package tech.komutech.native_scripts.support;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tech.komutech.native_scripts.cultivation.JianLingShiSupport;

public final class CultivationMath {

    public static final List<String> SWITCHABLE_ATTRS = List.of("血量", "攻击力", "防御力", "速度", "灵识");
    public static final List<String> VALID_LINGGEN = List.of("金", "木", "水", "火", "土", "雷", "风", "冰", "光", "暗");

    private static final double[][] REALM_BONUS = {
        {0.10, 0.13, 0.16, 0.19, 0.22, 0.25, 0.28, 0.31, 0.34},
        {0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90},
        {1.20, 1.30, 1.40, 1.50, 1.60, 1.70, 1.80, 1.90, 2.00},
        {2.50, 2.70, 2.90, 3.10, 3.30, 3.50, 3.70, 3.90, 4.10},
        {4.50, 4.80, 5.10, 5.40, 5.70, 6.00, 6.30, 6.60, 6.90},
        {7.50, 8.00, 8.50, 9.00, 9.50, 10.00, 10.50, 11.00, 11.50},
        {12.50, 13.50, 14.50, 15.50, 16.50, 17.50, 18.50, 19.50, 20.50},
        {22.00, 23.50, 25.00, 26.50, 28.00, 29.50, 31.00, 32.50, 34.00},
        {36.00, 38.00, 40.00, 42.00, 44.00, 46.00, 48.00, 50.00, 52.00}
    };

    private static final Map<Integer, Double> DECAY_FACTOR = Map.of(1, 1.0, 2, 0.8, 3, 0.5, 4, 0.3, 5, 0.1);
    private static final Map<String, Boolean> MUTATED = Map.of(
        "雷", true, "风", true, "冰", true, "光", true, "暗", true
    );

    private static final Map<String, Map<String, Double>> LINGGEN_BONUS = new HashMap<>();

    static {
        putBonus("金", 0.8, 1.2, 1.0, 0.5, 0.8, 1.0);
        putBonus("木", 1.2, 0.8, 1.0, 0.8, 1.2, 1.2);
        putBonus("水", 1.0, 0.9, 0.8, 0.8, 1.0, 1.5);
        putBonus("火", 1.0, 1.0, 0.8, 0.8, 0.8, 0.8);
        putBonus("土", 1.0, 0.8, 1.2, 0.5, 0.9, 0.9);
        putBonus("雷", 1.2, 1.8, 1.0, 1.5, 1.5, 1.5);
        putBonus("风", 1.0, 1.2, 0.8, 1.8, 1.0, 1.3);
        putBonus("冰", 1.2, 1.5, 1.0, 1.2, 1.2, 1.4);
        putBonus("光", 1.3, 1.5, 1.1, 1.5, 1.5, 1.5);
        putBonus("暗", 1.1, 1.3, 1.4, 1.2, 1.5, 1.3);
    }

    public static Map<String, Double> baseGrowth = Map.of(
        "血量", 3.0, "攻击力", 1.0, "防御力", 1.0, "速度", 0.1,
        "灵识", 0.01, "灵力", 0.1, "灵气获取", 2.5
    );

    private CultivationMath() {
    }

    private static void putBonus(String element, double hp, double atk, double def, double speed, double sense, double lingli) {
        LINGGEN_BONUS.put(element, Map.of(
            "血量", hp, "攻击力", atk, "防御力", def, "速度", speed,
            "灵识", sense, "灵力", lingli, "灵气获取", lingli
        ));
    }

    public static List<String> splitLinggen(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[、，\\s]+")).filter(s -> !s.isBlank()).toList();
    }

    private static long parseSpiritValue(String spirit) {
        if (spirit == null || spirit.isBlank()) {
            return 0;
        }
        var match = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)").matcher(spirit.trim());
        if (!match.find()) {
            return 0;
        }
        String raw = match.group(1);
        try {
            if (raw.contains(".")) {
                return (long) Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return Long.MAX_VALUE;
        }
    }

    public static double getRealmCoefficient(Map<String, Object> data) {
        long value = parseSpiritValue(PlayerAttributeStore.getString(data, "灵气", ""));
        if (value < 100) {
            return 0;
        }
        if (value >= 100_000_000_000L) {
            return 18 + Math.floor((value - 100_000_000_000L) / 100_000_000_000.0) * 2;
        }
        long[] realmBases = {100, 1000, 10000, 100000, 1000000, 10000000, 100000000, 1000000000L};
        int idx = 0;
        for (int i = 0; i < realmBases.length; i++) {
            if (value < realmBases[i]) {
                idx = i - 1;
                break;
            }
            idx = i;
        }
        if (idx < 0) {
            idx = 0;
        }
        if (value >= 1_000_000_000L) {
            idx = 7;
        }
        long base = realmBases[idx];
        long range = idx == 7 ? 90_000_000_000L : realmBases[idx + 1] - base;
        long step = Math.max(1, range / 9);
        int rank = (int) Math.floor((value - base) / (double) step);
        if (rank >= 9) {
            rank = 8;
        }
        return REALM_BONUS[idx][rank];
    }

    public static CultivationInfo getCultivationInfo(long value) {
        if (value < 100) {
            return new CultivationInfo("『引气入体』", 100, "100");
        }
        record Bracket(int base, long next, String name) {
        }
        Bracket[] brackets = {
            new Bracket(100, 1000, "练气"),
            new Bracket(1000, 10000, "筑基"),
            new Bracket(10000, 100000, "金丹"),
            new Bracket(100000, 1000000, "元婴"),
            new Bracket(1000000, 10000000, "化神"),
            new Bracket(10000000, 100000000, "合体"),
            new Bracket(100000000, 1000000000, "渡劫"),
            new Bracket(1000000000, 100_000_000_000L, "飞升")
        };
        for (Bracket bracket : brackets) {
            if (value < bracket.next) {
                long range = bracket.next - bracket.base;
                long step = Math.max(1, range / 9);
                int rank = (int) Math.floor((value - bracket.base) / (double) step);
                if (rank >= 9) {
                    rank = 8;
                }
                long spiritCap = Math.round(bracket.next * ((rank + 1) * 0.1));
                return new CultivationInfo("『" + bracket.name + "·" + (rank + 1) + "阶』", spiritCap, String.valueOf(bracket.next));
            }
        }
        int rank = (int) Math.floor((value - 100_000_000_000L) / 100_000_000_000.0) + 1;
        if (rank < 1) {
            rank = 1;
        }
        long spiritCap = Math.round(100_000_000_000L * (rank * 0.1));
        return new CultivationInfo("『仙人·" + rank + "阶』", spiritCap, "無");
    }

    public static CultivationInfo getCultivationInfo(String spirit) {
        return getCultivationInfo(parseSpiritValue(spirit));
    }

    public static String computeLinggenAttr(List<String> elements, double quality) {
        int count = elements.size();
        long mutatedCount = elements.stream().filter(MUTATED::containsKey).count();
        if (count == 1) {
            String element = elements.get(0);
            if (MUTATED.containsKey(element)) {
                return "变异灵根";
            }
            if (quality > 0.9) {
                return "天灵根";
            }
            return "单灵根";
        }
        if (count >= 5) {
            return "杂灵根";
        }
        if (count > 1 && mutatedCount > 0) {
            return "变异多灵根";
        }
        return switch (count) {
            case 4 -> "四灵根";
            case 3 -> "三灵根";
            case 2 -> "双灵根";
            default -> "未知";
        };
    }

    public static double getLinggenFinalCoefficient(List<String> elements, double quality, String attr) {
        double baseCoeff = calculateCoefficients(elements, quality).getOrDefault(attr, 0.0);
        String attrType = computeLinggenAttr(elements, quality);
        double extra = switch (attrType) {
            case "杂灵根" -> 0.1;
            case "四灵根" -> 0.3;
            case "三灵根" -> 0.5;
            case "双灵根" -> 0.8;
            case "单灵根" -> 1.0;
            case "天灵根" -> 1.2;
            case "变异灵根" -> 1.3;
            default -> 1.0;
        };
        return (1.0 + baseCoeff) * extra;
    }

    public static Map<String, Double> calculateCoefficients(List<String> elements, double quality) {
        Map<String, Double> coeffs = new HashMap<>();
        for (String attr : List.of("血量", "攻击力", "防御力", "速度", "灵识", "灵力", "灵气获取")) {
            coeffs.put(attr, 0.0);
        }
        if (elements.isEmpty()) {
            return coeffs;
        }
        int count = elements.size();
        double decay = DECAY_FACTOR.getOrDefault(count, 0.1);
        Map<String, Double> sum = new HashMap<>();
        for (String attr : coeffs.keySet()) {
            sum.put(attr, 0.0);
        }
        for (String element : elements) {
            Map<String, Double> bonus = LINGGEN_BONUS.getOrDefault(element, Map.of());
            for (String attr : sum.keySet()) {
                sum.merge(attr, bonus.getOrDefault(attr, 0.0), Double::sum);
            }
        }
        for (String attr : coeffs.keySet()) {
            coeffs.put(attr, (sum.get(attr) / count) * quality * decay);
        }
        return coeffs;
    }

    public static double computeActualValue(double points, double base, double linggenCoeff, double realmCoeff,
                                           double quality, double gengu) {
        double raw = points * base * (linggenCoeff + realmCoeff) * quality * (gengu / 10.0);
        return Math.max(0.0, Math.round(raw * 100.0) / 100.0);
    }

    public static int computeBaseMaxLingli(Map<String, Object> data) {
        List<String> elements = splitLinggen(PlayerAttributeStore.getString(data, "灵根", ""));
        double quality = PlayerAttributeStore.getDouble(data, "总品质", 0.01);
        double linggenCoeff = getLinggenFinalCoefficient(elements, quality, "灵力");
        long spiritValue = parseSpiritValue(PlayerAttributeStore.getString(data, "灵气", "0"));
        CultivationInfo info = getCultivationInfo(spiritValue);
        double decay = DECAY_FACTOR.getOrDefault(elements.size(), 0.1);
        double gengu = PlayerAttributeStore.getDouble(data, "根骨", 1);
        double growth = baseGrowth.getOrDefault("灵力", 0.1);
        double computed = Math.floor(info.spiritCap() * linggenCoeff * decay * quality * growth * (gengu / 10.0)) + 100;
        if (computed >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (computed <= 0) {
            return 100;
        }
        return (int) computed;
    }

    public static void ensureDataComplete(String playerName, Map<String, Object> data) {
        boolean modified = false;
        for (String attr : SWITCHABLE_ATTRS) {
            if (!data.containsKey(attr + "_启用")) {
                data.put(attr + "_启用", true);
                modified = true;
            }
            if (!data.containsKey(attr + "_实际")) {
                data.put(attr + "_实际", 0.0);
                modified = true;
            }
        }
        if (!data.containsKey("属性点")) {
            data.put("属性点", 3);
            modified = true;
        }
        if (!data.containsKey("灵气获取")) {
            data.put("灵气获取", 0);
            modified = true;
        }
        if (!data.containsKey("灵气获取_实际")) {
            data.put("灵气获取_实际", 100.0);
            modified = true;
        }
        if (!data.containsKey("攻击力")) {
            data.put("攻击力", 0);
            modified = true;
        }
        if (JianLingShiSupport.completeLinggenFieldsIfNeeded(data)) {
            modified = true;
        }
        String lingli = PlayerAttributeStore.getString(data, "灵力", "");
        if (lingli.isBlank() || !lingli.contains("+")) {
            ensureLingliFormat(data);
            modified = true;
        }
        if (modified) {
            PlayerAttributeStore.save(playerName, data);
        }
    }

    public static void ensureLingliFormat(Map<String, Object> data) {
        String lingli = PlayerAttributeStore.getString(data, "灵力", "");
        var match = java.util.regex.Pattern.compile("^(\\d+)/(\\d+)$").matcher(lingli);
        if (match.find()) {
            data.put("灵力", match.group(1) + "/" + match.group(2) + "+0");
            return;
        }
        int base = computeBaseMaxLingli(data);
        data.put("灵力", base + "/" + base + "+0");
    }

    public static boolean isImmortalRealm(Map<String, Object> data) {
        return parseSpiritValue(PlayerAttributeStore.getString(data, "灵气", "")) >= 100_000_000_000L;
    }

    public static boolean isGoldenCoreRealm(Map<String, Object> data) {
        return spiritValue(data) >= 10000;
    }

    /** 化神期起（含更高境界）：灵气当前值 ≥ 1_000_000 */
    public static boolean isHuaShenOrAbove(Map<String, Object> data) {
        return spiritValue(data) >= 1_000_000L;
    }

    public static long spiritValue(Map<String, Object> data) {
        return parseSpiritValue(PlayerAttributeStore.getString(data, "灵气", "0"));
    }

    /**
     * 自大境界「化神」起的阶位：未达化神返回 -1；
     * 化神=0，合体=1，渡劫=2，飞升=3，仙人=4。
     */
    public static int majorRealmIndexFromHuaShen(Map<String, Object> data) {
        long value = spiritValue(data);
        if (value < 1_000_000L) {
            return -1;
        }
        if (value >= 100_000_000_000L) {
            return 4;
        }
        if (value >= 1_000_000_000L) {
            return 3;
        }
        if (value >= 100_000_000L) {
            return 2;
        }
        if (value >= 10_000_000L) {
            return 1;
        }
        return 0;
    }

    public record CultivationInfo(String stage, long spiritCap, String maxStr) {
    }
}
