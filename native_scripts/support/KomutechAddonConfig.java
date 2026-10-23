package tech.komutech.native_scripts.support;

import java.io.File;
import java.util.Collections;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

public final class KomutechAddonConfig {

    private static File configFile;
    private static YamlConfiguration config;
    private static long lastModified;

    private KomutechAddonConfig() {
    }

    private static YamlConfiguration yaml() {
        File file = new File(KomutechPaths.configRoot(), "config.yml");
        long modified = file.exists() ? file.lastModified() : 0L;
        if (config == null || configFile == null || modified != lastModified || !file.equals(configFile)) {
            configFile = file;
            config = YamlConfiguration.loadConfiguration(file);
            lastModified = modified;
        }
        return config;
    }

    public static int getInt(String key, int defaultValue) {
        return yaml().getInt(key, defaultValue);
    }

    public static String getString(String key, String defaultValue) {
        String value = yaml().getString(key, defaultValue);
        return value == null ? defaultValue : value;
    }

    public static List<String> getStringList(String key) {
        List<String> list = yaml().getStringList(key);
        return list == null ? Collections.emptyList() : list;
    }
}
