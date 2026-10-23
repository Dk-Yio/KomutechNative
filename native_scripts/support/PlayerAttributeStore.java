package tech.komutech.native_scripts.support;



import java.io.IOException;

import java.nio.charset.StandardCharsets;

import java.nio.file.Files;

import java.nio.file.Path;

import java.util.LinkedHashMap;

import java.util.Map;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tech.komutech.native_scripts.support.KomutechJson;
import tech.komutech.native_scripts.support.KomutechPaths;

public final class PlayerAttributeStore {

    private static Path dataDir() {
        return KomutechPaths.playerAttributes();
    }



    private PlayerAttributeStore() {

    }



    public static Path fileFor(String playerName) {

        return dataDir().resolve("[" + playerName + "].json");

    }



    public static boolean exists(String playerName) {

        return Files.exists(fileFor(playerName));

    }



    public static Map<String, Object> load(String playerName) {

        Path path = fileFor(playerName);

        if (!Files.exists(path)) {

            return null;

        }

        try {

            String raw = Files.readString(path, StandardCharsets.UTF_8);

            return KomutechJson.asMap(KomutechJson.parse(raw));

        } catch (IOException | RuntimeException exception) {

            return null;

        }

    }



    public static boolean save(String playerName, Map<String, Object> data) {

        try {

            Path path = fileFor(playerName);

            Files.createDirectories(path.getParent());

            Files.writeString(path, prettyJson(data), StandardCharsets.UTF_8);

            return true;

        } catch (IOException exception) {

            return false;

        }

    }



    public static boolean delete(String playerName) {

        try {

            return Files.deleteIfExists(fileFor(playerName));

        } catch (IOException exception) {

            return false;

        }

    }



    @SuppressWarnings("unchecked")

    public static Map<String, Object> deepCopy(Map<String, Object> source) {

        return KomutechJson.asMap(KomutechJson.parse(KomutechJson.stringify(source)));

    }



    private static final Pattern LINGLI_PATTERN = Pattern.compile(
        "^(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?|無|无)\\s*(?:\\+\\s*(-?\\d+(?:\\.\\d+)?))?$"
    );

    public static Lingli parseLingli(Object raw) {
        String text = raw == null ? "100/100+0" : String.valueOf(raw).trim();
        Matcher m = LINGLI_PATTERN.matcher(text);
        if (m.find()) {
            double bonus = m.group(3) == null ? 0 : parseLargeNumber(m.group(3));
            return new Lingli(parseLargeNumber(m.group(1)), parseMaxNumber(m.group(2)), bonus);
        }
        // 兼容仅写入当前值、或上限为无的仙人格式未能匹配时，尽量解析当前灵气
        Matcher currentOnly = Pattern.compile("^(\\d+(?:\\.\\d+)?)").matcher(text);
        if (currentOnly.find() && text.contains("/")) {
            String maxPart = text.substring(text.indexOf('/') + 1).trim();
            if (maxPart.startsWith("無") || maxPart.startsWith("无")) {
                return new Lingli(parseLargeNumber(currentOnly.group(1)), Double.POSITIVE_INFINITY, 0);
            }
        }
        return new Lingli(100, 100, 0);
    }

    private static double parseMaxNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        if ("無".equals(raw) || "无".equals(raw)) {
            return Double.POSITIVE_INFINITY;
        }
        return parseLargeNumber(raw);
    }

    private static double parseLargeNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            if (raw.contains(".")) {
                return Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return Double.parseDouble(raw);
        }
    }

    public static String formatSpiritAmount(double current, double max) {
        return formatAmount(current) + "/" + formatMax(max);
    }

    public static String formatMax(double max) {
        if (Double.isInfinite(max)) {
            return "無";
        }
        return trimDouble(max);
    }

    public static String formatAmount(double value) {
        if (Double.isInfinite(value) || Double.isNaN(value)) {
            return "0";
        }
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format("%.2f", value);
    }

    public static String formatLingli(Lingli lingli) {
        String base = formatSpiritAmount(lingli.current, lingli.max);
        if (lingli.bonus == 0) {
            return base;
        }
        String bonusText = trimDouble(lingli.bonus);
        return base + (lingli.bonus > 0 ? "+" + bonusText : bonusText);
    }



    public static double getDouble(Map<String, Object> data, String key, double fallback) {

        return KomutechJson.getDouble(data, key, fallback);

    }



    public static int getInt(Map<String, Object> data, String key, int fallback) {

        return KomutechJson.getInt(data, key, fallback);

    }



    public static String getString(Map<String, Object> data, String key, String fallback) {

        return KomutechJson.getString(data, key, fallback);

    }



    public static boolean getBoolean(Map<String, Object> data, String key, boolean fallback) {

        return KomutechJson.getBoolean(data, key, fallback);

    }



    @SuppressWarnings("unchecked")

    public static Map<String, Object> getMap(Map<String, Object> data, String key) {

        if (data == null || !data.containsKey(key)) {

            return new LinkedHashMap<>();

        }

        Object value = data.get(key);

        if (value instanceof Map<?, ?> map) {

            return KomutechJson.asMap(value);

        }

        return new LinkedHashMap<>();

    }



    private static String trimDouble(double value) {

        if (Math.floor(value) == value) {

            return String.valueOf((long) value);

        }

        return String.valueOf(value);

    }



    private static String prettyJson(Map<String, Object> data) {

        return KomutechJson.stringify(data);

    }



    public record Lingli(double current, double max, double bonus) {
        public double totalMax() {
            if (Double.isInfinite(max)) {
                return Double.POSITIVE_INFINITY;
            }
            return max + bonus;
        }

        /** 仙人境界上限「無」 */
        public boolean isUnlimitedMax() {
            return Double.isInfinite(max);
        }
    }

}

