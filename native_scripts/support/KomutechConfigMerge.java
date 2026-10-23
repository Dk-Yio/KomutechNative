package tech.komutech.native_scripts.support;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import tech.origintech.NativeAddonConfig;

/**
 * 磁盘配置可能落后于 jar：用 classpath 内置配置补齐/覆盖已知键，并回写磁盘。
 */
public final class KomutechConfigMerge {

    private KomutechConfigMerge() {
    }

    public static Map<String, Object> loadMerged(Path diskPath, String classpathResource) {
        Map<String, Object> disk = readDisk(diskPath);
        Map<String, Object> embedded = readClasspath(classpathResource);
        if (embedded.isEmpty()) {
            return disk;
        }
        MergeResult merged = merge(disk, embedded);
        if (merged.changed) {
            try {
                Path parent = diskPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(diskPath, KomutechJson.stringify(merged.map), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        return merged.map;
    }

    private static MergeResult merge(Map<String, Object> disk, Map<String, Object> embedded) {
        Map<String, Object> out = new LinkedHashMap<>(disk);
        boolean changed = false;
        for (Map.Entry<String, Object> entry : embedded.entrySet()) {
            String key = entry.getKey();
            Object embVal = entry.getValue();
            Object diskVal = out.get(key);
            if (diskVal == null) {
                out.put(key, embVal);
                changed = true;
                continue;
            }
            if (embVal instanceof Map<?, ?> embMap && diskVal instanceof Map<?, ?>) {
                Map<String, Object> childDisk = KomutechJson.asMap(diskVal);
                Map<String, Object> childEmb = KomutechJson.asMap(embMap);
                MergeResult child = merge(childDisk, childEmb);
                out.put(key, child.map);
                changed = changed || child.changed;
            } else if (!Objects.equals(String.valueOf(diskVal), String.valueOf(embVal))) {
                // jar 为战斗数值源：覆盖基础冷却/熟练度上限等标量
                out.put(key, embVal);
                changed = true;
            }
        }
        return new MergeResult(out, changed);
    }

    private static Map<String, Object> readDisk(Path path) {
        try {
            if (path != null && Files.exists(path)) {
                return KomutechJson.asMap(KomutechJson.parse(Files.readString(path, StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> readClasspath(String resource) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(NativeAddonConfig.PLUGIN_NAME);
        if (plugin == null || resource == null || resource.isBlank()) {
            return new LinkedHashMap<>();
        }
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) {
                return new LinkedHashMap<>();
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return KomutechJson.asMap(KomutechJson.parse(text));
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private record MergeResult(Map<String, Object> map, boolean changed) {
    }
}
