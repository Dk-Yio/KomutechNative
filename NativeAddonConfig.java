package tech.komutech;

public final class NativeAddonConfig {

    public static final String PLUGIN_NAME = "KomutechNative";
    public static final String ADDON_ID = "Komutech";
    public static final String COMMAND_NAME = "komutech";
    public static final String COMMAND_PERMISSION = "komutech.admin";
    public static final String ADDON_FOLDER = "Komutech";
    /** RSC addons 目录中的原始文件夹名（共存时需禁用） */
    public static final String RSC_SOURCE_FOLDER = "【口木科技】Komutech-0621";
    public static final String PLUGIN_DISPLAY_NAME = "口木科技";
    public static final String VERSION = "Release-3.0.1";
    public static final String RSC_DISABLED_SUFFIX = ".native-disabled";
    public static final String RSC_DEPENDENCY_STUB_FOLDER = "KomutechNative-DependencyStub";
    public static final String BUG_TRACKER_URL = "";

    public static final String[] REQUIRED_PLUGIN_DEPENDS = {};
    public static final String[] WAIT_IF_INSTALLED_PLUGIN_DEPENDS = {
        "Networks", "SlimeAEPlugin", "SlimeAE", "LogiTech"
    };
    public static final String[] RECOMMENDED_PLUGIN_DEPENDS = {};
    public static final int ADDON_LOAD_MAX_ATTEMPTS = 600;
    public static final boolean AUTO_RELOAD_WHEN_DEPS_READY = true;
    public static final boolean ENABLE_WORLD_GENERATION = false;
    public static final boolean ENABLE_SAVED_ITEMS_GUIDE = false;
    public static final boolean CONFIGURABLE_AUTO_UPDATE = false;

    private NativeAddonConfig() {
    }
}
