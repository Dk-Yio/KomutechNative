package tech.komutech.native_scripts.support;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

public final class AttributeApplier {

    public static final double MAX_REACH_BONUS = 5.0;

    private static final String CRANE_ATTRIBUTE_PLUGIN = "CraneAttribute";

    private static volatile Boolean craneAttributePresent;

    private static final Map<String, AttrMeta> META = Map.of(
        "血量", new AttrMeta("10110305-1003-1204-0823-141408280000", List.of("GENERIC_MAX_HEALTH", "MAX_HEALTH"), false),
        "攻击力", new AttrMeta("10110305-1003-1204-0823-092412040000", List.of("GENERIC_ATTACK_DAMAGE", "ATTACK_DAMAGE"), false),
        "防御力", new AttrMeta("10110305-1003-1204-0823-042100060000", List.of("GENERIC_ARMOR", "ARMOR"), false),
        "速度", new AttrMeta("10110305-1003-1204-0823-210505050000", List.of("GENERIC_MOVEMENT_SPEED", "MOVEMENT_SPEED"), true),
        "灵识", new AttrMeta(null, List.of("BLOCK_INTERACTION_RANGE", "ENTITY_INTERACTION_RANGE"), false,
            List.of("10110305-1003-1204-0823-082317040001", "10110305-1003-1204-0823-082317040002"))
    );

    private AttributeApplier() {
    }

    public static void registerCompatListeners(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent event) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> applyStoredAttributes(event.getPlayer()), 1L);
            }
        }, plugin);
    }

    public static void applyStoredAttributes(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Map<String, Object> data = PlayerAttributeStore.load(player.getName());
        if (data == null) {
            return;
        }
        applyPlayerAttributes(player, data);
        PlayerAttributeStore.save(player.getName(), data);
    }

    /** 对所有在线玩家按当前规则重新应用属性（插件加载后调用，修正溢出残留）。 */
    public static void reapplyAllOnline() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            applyStoredAttributes(online);
        }
    }

    public static boolean isCraneAttributePresent() {
        if (craneAttributePresent == null) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(CRANE_ATTRIBUTE_PLUGIN);
            craneAttributePresent = plugin != null && plugin.isEnabled();
        }
        return craneAttributePresent;
    }

    private static boolean shouldApplyBukkitModifiers() {
        return true;
    }

    public static void clearSwitchable(Player player) {
        for (String attr : CultivationMath.SWITCHABLE_ATTRS) {
            removeModifier(player, attr);
        }
    }

    public static void applyPlayerAttributes(Player player, Map<String, Object> data) {
        applyPlayerAttributes(player, data, true);
    }

    /**
     * @param respectExempt true=尊重管理员豁免（管理员保存）；false=强制截断到配置上限（个人加点/进服同步）。
     */
    public static void applyPlayerAttributes(Player player, Map<String, Object> data, boolean respectExempt) {
        List<String> elements = CultivationMath.splitLinggen(PlayerAttributeStore.getString(data, "灵根", ""));
        double quality = PlayerAttributeStore.getDouble(data, "总品质", 0.01);
        double realmCoeff = CultivationMath.getRealmCoefficient(data);
        double gengu = PlayerAttributeStore.getDouble(data, "根骨", 1);
        for (String attr : CultivationMath.SWITCHABLE_ATTRS) {
            double linggenCoeff = CultivationMath.getLinggenFinalCoefficient(elements, quality, attr);
            double points = PlayerAttributeStore.getDouble(data, attr, 0);
            double value = points == 0 ? 0 : CultivationMath.computeActualValue(
                points, CultivationMath.baseGrowth.getOrDefault(attr, 0.0), linggenCoeff, realmCoeff, quality, gengu
            );
            value = AttributePointLimits.clampForApply(data, attr, value, respectExempt);
            data.put(attr + "_实际", value);
            applyAttribute(player, attr, value, PlayerAttributeStore.getBoolean(data, attr + "_启用", true));
        }
        int spiritGainPoints = PlayerAttributeStore.getInt(data, "灵气获取", 0);
        data.put("灵气获取_实际", AttributePointLimits.spiritGainPercent(data, spiritGainPoints));
    }

    public static void applyAttribute(Player player, String attr, double value, boolean enabled) {
        if (!shouldApplyBukkitModifiers()) {
            removeModifier(player, attr);
            return;
        }
        if (!enabled) {
            removeModifier(player, attr);
            return;
        }
        AttrMeta meta = META.get(attr);
        if (meta == null) {
            return;
        }
        if ("灵识".equals(attr)) {
            double clamped = Math.min(value, MAX_REACH_BONUS);
            for (int i = 0; i < meta.names.size(); i++) {
                AttributeInstance instance = resolveAttribute(player, meta.names.get(i));
                if (instance == null || meta.uuids == null) {
                    continue;
                }
                UUID uuid = UUID.fromString(meta.uuids.get(i));
                removeByUuid(instance, uuid);
                if (clamped > 0) {
                    instance.addModifier(new AttributeModifier(uuid, meta.uuids.get(i), clamped, AttributeModifier.Operation.ADD_NUMBER));
                }
            }
            return;
        }
        AttributeInstance instance = resolveAttribute(player, meta.names);
        if (instance == null || meta.uuid == null) {
            return;
        }
        UUID uuid = UUID.fromString(meta.uuid);
        removeByUuid(instance, uuid);
        double finalValue = value;
        AttributeModifier.Operation operation = AttributeModifier.Operation.ADD_NUMBER;
        if (meta.multiply) {
            operation = AttributeModifier.Operation.MULTIPLY_SCALAR_1;
            finalValue = Math.min(finalValue, 200.0) / 100.0;
        }
        if (finalValue != 0) {
            instance.addModifier(new AttributeModifier(uuid, meta.uuid, finalValue, operation));
        }
    }

    public static void removeModifier(Player player, String attr) {
        AttrMeta meta = META.get(attr);
        if (meta == null) {
            return;
        }
        if ("灵识".equals(attr) && meta.uuids != null) {
            for (int i = 0; i < meta.names.size(); i++) {
                AttributeInstance instance = resolveAttribute(player, meta.names.get(i));
                if (instance != null) {
                    removeByUuid(instance, UUID.fromString(meta.uuids.get(i)));
                }
            }
            return;
        }
        AttributeInstance instance = resolveAttribute(player, meta.names);
        if (instance != null && meta.uuid != null) {
            removeByUuid(instance, UUID.fromString(meta.uuid));
        }
    }

    private static void removeByUuid(AttributeInstance instance, UUID uuid) {
        AttributeModifier existing = instance.getModifier(uuid);
        if (existing != null) {
            instance.removeModifier(existing);
        }
    }

    private static AttributeInstance resolveAttribute(Player player, List<String> names) {
        for (String name : names) {
            AttributeInstance instance = resolveAttribute(player, name);
            if (instance != null) {
                return instance;
            }
        }
        return null;
    }

    private static AttributeInstance resolveAttribute(Player player, String name) {
        try {
            Attribute attribute = Attribute.valueOf(name);
            return player.getAttribute(attribute);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record AttrMeta(String uuid, List<String> names, boolean multiply, List<String> uuids) {
        AttrMeta(String uuid, List<String> names, boolean multiply) {
            this(uuid, names, multiply, null);
        }
    }
}
