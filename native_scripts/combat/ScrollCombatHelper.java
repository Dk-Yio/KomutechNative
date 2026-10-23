package tech.komutech.native_scripts.combat;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import tech.komutech.native_scripts.support.EntityQueries;

public final class ScrollCombatHelper {

    private ScrollCombatHelper() {
    }

    public static boolean isValidTarget(Player attacker, LivingEntity entity) {
        if (entity == null || entity == attacker || entity.getType().name().equals("ARMOR_STAND")
            || entity.isDead() || !entity.isValid()) {
            return false;
        }
        // 玩家目标：灵系 PVP 名单 + 世界/领地保护探测
        return PvpGuard.canHarm(attacker, entity);
    }

    public static void damageTarget(Player attacker, Map<String, Object> attr, LivingEntity target, double damage) {
        if (!isValidTarget(attacker, target)) {
            return;
        }
        target.damage(damage, attacker);
        if (!(target instanceof Player)) {
            ScrollCombatEngine.meritChangeDelta(attr, target.getType().name());
        }
    }

    public static void damageAlongRay(Player attacker, Map<String, Object> attr, Location start, Vector dir,
                                      double maxDist, double detectRadius, double damage) {
        Set<LivingEntity> hit = new HashSet<>();
        int steps = Math.max(1, (int) Math.ceil(maxDist / Math.max(0.5, detectRadius)));
        double step = maxDist / steps;
        for (int i = 0; i <= steps; i++) {
            Location pos = start.clone().add(dir.clone().multiply(step * i));
            collectAndDamage(attacker, attr, hit, pos, detectRadius, damage);
        }
    }

    public static void damageInRadius(Player attacker, Map<String, Object> attr, Location center,
                                      double radius, double damage) {
        Set<LivingEntity> hit = new HashSet<>();
        collectAndDamage(attacker, attr, hit, center, radius, damage);
    }

    private static void collectAndDamage(Player attacker, Map<String, Object> attr, Set<LivingEntity> hit,
                                           Location center, double radius, double damage) {
        Collection<LivingEntity> nearby = EntityQueries.living(center, radius);
        for (LivingEntity entity : nearby) {
            if (!hit.add(entity)) {
                continue;
            }
            damageTarget(attacker, attr, entity, damage);
        }
    }

    public static void applyBatchMerit(Map<String, Object> attr, int whiteHits, int normalHits,
                                       int minChange, int maxChange) {
        int change = 0;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (whiteHits > 0) {
            change -= whiteHits * random.nextInt(minChange, maxChange + 1);
        }
        if (normalHits > 0) {
            change += normalHits * random.nextInt(minChange, maxChange + 1);
        }
        if (change == 0) {
            return;
        }
        int merit = ScrollCombatEngine.getIntAttr(attr, "功德", 0) + change;
        attr.put("功德", Math.max(0, merit));
        if (change < 0) {
            attr.put("煞气", ScrollCombatEngine.getIntAttr(attr, "煞气", 0) + Math.abs(change));
        }
    }

    public static int recordMeritHit(LivingEntity entity, List<String> whiteList, MeritTracker tracker) {
        if (entity instanceof Player) {
            return 0;
        }
        if (whiteList.contains(entity.getType().name())) {
            tracker.whiteHits++;
        } else {
            tracker.normalHits++;
        }
        return 1;
    }

    public static final class MeritTracker {
        int whiteHits;
        int normalHits;
    }
}
