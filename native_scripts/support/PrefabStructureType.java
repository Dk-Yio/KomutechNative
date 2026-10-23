package tech.komutech.native_scripts.support;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;

public enum PrefabStructureType {

    JIAN_LING_SHI("KOMUTECH_L_GL_预制鉴灵石"),
    ULTIMATE_SYNTH_CORE("KOMUTECH_L_GL_预制终极合成台核心"),
    MB_CRAFTSMAN("KOMUTECH_L_GL_预制工匠台"),
    MB_SPIRIT_MINE("KOMUTECH_L_GL_预制灵矿提取台"),
    MB_SPIRIT_INFUSE("KOMUTECH_L_GL_预制注灵台");

    private final String itemId;

    PrefabStructureType(String itemId) {
        this.itemId = itemId;
    }

    public String itemId() {
        return itemId;
    }

    public static PrefabStructureType fromItem(SlimefunItem item) {
        if (item == null) {
            return null;
        }
        for (PrefabStructureType type : values()) {
            if (type.itemId.equals(item.getId())) {
                return type;
            }
        }
        return null;
    }
}
