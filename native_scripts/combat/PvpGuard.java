package tech.komutech.native_scripts.combat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import tech.komutech.native_scripts.support.KomutechJson;
import tech.komutech.native_scripts.support.KomutechPaths;

/**
 * 灵系 PVP：名单内玩家为「关闭灵系 PVP」；并对玩家目标探测保护插件（领地/WG 等）。
 */
public final class PvpGuard {

    private static final long CACHE_MS = 5_000L;
    private static volatile Set<String> disabledCache = Set.of();
    private static volatile long cachedAt;

    private PvpGuard() {
    }

    /** 与旧 JS 一致：名单内 = 关闭灵系 PVP */
    public static boolean isSpiritPvpEnabled(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        return !disabledNames().contains(playerName);
    }

    public static boolean canHarm(Player attacker, LivingEntity target) {
        if (attacker == null || target == null || target == attacker) {
            return false;
        }
        if (!(target instanceof Player victim)) {
            return true;
        }
        if (!attacker.getWorld().getPVP()) {
            return false;
        }
        if (!isSpiritPvpEnabled(attacker.getName()) || !isSpiritPvpEnabled(victim.getName())) {
            return false;
        }
        return !isProtectedByOtherPlugins(attacker, victim);
    }

    @SuppressWarnings("deprecation")
    private static boolean isProtectedByOtherPlugins(Player attacker, Player victim) {
        try {
            EntityDamageByEntityEvent probe = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 0.01D);
            Bukkit.getPluginManager().callEvent(probe);
            return probe.isCancelled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Set<String> disabledNames() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MS) {
            return disabledCache;
        }
        Set<String> loaded = loadDisabled();
        disabledCache = loaded;
        cachedAt = now;
        return loaded;
    }

    private static Set<String> loadDisabled() {
        Path path = KomutechPaths.configRoot().toPath().resolve("灵PVP列表.json");
        try {
            if (!Files.exists(path)) {
                return Set.of();
            }
            Object parsed = KomutechJson.parse(Files.readString(path, StandardCharsets.UTF_8));
            Set<String> out = new HashSet<>();
            if (parsed instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        out.add(String.valueOf(item));
                    }
                }
            } else if (parsed instanceof Map<?, ?> map) {
                for (Object key : map.keySet()) {
                    if (key != null) {
                        out.add(String.valueOf(key));
                    }
                }
            }
            return Collections.unmodifiableSet(out);
        } catch (Exception ignored) {
            return Set.of();
        }
    }
}
