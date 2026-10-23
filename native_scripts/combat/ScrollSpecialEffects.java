package tech.komutech.native_scripts.combat;

import tech.komutech.native_scripts.support.EntityQueries;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import tech.origintech.NativeAddonConfig;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.ParticleBudget;

public final class ScrollSpecialEffects {

    /** 持续型卷轴特效调度间隔（tick） */
    private static final long CONTINUOUS_EFFECT_INTERVAL = 2L;

    private static final Color CYAN = Color.fromRGB(0, 255, 255);
    private static final Color GREEN = Color.fromRGB(0, 255, 77);

    private ScrollSpecialEffects() {
    }

    public static void execute(String skillId, Player player, Map<String, Object> attr, double damage,
                               double maxDist, List<Object> whiteListRaw, List<Object> meritRangeRaw) {
        List<String> whiteList = whiteListRaw.stream().map(String::valueOf).toList();
        int meritMin = meritRangeRaw.size() >= 2 ? parseInt(meritRangeRaw.get(0), 1) : 1;
        int meritMax = meritRangeRaw.size() >= 2 ? parseInt(meritRangeRaw.get(1), 10) : 10;
        switch (skillId) {
            case "勾豆灰" -> gouDouHui(player, attr, damage, maxDist);
            case "碎玉闪" -> suiYuShan(player, attr, damage, maxDist, whiteList, meritMin, meritMax);
            case "九霄环佩鸣" -> jiuXiaoHuanPeiMing(player, attr, damage, maxDist, whiteList, meritMin, meritMax);
            case "寒霜锁" -> hanShuangSuo(player, attr, damage, maxDist, whiteList, meritMin, meritMax);
            case "冰火两重天" -> bingHuoLiangChongTian(player, attr, damage, maxDist, whiteList, meritMin, meritMax);
            case "御龙护身决" -> yuLongHuShenJue(player, attr, damage, whiteList, meritMin, meritMax);
            case "游龙惊鸿诀" -> youLongJingHongJue(player, attr, damage, maxDist, whiteList, meritMin, meritMax);
            case "星陨劫" -> xingYunJie(player, attr, damage, maxDist, whiteList, meritMin, meritMax);
            case "五行必杀" -> wuXingBiSha(player, attr, damage, whiteList, meritMin, meritMax);
            default -> defaultRay(player, attr, damage, maxDist);
        }
    }

