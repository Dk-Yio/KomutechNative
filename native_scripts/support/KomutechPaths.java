package tech.komutech.native_scripts.support;

import java.io.File;
import java.nio.file.Path;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import tech.origintech.NativeAddonConfig;

public final class KomutechPaths {

    private KomutechPaths() {
    }

    public static File configRoot() {
        File legacy = new File("plugins/RykenSlimefunCustomizer/addon_configs/" + NativeAddonConfig.ADDON_ID);
        if (legacy.isDirectory()) {
            return legacy;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin(NativeAddonConfig.PLUGIN_NAME);
        if (plugin != null) {
            File nativeRoot = new File(plugin.getDataFolder(), "addon_configs/" + NativeAddonConfig.ADDON_ID);
            if (!nativeRoot.exists()) {
                nativeRoot.mkdirs();
            }
            return nativeRoot;
        }
        File fallback = new File("plugins/" + NativeAddonConfig.PLUGIN_NAME + "/addon_configs/" + NativeAddonConfig.ADDON_ID);
        if (!fallback.exists()) {
            fallback.mkdirs();
        }
        return fallback;
    }

    public static Path playerAttributes() {
        return configRoot().toPath().resolve("玩家属性");
    }

    public static Path wanXiangGui() {
        return configRoot().toPath().resolve("萬象匱");
    }

    public static Path yunZhuanXia() {
        return configRoot().toPath().resolve("云篆匣");
    }

    public static Path naJie() {
        return configRoot().toPath().resolve("纳戒");
    }

    public static Path scrollConfig() {
        return configRoot().toPath().resolve("卷轴属性.json");
    }

    public static Path staffConfig() {
        return configRoot().toPath().resolve("灵杖属性.json");
    }

    public static Path puTuanConfig() {
        return configRoot().toPath().resolve("蒲团配置.json");
    }

    public static Path attributePointLimits() {
        return configRoot().toPath().resolve("属性加点限制.json");
    }
}
