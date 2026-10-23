package tech.komutech.native_scripts.combat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * 同攻击者对同目标的伤害判定冷却，避免多瓣/多段特效在同一 tick 窗口内爆伤。
 */
public final class CombatHitGate {

    private static final Map<String, Long> READY_AT_MS = new ConcurrentHashMap<>();

    private CombatHitGate() {
    }

    public static boolean tryAllow(Player attacker, LivingEntity target, int cooldownTicks) {
        if (attacker == null || !ScrollCombatHelper.isValidTarget(attacker, target)) {
            return false;
        }
        int cd = Math.max(1, cooldownTicks);
        String key = key(attacker.getUniqueId(), target.getUniqueId());
        long now = System.currentTimeMillis();
        Long ready = READY_AT_MS.get(key);
        if (ready != null && now < ready) {
            return false;
        }
        READY_AT_MS.put(key, now + cd * 50L);
        return true;
    }

    public static boolean damage(Player attacker, LivingEntity target, double amount, int cooldownTicks) {
        if (!tryAllow(attacker, target, cooldownTicks)) {
            return false;
        }
        target.damage(amount, attacker);
        return true;
    }

    private static String key(UUID attacker, UUID target) {
        return attacker + ":" + target;
    }
}