    private static void gouDouHui(Player player, Map<String, Object> attr, double damage, double maxDist) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        Location start = eye.clone().add(dir);
        ParticleBudget.spawnRayTrail(player.getWorld(), start, dir, maxDist, CYAN, GREEN, 4.0f, 0.3);
        rayHit(player, attr, start, dir, maxDist, damage);
    }

    /** 黄阶：扇形碎玉弹幕，近距多射线（对标勾豆灰的单体射线） */
    private static void suiYuShan(Player player, Map<String, Object> attr, double damage, double maxDist,
                                  List<String> whiteList, int meritMin, int meritMax) {
        var world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        Vector rightRaw = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (rightRaw.lengthSquared() < 0.01) {
            rightRaw = forward.clone().crossProduct(new Vector(1, 0, 0));
        }
        Vector right = rightRaw.normalize();
        Vector up = right.clone().crossProduct(forward).normalize();
        Color crystal = Color.fromRGB(220, 245, 255);
        Color tip = Color.fromRGB(255, 215, 120);
        ScrollCombatHelper.MeritTracker tracker = new ScrollCombatHelper.MeritTracker();
        Set<LivingEntity> damaged = new HashSet<>();
        double range = Math.max(8.0, maxDist);
        int rays = 7;
        for (int r = 0; r < rays; r++) {
            double yawOff = (r - (rays - 1) / 2.0) * 0.18;
            Vector dir = forward.clone()
                .add(right.clone().multiply(Math.sin(yawOff)))
                .add(up.clone().multiply(Math.sin(yawOff * 0.35) * 0.15))
                .normalize();
            for (double d = 0.4; d <= range; d += 0.45) {
                Location pos = eye.clone().add(dir.clone().multiply(d));
                float size = 1.4f + (float) (d / range);
                world.spawnParticle(Particle.DUST, pos, 1, 0, 0, 0, 0,
                    new Particle.DustOptions(gradient(crystal, tip, d / range), size));
                if ((int) (d / 0.45) % 2 == 0) {
                    world.spawnParticle(Particle.END_ROD, pos, 1, 0.02, 0.02, 0.02, 0.01);
                }
                for (LivingEntity entity : EntityQueries.living(world, pos, 1.1)) {
                    if (!damaged.add(entity) || !ScrollCombatHelper.isValidTarget(player, entity)) {
                        continue;
                    }
                    entity.damage(damage, player);
                    ScrollCombatHelper.recordMeritHit(entity, whiteList, tracker);
                }
            }
        }
        ScrollCombatHelper.applyBatchMerit(attr, tracker.whiteHits, tracker.normalHits, meritMin, meritMax);
        KomutechSupport.send(player, "§b✦ §f碎玉飞芒·扇面裂空！");
    }

    /** 玄阶：地面寒霜环扩散锁敌（对标九霄的直线音波） */
    private static void hanShuangSuo(Player player, Map<String, Object> attr, double damage, double maxDist,
                                     List<String> whiteList, int meritMin, int meritMax) {
        Plugin plugin = plugin();
        if (plugin == null) {
            return;
        }
        var world = player.getWorld();
        Location center = player.getLocation().clone().add(0, 0.15, 0);
        Vector look = player.getLocation().getDirection().clone().setY(0);
        if (look.lengthSquared() > 0.01) {
            center.add(look.normalize().multiply(Math.min(4.0, maxDist * 0.25)));
        }
        ScrollCombatHelper.MeritTracker tracker = new ScrollCombatHelper.MeritTracker();
        // 寒霜以控场为主：整次施法对同目标只结算 1 次伤害（系数再压低），避免多段叠成「百分比秒杀」
        final Set<LivingEntity> damaged = new HashSet<>();
        final double hitDamage = damage * 0.55;
        final double maxR = Math.max(10.0, maxDist);
        final int[] tick = {0};
        final int total = 28;
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline() || player.isDead() || player.getWorld() != world) {
                task.cancel();
                return;
            }
            if (tick[0] >= total) {
                task.cancel();
                ScrollCombatHelper.applyBatchMerit(attr, tracker.whiteHits, tracker.normalHits, meritMin, meritMax);
                KomutechSupport.send(player, "§b✦ §3寒霜归寂·锁意消散");
                return;
            }
            double radius = 1.2 + (maxR - 1.2) * (tick[0] / (double) (total - 1));
            int points = Math.max(16, (int) (radius * 6));
            Color iceA = Color.fromRGB(180, 235, 255);
            Color iceB = Color.fromRGB(100, 170, 255);
            for (int i = 0; i < points; i++) {
                double ang = (Math.PI * 2 * i) / points + tick[0] * 0.08;
                Location pos = center.clone().add(Math.cos(ang) * radius, 0.05, Math.sin(ang) * radius);
                world.spawnParticle(Particle.DUST, pos, 1, 0, 0, 0, 0,
                    new Particle.DustOptions(gradient(iceA, iceB, i / (double) points), 1.8f));
                if (i % 3 == 0) {
                    world.spawnParticle(Particle.SNOWFLAKE, pos.clone().add(0, 0.3, 0), 1, 0.05, 0.15, 0.05, 0.01);
                }
            }
            // 内圈补光，保证近处也清晰
            if (tick[0] % 2 == 0) {
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI * 2 * i / 8.0;
                    Location inner = center.clone().add(Math.cos(ang) * (radius * 0.55), 0.2, Math.sin(ang) * (radius * 0.55));
                    world.spawnParticle(Particle.DUST, inner, 1, 0, 0, 0, 0,
                        new Particle.DustOptions(iceA, 1.35f));
                }
            }
            if (tick[0] % 3 == 0) {
                for (LivingEntity entity : EntityQueries.living(world, center, radius + 0.8)) {
                    if (!ScrollCombatHelper.isValidTarget(player, entity)) {
                        continue;
                    }
                    PotionEffectType slowness = PotionEffectType.SLOWNESS;
                    PotionEffectType mining = PotionEffectType.MINING_FATIGUE;
                    if (slowness != null) {
                        entity.addPotionEffect(new PotionEffect(slowness, 80, 2));
                    }
                    if (mining != null) {
                        entity.addPotionEffect(new PotionEffect(mining, 60, 1));
                    }
                    if (!damaged.add(entity)) {
                        continue;
                    }
                    if (!CombatHitGate.damage(player, entity, hitDamage, 20)) {
                        damaged.remove(entity);
                        continue;
                    }
                    ScrollCombatHelper.recordMeritHit(entity, whiteList, tracker);
                }
            }
            tick[0] += 1;
        }, 0L, 1L);
        KomutechSupport.send(player, "§b✦ §3寒霜锁域·步履难移！");
    }

    /** 天阶：锁定攻击范围内目标追踪落星；无目标时环绕散落 */
    private static void xingYunJie(Player player, Map<String, Object> attr, double damage, double maxDist,
                                   List<String> whiteList, int meritMin, int meritMax) {
        Plugin plugin = plugin();
        if (plugin == null) {
            return;
        }
        var world = player.getWorld();
        Location eye = player.getEyeLocation();
        Location playerLoc = player.getLocation();
        double range = Math.max(12.0, maxDist);
        Vector dir = eye.getDirection();
        Location rayStart = eye.clone().add(dir);
        RayTraceResult aimHit = world.rayTrace(rayStart, dir, range, FluidCollisionMode.NEVER, true, 0.4,
            entity -> entity != player && entity instanceof LivingEntity living
                && ScrollCombatHelper.isValidTarget(player, living));
        LivingEntity aimed = aimHit != null && aimHit.getHitEntity() instanceof LivingEntity living ? living : null;

        List<LivingEntity> candidates = new ArrayList<>();
        for (LivingEntity entity : EntityQueries.living(world, playerLoc, range)) {
            if (!ScrollCombatHelper.isValidTarget(player, entity)) {
                continue;
            }
            candidates.add(entity);
        }
        candidates.sort((a, b) -> Double.compare(
            a.getLocation().distanceSquared(playerLoc),
            b.getLocation().distanceSquared(playerLoc)));

        final int meteorCount = 5;
        LivingEntity[] meteorTargets = new LivingEntity[meteorCount];
        Location[] fallbackImpacts = new Location[meteorCount];
        int assigned = 0;
        if (aimed != null) {
            meteorTargets[assigned++] = aimed;
        }
        for (LivingEntity entity : candidates) {
            if (assigned >= meteorCount) {
                break;
            }
            if (entity == aimed) {
                continue;
            }
            meteorTargets[assigned++] = entity;
        }
        // 多余星陨复用已锁定目标；仍无目标则环绕落点
        for (int i = 0; i < meteorCount; i++) {
            if (meteorTargets[i] == null && assigned > 0) {
                meteorTargets[i] = meteorTargets[i % assigned];
            }
            double angle = (Math.PI * 2 * i) / meteorCount + ThreadLocalRandom.current().nextDouble(0.15);
            double radius = 3.5 + (i % 3) * 1.8;
            fallbackImpacts[i] = xingYunFallbackImpact(world, playerLoc,
                Math.cos(angle) * radius, Math.sin(angle) * radius);
        }

        ScrollCombatHelper.MeritTracker tracker = new ScrollCombatHelper.MeritTracker();
        final int[] tick = {0};
        final int fallTicks = 16;
        final int hold = 20;
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            try {
                if (!player.isOnline() || player.isDead() || player.getWorld() != world) {
                    task.cancel();
                    return;
                }
                if (tick[0] >= fallTicks + hold + (meteorCount - 1) * 2) {
                    task.cancel();
                    ScrollCombatHelper.applyBatchMerit(attr, tracker.whiteHits, tracker.normalHits, meritMin, meritMax);
                    KomutechSupport.send(player, "§6✦ §e星火燃尽·天劫消散");
                    return;
                }
                Color starA = Color.fromRGB(255, 230, 120);
                Color starB = Color.fromRGB(255, 80, 20);
                for (int m = 0; m < meteorCount; m++) {
                    int startAt = m * 2;
                    if (tick[0] < startAt) {
                        continue;
                    }
                    int local = tick[0] - startAt;
                    Location impact = resolveXingYunImpact(world, meteorTargets[m], fallbackImpacts[m]);
                    if (local <= fallTicks) {
                        double t = local / (double) fallTicks;
                        // 从落点上方相对高度落下，避免被世界最高方块（屋顶）拉飞
                        Location from = impact.clone().add(0, 14, 0);
                        Location pos = from.clone().add(0, -(14 * t), 0);
                        spawnForcedDust(world, pos, 2, 0.05, 0.05, 0.05,
                            new Particle.DustOptions(gradient(starA, starB, t), 2.4f));
                        spawnForced(world, Particle.FLAME, pos, 2, 0.08, 0.08, 0.08, 0.01);
                        spawnForced(world, Particle.END_ROD, pos, 1, 0.02, 0.02, 0.02, 0.02);
                        if (local == fallTicks) {
                            spawnForced(world, Particle.EXPLOSION, impact, 1, 0, 0, 0, 0);
                            spawnForcedDust(world, impact, 18, 0.7, 0.2, 0.7,
                                new Particle.DustOptions(starB, 2.0f));
                            for (LivingEntity entity : EntityQueries.living(world, impact, 4.0)) {
                                if (!CombatHitGate.damage(player, entity, damage, 6)) {
                                    continue;
                                }
                                entity.setFireTicks(80);
                                ScrollCombatHelper.recordMeritHit(entity, whiteList, tracker);
                            }
                            LivingEntity locked = meteorTargets[m];
                            if (locked != null
                                && locked.getLocation().distanceSquared(impact) <= 8.0 * 8.0
                                && CombatHitGate.damage(player, locked, damage, 6)) {
                                locked.setFireTicks(80);
                                ScrollCombatHelper.recordMeritHit(locked, whiteList, tracker);
                            }
                        }
                    } else if (local % 2 == 0 && local <= fallTicks + hold) {
                        spawnForcedDust(world, impact.clone().add(0, 0.2, 0), 3, 0.35, 0.1, 0.35,
                            new Particle.DustOptions(starB, 1.5f));
                        spawnForced(world, Particle.FLAME, impact, 1, 0.2, 0.05, 0.2, 0.01);
                    }
                }
                tick[0] += 1;
            } catch (Throwable ex) {
                task.cancel();
                plugin.getLogger().warning("[星陨劫] 特效任务异常: " + ex.getMessage());
                ex.printStackTrace();
            }
        }, 0L, 1L);
        KomutechSupport.send(player, assigned > 0
            ? "§6✦ §e星陨天劫·锁定 §f" + assigned + " §e目标！"
            : "§6✦ §e星陨天劫·环天散落！");
    }

    /** 落点跟随目标胸口附近，避免落在脚底被大体型模型挡住 */
    private static Location resolveXingYunImpact(org.bukkit.World world, LivingEntity target, Location fallback) {
        if (target != null && !target.isDead() && target.isValid()) {
            double lift = Math.min(2.2, Math.max(0.8, target.getHeight() * 0.45));
            return target.getLocation().clone().add(0, lift, 0);
        }
        return fallback.clone();
    }

    /** 无目标时：在玩家高度附近向下找地面，避免屋顶/山顶 */
    private static Location xingYunFallbackImpact(org.bukkit.World world, Location playerLoc, double ox, double oz) {
        Location at = playerLoc.clone().add(ox, 0, oz);
        at.setY(groundYNear(world, at.getX(), at.getZ(), playerLoc.getY()));
        return at;
    }

    private static double groundYNear(org.bukkit.World world, double x, double z, double refY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int from = Math.min(world.getMaxHeight() - 1, (int) Math.ceil(refY + 8));
        int to = Math.max(world.getMinHeight(), (int) Math.floor(refY - 32));
        for (int y = from; y >= to; y--) {
            if (world.getBlockAt(bx, y, bz).getType().isSolid()) {
                return y + 1.0;
            }
        }
        return refY;
    }

    private static void spawnForced(org.bukkit.World world, Particle particle, Location loc,
                                    int count, double ox, double oy, double oz, double extra) {
        // FLAME/END_ROD/EXPLOSION 等无 data 粒子：不要传 force 布尔，
        // 否则会命中 (..., T data) 重载，抛 Boolean should be Void 并中断定时任务
        world.spawnParticle(particle, loc, count, ox, oy, oz, extra);
    }

    private static void spawnForcedDust(org.bukkit.World world, Location loc,
                                        int count, double ox, double oy, double oz,
                                        Particle.DustOptions dust) {
        world.spawnParticle(Particle.DUST, loc, count, ox, oy, oz, 0, dust);
    }

    private static void defaultRay(Player player, Map<String, Object> attr, double damage, double maxDist) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        Location start = eye.clone().add(dir);
        ParticleBudget.spawnRayTrail(player.getWorld(), start, dir, maxDist, CYAN, GREEN, 4.0f, 0.3);
        rayHit(player, attr, start, dir, maxDist, damage);
    }

    private static void rayHit(Player player, Map<String, Object> attr, Location start, Vector dir,
                                 double maxDist, double damage) {
        RayTraceResult hit = player.getWorld().rayTrace(start, dir, maxDist, FluidCollisionMode.NEVER, false, 0.1,
            entity -> entity != player && entity instanceof LivingEntity living && !living.getType().name().equals("ARMOR_STAND"));
        if (hit != null && hit.getHitEntity() instanceof LivingEntity target) {
            ScrollCombatHelper.damageTarget(player, attr, target, damage);
        }
    }

    private static void jiuXiaoHuanPeiMing(Player player, Map<String, Object> attr, double damage, double maxDist,
                                            List<String> whiteList, int meritMin, int meritMax) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        Location start = eye.clone().add(dir);
        var world = player.getWorld();
        ScrollCombatHelper.MeritTracker tracker = new ScrollCombatHelper.MeritTracker();
        Set<LivingEntity> damaged = new HashSet<>();
        double step = Math.max(1.0, maxDist / 32.0);
        for (double distance = 0; distance <= maxDist; distance += step) {
            Location pos = start.clone().add(dir.clone().multiply(distance));
            if (ParticleBudget.request(world, 1) > 0) {
                world.spawnParticle(Particle.SONIC_BOOM, pos, 1, 0, 0, 0, 0);
            }
            for (LivingEntity entity : EntityQueries.living(world, pos, 2)) {
                if (!damaged.add(entity) || !ScrollCombatHelper.isValidTarget(player, entity)) {
                    continue;
                }
                entity.damage(damage, player);
                PotionEffectType slowness = PotionEffectType.SLOWNESS;
                PotionEffectType nausea = PotionEffectType.NAUSEA;
                if (slowness != null) {
                    entity.addPotionEffect(new PotionEffect(slowness, 100, 1));
                }
                if (nausea != null) {
                    entity.addPotionEffect(new PotionEffect(nausea, 100, 1));
                }
                ScrollCombatHelper.recordMeritHit(entity, whiteList, tracker);
            }
        }
        ScrollCombatHelper.applyBatchMerit(attr, tracker.whiteHits, tracker.normalHits, meritMin, meritMax);
        KomutechSupport.send(player, "§a九霄环佩，音波激荡！");
    }

    private static void bingHuoLiangChongTian(Player player, Map<String, Object> attr, double damage, double maxDist,
                                                List<String> whiteList, int meritMin, int meritMax) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        Location start = eye.clone().add(dir);
        var world = player.getWorld();
        Vector up = new Vector(0, 1, 0);
        Vector right = dir.clone().crossProduct(up);
        if (right.lengthSquared() < 0.01) {
            right = dir.clone().crossProduct(new Vector(1, 0, 0));
        }
        right.normalize();
        Vector upLocal = right.clone().crossProduct(dir).normalize();
        Color fireStart = Color.fromRGB(255, 140, 0);
        Color fireEnd = Color.fromRGB(255, 69, 0);
        Color snowStart = Color.fromRGB(173, 216, 230);
        Color snowEnd = Color.fromRGB(255, 255, 255);
        ScrollCombatHelper.MeritTracker tracker = new ScrollCombatHelper.MeritTracker();
        Set<LivingEntity> damaged = new HashSet<>();
        // 步长过小会一次打光粒子预算，只剩开头几格可见
        double spiralStep = 0.55;
        for (double distance = 0; distance <= maxDist; distance += spiralStep) {
            Location loc = start.clone().add(dir.clone().multiply(distance));
            double t = distance / maxDist;
            double radius = 0.8 * (0.5 + t * 1.5);
            double angle1 = distance * 0.8;
            double angle2 = distance * 1.5;
            Location firePoint = loc.clone().add(right.clone().multiply(Math.sin(angle1) * radius))
                .add(upLocal.clone().multiply(Math.cos(angle1) * radius));
            Location snowPoint = loc.clone().add(right.clone().multiply(Math.sin(angle1 + Math.PI / 2) * radius * 0.8))
                .add(upLocal.clone().multiply(Math.cos(angle1 + Math.PI / 2) * radius * 0.8));
            Particle.DustTransition fireData = new Particle.DustTransition(fireStart, fireEnd, 2.2f);
            Particle.DustTransition snowData = new Particle.DustTransition(snowStart, snowEnd, 2.2f);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, firePoint, 1, 0.05, 0.05, 0.05, 0, fireData);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, snowPoint, 1, 0.05, 0.05, 0.05, 0, snowData);
            if ((int) (distance / spiralStep) % 2 == 0) {
                world.spawnParticle(Particle.FLAME, loc.clone().add(right.clone().multiply(Math.sin(angle2) * radius * 0.5)), 1, 0.05, 0.05, 0.05, 0.05);
                world.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(upLocal.clone().multiply(Math.cos(angle2 + 0.3) * radius * 0.5)), 1, 0.05, 0.05, 0.05, 0.05);
            }
        }
        for (double distance = 0; distance <= maxDist; distance += 0.5) {
            Location pos = start.clone().add(dir.clone().multiply(distance));
            for (LivingEntity entity : EntityQueries.living(world, pos, 1.5)) {
                if (!damaged.add(entity) || !ScrollCombatHelper.isValidTarget(player, entity)) {
                    continue;
                }
                entity.damage(damage, player);
                entity.setFireTicks(60);
                PotionEffectType slowness = PotionEffectType.SLOWNESS;
                if (slowness != null) {
                    entity.addPotionEffect(new PotionEffect(slowness, 40, 0));
                }
                ScrollCombatHelper.recordMeritHit(entity, whiteList, tracker);
            }
        }
        ScrollCombatHelper.applyBatchMerit(attr, tracker.whiteHits, tracker.normalHits, meritMin, meritMax);
        KomutechSupport.send(player, "§a冰火交融，螺旋激荡！");
    }

    private static void yuLongHuShenJue(Player player, Map<String, Object> attr, double damage,
                                        List<String> whiteList, int meritMin, int meritMax) {
        Plugin plugin = plugin();
        if (plugin == null) {
            return;
        }
        var world = player.getWorld();
        Color[] colors = {
            Color.fromRGB(240, 230, 140),
            Color.fromRGB(255, 215, 0),
            Color.fromRGB(255, 180, 0),
            Color.fromRGB(255, 140, 0)
        };
        ScrollCombatHelper.MeritTracker tracker = new ScrollCombatHelper.MeritTracker();
        final long[] startNanos = {System.nanoTime()};
        final int[] tick = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline() || player.isDead() || player.getWorld() != world) {
                task.cancel();
                return;
            }
            if (tick[0] >= 240) {
                task.cancel();
                ScrollCombatHelper.applyBatchMerit(attr, tracker.whiteHits, tracker.normalHits, meritMin, meritMax);
                KomutechSupport.send(player, "§7龙魂消散");
                return;
            }
            double time = (System.nanoTime() - startNanos[0]) / 1_000_000_000.0;
            Location center = player.getLocation().clone().add(0, 1, 0);
            for (int i = 0; i < 5; i++) {
                double progress = (tick[0] + i * 0.2) / 240.0;
                Location pos = dragonTrailPos(center, progress, time);
                int idx = Math.min(colors.length - 1, Math.max(0, (int) ((pos.getY() - center.getY()) / 3.0 + 1) * 2));
                if (ParticleBudget.request(world, 1) > 0) {
                    world.spawnParticle(Particle.DUST, pos, 1, 0, 0, 0, 0,
                        new Particle.DustOptions(colors[idx], 4.0f));
                }
                // 关键节点不受预算限制，保证御龙圈至少能看见
                if (i == 0 || i == 2) {
                    world.spawnParticle(Particle.DUST, pos, 1, 0, 0, 0, 0,
                        new Particle.DustOptions(colors[idx], 3.0f));
                }
            }
            if (tick[0] % 8 == 0) {
                for (LivingEntity entity : EntityQueries.living(world, center, 4)) {
                    if (!CombatHitGate.damage(player, entity, damage, 8)) {
                        continue;
                    }
                    ScrollCombatHelper.recordMeritHit(entity, whiteList, tracker);
                }
            }
            tick[0] += CONTINUOUS_EFFECT_INTERVAL;
        }, 0L, CONTINUOUS_EFFECT_INTERVAL);
        KomutechSupport.send(player, "§6✦ §e龙魂护体·御敌无形！");
    }

    private static void wuXingBiSha(Player player, Map<String, Object> attr, double damage,
                                    List<String> whiteList, int meritMin, int meritMax) {
        Plugin plugin = plugin();
        if (plugin == null) {
            return;
        }
        var world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        // 与原版 JS 一致：按视线方向纵深直线排布（金→土依次更远）
        final double spawnDistance = 3.5;
        final double yOffset = 0.5;
        final double offsetScale = 0.6;
        final int totalTick = 80;
        final int initDelay = 5;
        final int drawTicks = 25;
        double yaw = -Math.toRadians(eye.getYaw());
        double pitch = Math.toRadians(eye.getPitch());
        final double cosYaw = Math.cos(yaw);
        final double sinYaw = Math.sin(yaw);
        final double cosPitch = Math.cos(pitch);
        final double sinPitch = Math.sin(pitch);
        ElementEffect[] elements = ElementEffect.all();
        Location[] centers = new Location[elements.length];
        for (int i = 0; i < elements.length; i++) {
            double distance = spawnDistance + elements[i].depthOffset * offsetScale;
            centers[i] = eye.clone().add(dir.clone().multiply(distance)).add(0, yOffset, 0);
        }
        ScrollCombatHelper.MeritTracker tracker = new ScrollCombatHelper.MeritTracker();
        final boolean[] charHit = new boolean[elements.length];
        final boolean[] finaleHit = {false};
        final int[] tick = {0};
        // 第 5 字画完时刻；之后再结算第 6 次（五字齐显）
        final int allDrawnTick = (elements.length - 1) * initDelay + drawTicks;
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline() || player.isDead() || player.getWorld() != world) {
                task.cancel();
                return;
            }
            if (tick[0] >= totalTick) {
                task.cancel();
                ScrollCombatHelper.applyBatchMerit(attr, tracker.whiteHits, tracker.normalHits, meritMin, meritMax);
                KomutechSupport.send(player, "§6✦ §e五行归元·化作尘埃");
                return;
            }
            for (int ni = 0; ni < elements.length; ni++) {
                ElementEffect cfg = elements[ni];
                int charElapsed = Math.max(0, tick[0] - ni * initDelay);
                double charProgress = Math.min(1.0, charElapsed / (double) drawTicks);
                int totalPts = cfg.totalPoints();
                int targetPts = (int) Math.floor(totalPts * charProgress);
                int drawnPts = 0;
                for (double[][] stroke : cfg.strokes) {
                    int strokeStart = drawnPts;
                    int strokeEnd = strokeStart + stroke.length;
                    int end = Math.min(stroke.length, Math.max(0, targetPts - strokeStart));
                    for (int i = 0; i < end; i++) {
                        double[] pt = stroke[i];
                        Vector wp = rotateLocal(pt[0], pt[1], 0, cosYaw, sinYaw, cosPitch, sinPitch);
                        Location pos = centers[ni].clone().add(wp);
                        Color color = gradient(cfg.start, cfg.end, i / (double) Math.max(1, stroke.length - 1));
                        world.spawnParticle(Particle.DUST, pos, 1, 0, 0, 0, 0,
                            new Particle.DustOptions(color, 2.0f));
                        if (ThreadLocalRandom.current().nextDouble() < 0.35) {
                            world.spawnParticle(cfg.spark, pos, 1, 0.05, 0.05, 0.05, 0.01);
                        }
                    }
                    drawnPts = strokeEnd;
                    if (strokeEnd > targetPts) {
                        break;
                    }
                }
                // 每完成一字结算一次伤害（共 5 次）
                if (charElapsed >= drawTicks && !charHit[ni]) {
                    charHit[ni] = true;
                    wuXingWaveDamage(world, centers[ni], 2.8, player, damage, whiteList, tracker);
                }
            }
            // 五字齐显时第 6 次，覆盖整段阵位
            if (tick[0] >= allDrawnTick && !finaleHit[0]) {
                finaleHit[0] = true;
                Location mid = centers[centers.length / 2].clone();
                wuXingWaveDamage(world, mid, 5.5, player, damage, whiteList, tracker);
            }
            tick[0] += 1;
        }, 0L, 1L);
    }

    /** 单波 AoE：本波内每目标只打一次，不同波可重复命中 */
    private static void wuXingWaveDamage(org.bukkit.World world, Location center, double radius,
                                         Player player, double damage, List<String> whiteList,
                                         ScrollCombatHelper.MeritTracker tracker) {
        Set<LivingEntity> waveHit = new HashSet<>();
        for (LivingEntity entity : EntityQueries.living(world, center, radius)) {
            if (!waveHit.add(entity) || !ScrollCombatHelper.isValidTarget(player, entity)) {
                continue;
            }
            entity.damage(damage, player);
            ScrollCombatHelper.recordMeritHit(entity, whiteList, tracker);
        }
    }

    /** 原版 JS rotate：本地 x/y 转到面向玩家的平面 */
    private static Vector rotateLocal(double x, double y, double z,
                                      double cosYaw, double sinYaw, double cosPitch, double sinPitch) {
        double y1 = y * cosPitch - z * sinPitch;
        double z1 = y * sinPitch + z * cosPitch;
        return new Vector(x * cosYaw + z1 * sinYaw, y1, -x * sinYaw + z1 * cosYaw);
    }

    private static void youLongJingHongJue(Player player, Map<String, Object> attr, double damage, double maxDist,
                                           List<String> whiteList, int meritMin, int meritMax) {
        Plugin plugin = plugin();
        if (plugin == null) {
            return;
        }
        var world = player.getWorld();
        // 游龙：青蓝三龙前冲追击，与御龙金色护体圈区分
        Color[][] palettes = {
            {Color.fromRGB(64, 224, 208), Color.fromRGB(0, 206, 209), Color.fromRGB(72, 209, 204)},
            {Color.fromRGB(135, 206, 250), Color.fromRGB(30, 144, 255), Color.fromRGB(0, 191, 255)},
            {Color.fromRGB(176, 196, 222), Color.fromRGB(70, 130, 180), Color.fromRGB(100, 149, 237)}
        };
        Location eye = player.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        Vector rightRaw = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (rightRaw.lengthSquared() < 0.01) {
            rightRaw = forward.clone().crossProduct(new Vector(1, 0, 0));
        }
        final Vector right = rightRaw.normalize();
        final Vector up = right.clone().crossProduct(forward).normalize();
        final Vector fwd = forward.clone();
        ScrollCombatHelper.MeritTracker tracker = new ScrollCombatHelper.MeritTracker();
        final int[] tick = {0};
        final double range = Math.max(8.0, maxDist);
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline() || player.isDead() || player.getWorld() != world) {
                task.cancel();
                return;
            }
            if (tick[0] >= 40) {
                task.cancel();
                ScrollCombatHelper.applyBatchMerit(attr, tracker.whiteHits, tracker.normalHits, meritMin, meritMax);
                KomutechSupport.send(player, "§b✦ §3游龙归渊·惊鸿一瞥");
                return;
            }
            double progress = tick[0] / 40.0;
            double distance = progress * range;
            Location base = eye.clone().add(fwd.clone().multiply(distance));
            for (int dragon = 0; dragon < 3; dragon++) {
                double phase = dragon * (Math.PI * 2 / 3.0) + tick[0] * 0.35;
                double radius = 1.2 + 0.4 * Math.sin(tick[0] * 0.2 + dragon);
                Location pos = base.clone()
                    .add(right.clone().multiply(Math.cos(phase) * radius))
                    .add(up.clone().multiply(Math.sin(phase) * radius * 0.7))
                    .add(0, 0.4, 0);
                Color[] palette = palettes[dragon];
                for (int i = 0; i < 4; i++) {
                    double trailBack = i * 0.35;
                    Location trail = pos.clone().subtract(fwd.clone().multiply(trailBack));
                    world.spawnParticle(Particle.DUST, trail, 1, 0, 0, 0, 0,
                        new Particle.DustOptions(palette[i % palette.length], Math.max(1.2f, 2.4f - i * 0.35f)));
                }
                if (tick[0] % 2 == 0) {
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, pos, 1, 0.05, 0.05, 0.05, 0.01);
                }
            }
            if (tick[0] % 2 == 0) {
                for (LivingEntity entity : EntityQueries.living(world, base, 2.8)) {
                    // 持续游走：同目标约每 8tick 可再判定，避免整段只打 1 次
                    if (!CombatHitGate.damage(player, entity, damage, 8)) {
                        continue;
                    }
                    ScrollCombatHelper.recordMeritHit(entity, whiteList, tracker);
                }
            }
            tick[0] += CONTINUOUS_EFFECT_INTERVAL;
        }, 0L, CONTINUOUS_EFFECT_INTERVAL);
        KomutechSupport.send(player, "§b✦ §3三龙惊鸿·破空而去！");
    }

    private static Location dragonTrailPos(Location center, double progress, double time) {
        double base = progress * Math.PI * 8 + time * 0.1;
        double rVar = Math.sin(time * 0.7 + progress * 3) * 0.4 + Math.sin(time * 1.3 + progress * 5) * 0.2;
        double radius = 1 + 3 * (0.5 + rVar * 0.5);
        double hVar = Math.sin(time * 0.5 + progress * 4) * 1.2 + Math.sin(time * 0.85 + progress * 7) * 0.6;
        double height = hVar + 1;
        double offX = Math.sin(time * 0.9 + progress * 6) * 0.2;
        return center.clone().add(Math.cos(base) * radius + offX, height, Math.sin(base) * radius + offX);
    }

    private static Color gradient(Color start, Color end, double t) {
        return Color.fromRGB(
            (int) Math.round(start.getRed() + (end.getRed() - start.getRed()) * t),
            (int) Math.round(start.getGreen() + (end.getGreen() - start.getGreen()) * t),
            (int) Math.round(start.getBlue() + (end.getBlue() - start.getBlue()) * t)
        );
    }

    private static int parseInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Plugin plugin() {
        return Bukkit.getPluginManager().getPlugin(NativeAddonConfig.PLUGIN_NAME);
    }

    private static final class ElementEffect {
        final Particle spark;
        final Color start;
        final Color end;
        final double depthOffset;
        final double[][][] strokes;

        ElementEffect(Particle spark, Color start, Color end, double depthOffset, double[][][] strokes) {
            this.spark = spark;
            this.start = start;
            this.end = end;
            this.depthOffset = depthOffset;
            this.strokes = strokes;
        }

        int totalPoints() {
            int n = 0;
            for (double[][] stroke : strokes) {
                n += stroke.length;
            }
            return n;
        }

        /**
         * 还原原版 JS 笔画（15 网格 + FONT_SIZE=3），顺序：金木水火土。
         * 本地坐标：x = (7.5-gx)*GRID，y = (15-gy)*GRID。
         */
        static ElementEffect[] all() {
            return new ElementEffect[] {
                new ElementEffect(Particle.WAX_OFF, Color.fromRGB(245, 240, 220), Color.fromRGB(255, 215, 0), 0,
                    buildStrokes(
                        line(8, 1, 1, 8), line(8, 1, 15, 8), line(5, 6, 12, 6), line(2, 10, 14, 10),
                        line(8, 7, 8, 14), line(1, 15, 15, 15), line(11, 12, 9, 14), line(6, 12, 7, 14)
                    )),
                new ElementEffect(Particle.SCRAPE, Color.fromRGB(50, 205, 50), Color.fromRGB(34, 139, 34), 3,
                    buildStrokes(
                        line(8, 1, 8, 15), line(1, 5, 15, 5), line(7, 6, 1, 12), line(9, 6, 15, 12)
                    )),
                new ElementEffect(Particle.SOUL_FIRE_FLAME, Color.fromRGB(30, 144, 255), Color.fromRGB(0, 0, 255), 6,
                    buildStrokes(
                        line(8, 1, 8, 15), line(8, 15, 5, 13), line(2, 5, 7, 5),
                        line(7, 5, 2, 11), line(12, 2, 9, 5), line(9, 5, 14, 11)
                    )),
                new ElementEffect(Particle.SMALL_FLAME, Color.fromRGB(255, 69, 0), Color.fromRGB(255, 0, 0), 9,
                    buildStrokes(
                        line(3, 3, 6, 6), line(7, 1, 8, 7), line(8, 7, 1, 15),
                        line(13, 3, 10, 6), line(9, 8, 15, 15)
                    )),
                new ElementEffect(Particle.WAX_ON, Color.fromRGB(210, 180, 140), Color.fromRGB(139, 69, 19), 12,
                    buildStrokes(
                        line(2, 7, 14, 7), line(1, 15, 15, 15), line(8, 1, 8, 15)
                    ))
            };
        }

        private static double[][] line(double x1, double y1, double x2, double y2) {
            final double grid = 3.0 / 15.0;
            double[][] pts = new double[9][2];
            for (int i = 0; i <= 8; i++) {
                double t = i / 8.0;
                double gx = x1 + (x2 - x1) * t;
                double gy = y1 + (y2 - y1) * t;
                pts[i][0] = (7.5 - gx) * grid;
                pts[i][1] = (15.0 - gy) * grid;
            }
            return pts;
        }

        @SafeVarargs
        private static double[][][] buildStrokes(double[][]... strokes) {
            return strokes;
        }
    }
}
