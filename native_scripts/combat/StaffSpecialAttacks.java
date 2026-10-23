package tech.komutech.native_scripts.combat;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import tech.origintech.NativeAddonConfig;
import tech.komutech.native_scripts.support.EntityQueries;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.ParticleBudget;

public final class StaffSpecialAttacks {

    private static final double FYM_PROJECTILE_SPEED = 1.6;
    private static final double FYM_SPLIT_RATIO = 0.5;
    private static final double FYM_SUB_SPEED = 1.2;
    private static final double FYM_SUB_ANGLE = 0.26;
    private static final int FYM_RESIDUE_RADIUS_TICKS = 30;
    private static final double FYM_RESIDUE_RADIUS = 2.5;
    private static final double FYM_RESIDUE_DAMAGE_RATIO = 0.2;
    private static final int FYM_BLIND_DURATION = 20;

    private static final double KYJ_SPHERE_RADIUS = 1.5;
    private static final int KYJ_SPHERE_STEPS = 12;
    private static final int KYJ_SPHERE_INTERVAL = 1;
    private static final int KYJ_AREA_DAMAGE_INTERVAL = 2;
    private static final double KYJ_CIRCLE_RADIUS = 6.0;
    private static final double KYJ_CIRCLE_HEIGHT = 1.0;
    private static final int KYJ_CIRCLE_DAMAGE_INTERVAL = 10;

    /** 归墟：短蓄力 + 单次坍缩，每目标仅结算一次伤害 */
    private static final int GX_CHARGE_TICKS = 8;
    private static final double GX_BURST_RADIUS = 5.5;
    private static final double GX_PULL_STRENGTH = 0.22;
    private static final int GX_RING_POINTS = 10;
    private static final Color GX_VOID = Color.fromRGB(72, 32, 120);
    private static final Color GX_STAR = Color.fromRGB(230, 220, 255);
    private static final Color GX_CORE = Color.fromRGB(180, 140, 255);

    private StaffSpecialAttacks() {
    }

    public static void guiXu(Player player, ItemStack staff, Map<String, Object> attr) {
        double maxDist = ScrollCombatEngine.staffMaxDistance("归墟");
        double damage = ScrollCombatEngine.calcStaffDamage(player, staff, attr);
        var world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        // tryStaffAttack 已扣冷却/灵力：禁止静默 return，方向异常时兜底朝前
        if (dir.lengthSquared() < 1.0E-6) {
            dir = eye.getDirection().setY(0);
            if (dir.lengthSquared() < 1.0E-6) {
                dir = new Vector(0, 0, 1);
            }
        }
        dir.normalize();
        Location start = eye.clone().add(dir);
        RayTraceResult hit = world.rayTrace(start, dir, maxDist, FluidCollisionMode.NEVER, true, 0.35,
            entity -> entity != player && entity instanceof LivingEntity
                && !entity.getType().name().equals("ARMOR_STAND") && !entity.isDead());
        Location center;
        if (hit != null && hit.getHitPosition() != null) {
            center = hit.getHitPosition().toLocation(world).add(0, 0.35, 0);
        } else {
            center = start.clone().add(dir.clone().multiply(maxDist));
        }
        spawnGuiXuAimBeam(world, start, center);
        world.playSound(center, "block.respawn_anchor.charge", 0.55f, 1.35f);
        Plugin plugin = plugin();
        if (plugin == null) {
            // 无调度器时仍保证单次坍缩结算，避免白扣灵力
            collapseGuiXu(world, center, player, attr, damage);
            return;
        }
        final int[] tick = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline() || player.isDead() || player.getWorld() != world) {
                task.cancel();
                return;
            }
            if (tick[0] < GX_CHARGE_TICKS) {
                spawnGuiXuChargeRing(world, center, tick[0]);
                tick[0]++;
                return;
            }
            task.cancel();
            collapseGuiXu(world, center, player, attr, damage);
        }, 0L, 1L);
    }

    private static void spawnGuiXuAimBeam(org.bukkit.World world, Location start, Location end) {
        Vector delta = end.toVector().subtract(start.toVector());
        double dist = delta.length();
        if (dist < 0.2) {
            return;
        }
        Vector step = delta.normalize().multiply(0.55);
        Location cursor = start.clone();
        int points = Math.min(48, (int) Math.ceil(dist / 0.55));
        Particle.DustOptions dust = new Particle.DustOptions(GX_CORE, 0.9f);
        for (int i = 0; i < points; i++) {
            cursor.add(step);
            if (ParticleBudget.request(world, 1) <= 0) {
                break;
            }
            world.spawnParticle(Particle.DUST, cursor, 1, 0, 0, 0, 0, dust);
            if (i % 3 == 0) {
                ParticleBudget.spawnEndRod(world, cursor, 0, 0, 0, 0, 1);
            }
        }
    }

    private static void spawnGuiXuChargeRing(org.bukkit.World world, Location center, int tick) {
        double progress = tick / (double) Math.max(1, GX_CHARGE_TICKS - 1);
        // 外环收缩 → 清晰可读，点数固定，走预算
        double radius = GX_BURST_RADIUS * (1.0 - progress * 0.75);
        int allowed = ParticleBudget.request(world, GX_RING_POINTS);
        if (allowed <= 0) {
            return;
        }
        Particle.DustOptions outer = new Particle.DustOptions(GX_VOID, 1.15f);
        Particle.DustOptions inner = new Particle.DustOptions(GX_STAR, 0.85f);
        for (int i = 0; i < allowed; i++) {
            double angle = (Math.PI * 2 * i) / allowed + tick * 0.18;
            double x = center.getX() + Math.cos(angle) * radius;
            double z = center.getZ() + Math.sin(angle) * radius;
            double y = center.getY() + Math.sin(tick * 0.4 + i) * 0.15;
            world.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, outer);
            if (i % 2 == 0) {
                world.spawnParticle(Particle.DUST, x, y + 0.25, z, 1, 0, 0, 0, 0, inner);
            }
        }
        if (tick % 2 == 0) {
            ParticleBudget.spawnEndRod(world, center, 0.12, 0.2, 0.12, 0.01, 1);
        }
    }

    private static void collapseGuiXu(org.bukkit.World world, Location center, Player player,
                                      Map<String, Object> attr, double damage) {
        Set<UUID> hitOnce = new HashSet<>();
        int totalChange = 0;
        int hitCount = 0;
        for (LivingEntity entity : EntityQueries.living(world, center, GX_BURST_RADIUS)) {
            if (entity == player || entity.getType().name().equals("ARMOR_STAND") || entity.isDead()) {
                continue;
            }
            if (!hitOnce.add(entity.getUniqueId())) {
                continue;
            }
            hitCount++;
            entity.damage(damage, player);
            if (!(entity instanceof Player)) {
                totalChange += applyMeritChange(attr, entity.getType().name());
            }
        }
        safePull(world, center, player, GX_PULL_STRENGTH, GX_BURST_RADIUS);
        spawnGuiXuCollapseBurst(world, center);
        world.playSound(center, "block.respawn_anchor.deplete", 0.8f, 0.7f);
        world.playSound(center, "entity.warden.sonic_boom", 0.35f, 1.6f);
        if (hitCount > 0) {
            KomutechSupport.send(player, "§5归墟坍缩 §7· §f命中 §d" + hitCount + " §f目标");
        } else {
            KomutechSupport.send(player, "§5归墟坍缩 §7· §8虚空无物");
        }
        if (totalChange != 0) {
            KomutechSupport.send(player, totalChange > 0
                ? "§2✦ §a[归墟] 积德行善！§6+" + totalChange + "功德"
                : "§4✧ §c[归墟] 损德败行！§6" + totalChange + "功德");
        }
    }

    private static void spawnGuiXuCollapseBurst(org.bukkit.World world, Location center) {
        int ring = ParticleBudget.request(world, 16);
        Particle.DustOptions burst = new Particle.DustOptions(GX_CORE, 1.4f);
        Particle.DustOptions rim = new Particle.DustOptions(GX_STAR, 1.1f);
        for (int i = 0; i < ring; i++) {
            double angle = (Math.PI * 2 * i) / Math.max(1, ring);
            double r = GX_BURST_RADIUS * 0.85;
            Location point = new Location(world,
                center.getX() + Math.cos(angle) * r,
                center.getY() + 0.2,
                center.getZ() + Math.sin(angle) * r);
            world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, burst);
            if (i % 2 == 0) {
                world.spawnParticle(Particle.DUST, point.clone().add(0, 0.45, 0), 1, 0, 0, 0, 0, rim);
            }
        }
        ParticleBudget.spawnEndRod(world, center, 0.35, 0.55, 0.35, 0.04, 8);
        // 竖直残影：少量定点，避免刷屏
        for (int h = 0; h < 4; h++) {
            Location pillar = center.clone().add(0, h * 0.55, 0);
            ParticleBudget.spawnDust(world, pillar, new Particle.DustOptions(GX_VOID, 1.2f), 1);
        }
    }

    public static void fuyouMeng(Player player, ItemStack staff, Map<String, Object> attr) {
        double maxDist = ScrollCombatEngine.staffMaxDistance("蜉蝣梦");
        double damage = ScrollCombatEngine.calcStaffDamage(player, staff, attr);
        Color color = Color.fromRGB(176, 224, 230);
        Color endColor = Color.fromRGB(255, 255, 255);
        launchMainProjectile(player, attr, damage, maxDist, color, endColor);
    }

    public static void kongYinJin(Player player, ItemStack staff, Map<String, Object> attr) {
        double maxDist = ScrollCombatEngine.staffMaxDistance("空引津");
        double damage = ScrollCombatEngine.calcStaffDamage(player, staff, attr);
        var world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        Location start = eye.clone().add(dir);
        double stepDist = maxDist / KYJ_SPHERE_STEPS;
        Plugin plugin = plugin();
        if (plugin == null) {
            return;
        }
        final int[] step = {0};
        final int[] circleTick = {0};
        final int[] totalChange = {0};
        final boolean[] firstHit = {false};
        int circleLoops = Math.max(1, KYJ_CIRCLE_DAMAGE_INTERVAL / KYJ_SPHERE_INTERVAL);
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline() || player.isDead() || player.getWorld() != world || step[0] >= KYJ_SPHERE_STEPS) {
                task.cancel();
                if (!firstHit[0]) {
                    Location finalLoc = start.clone().add(dir.clone().multiply(stepDist * step[0]));
                    totalChange[0] += noteExplosion(world, finalLoc, player, attr, damage, 0.5);
                }
                if (totalChange[0] != 0) {
                    KomutechSupport.send(player, totalChange[0] > 0
                        ? "§2✦ §a[空引津] 积德行善！§6+" + totalChange[0] + "功德"
                        : "§4✧ §c[空引津] 损德败行！§6" + totalChange[0] + "功德");
                }
                return;
            }
            Location sphereCenter = start.clone().add(dir.clone().multiply(stepDist * step[0]));
            spawnSphereParticles(world, sphereCenter);
            spawnCircleParticles(world, player.getLocation());
            if (step[0] % KYJ_AREA_DAMAGE_INTERVAL == 0) {
                Collection<LivingEntity> nearby = EntityQueries.living(world, sphereCenter, KYJ_SPHERE_RADIUS);
                LivingEntity hitEntity = null;
                for (LivingEntity entity : nearby) {
                    if (entity == player || entity.getType().name().equals("ARMOR_STAND") || entity.isDead()) {
                        continue;
                    }
                    if (hitEntity == null) {
                        hitEntity = entity;
                    }
                    totalChange[0] += processEntityDamage(entity, player, damage, attr);
                }
                if (hitEntity != null && !firstHit[0]) {
                    firstHit[0] = true;
                    totalChange[0] += noteExplosion(world, hitEntity.getLocation(), player, attr, damage, 0.6);
                }
            }
            circleTick[0]++;
            if (circleTick[0] >= circleLoops) {
                circleTick[0] = 0;
                totalChange[0] += applyAreaDamage(world, player.getLocation(), player, damage,
                    KYJ_CIRCLE_RADIUS, KYJ_CIRCLE_HEIGHT, KYJ_CIRCLE_RADIUS, attr);
            }
            step[0]++;
        }, 0L, KYJ_SPHERE_INTERVAL);
    }

    private static void launchMainProjectile(Player player, Map<String, Object> attr, double damage,
                                             double maxDist, Color color, Color endColor) {
        var world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        Location start = eye.clone().add(dir);
        Plugin plugin = plugin();
        if (plugin == null) {
            return;
        }
        final double[] traveled = {0};
        final boolean[] splitTriggered = {false};
        final int[] sparkTick = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (traveled[0] >= maxDist || !player.isOnline() || player.isDead() || player.getWorld() != world) {
                task.cancel();
                if (traveled[0] >= maxDist) {
                    Location finalLoc = start.clone().add(dir.clone().multiply(traveled[0]));
                    hitBurst(world, finalLoc, color);
                    spawnResidue(world, finalLoc, player, attr, damage);
                }
                return;
            }
            traveled[0] += FYM_PROJECTILE_SPEED;
            Location current = start.clone().add(dir.clone().multiply(traveled[0]));
            spawnProjectileTrail(world, current, color, endColor);
            sparkTick[0]++;
            if (sparkTick[0] % 3 == 0) {
                ParticleBudget.spawnDust(world, current, new Particle.DustOptions(color, 1.2f), 1);
            }
            if (!splitTriggered[0] && traveled[0] >= maxDist * FYM_SPLIT_RATIO) {
                splitTriggered[0] = true;
                Vector right = dir.clone().crossProduct(new Vector(0, 1, 0)).normalize();
                launchSubProjectile(world, current, dir.clone().add(right.clone().multiply(FYM_SUB_ANGLE)).normalize(),
                    player, attr, damage, maxDist, color);
                launchSubProjectile(world, current, dir.clone().add(right.clone().multiply(-FYM_SUB_ANGLE)).normalize(),
                    player, attr, damage, maxDist, color);
                launchSubProjectile(world, current, dir.clone(), player, attr, damage, maxDist, color);
            }
            for (LivingEntity entity : EntityQueries.living(world, current, 1)) {
                if (entity == player || entity.getType().name().equals("ARMOR_STAND") || entity.isDead()) {
                    continue;
                }
                task.cancel();
                hitBurst(world, current, color);
                int change = damageEntity(entity, player, damage, attr);
                if (!(entity instanceof Player)) {
                    KomutechSupport.send(player, change < 0
                        ? "§c攻击§6" + entity.getType().name() + "§c，煞气+" + Math.abs(change) + "§c！"
                        : "§a攻击§6" + entity.getType().name() + "§a，功德+" + change + "§a！");
                }
                spawnResidue(world, current, player, attr, damage);
                return;
            }
        }, 0L, 1L);
    }

    private static void launchSubProjectile(org.bukkit.World world, Location start, Vector dir, Player player,
                                            Map<String, Object> attr, double damage, double maxDist, Color color) {
        Plugin plugin = plugin();
        if (plugin == null) {
            return;
        }
        final double[] traveled = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (traveled[0] >= maxDist * 0.7 || !player.isOnline() || player.getWorld() != world) {
                task.cancel();
                return;
            }
            traveled[0] += FYM_SUB_SPEED;
            Location current = start.clone().add(dir.clone().multiply(traveled[0]));
            ParticleBudget.spawnDust(world, current, new Particle.DustOptions(color, 0.8f), 3);
            for (LivingEntity entity : EntityQueries.living(world, current, 0.8)) {
                if (entity == player || entity.getType().name().equals("ARMOR_STAND") || entity.isDead()) {
                    continue;
                }
                damageEntity(entity, player, damage * 0.6, attr);
                task.cancel();
                hitBurst(world, current, color);
                return;
            }
        }, 0L, 1L);
    }

    private static void spawnProjectileTrail(org.bukkit.World world, Location location, Color color, Color endColor) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 3; i++) {
            double off = (random.nextDouble() - 0.5) * 0.5;
            Location point = location.clone().add(off, off, off);
            world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, new Particle.DustOptions(color, 1.3f));
        }
        world.spawnParticle(Particle.DUST, location, 1, 0, 0, 0, 0, new Particle.DustOptions(endColor, 0.7f));
        world.spawnParticle(Particle.END_ROD, location, 1, 0.02, 0.02, 0.02, 0.01);
    }

    private static void hitBurst(org.bukkit.World world, Location location, Color color) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int count = ParticleBudget.request(world, 12);
        for (int i = 0; i < count; i++) {
            double ox = (random.nextDouble() - 0.5) * 2.0;
            double oy = (random.nextDouble() - 0.5) * 2.0;
            double oz = (random.nextDouble() - 0.5) * 2.0;
            world.spawnParticle(Particle.DUST, location.getX() + ox, location.getY() + oy, location.getZ() + oz,
                1, 0, 0, 0, 0, new Particle.DustOptions(color, 1.5f));
        }
    }

    private static void spawnResidue(org.bukkit.World world, Location center, Player player,
                                     Map<String, Object> attr, double damage) {
        Color residueColor = Color.fromRGB(230, 230, 250);
        Plugin plugin = plugin();
        if (plugin == null) {
            return;
        }
        final int[] tick = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (tick[0] >= FYM_RESIDUE_RADIUS_TICKS) {
                task.cancel();
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int particles = ParticleBudget.request(world, 4);
            for (int i = 0; i < particles; i++) {
                double angle = random.nextDouble(Math.PI * 2);
                double radius = random.nextDouble() * FYM_RESIDUE_RADIUS;
                Location point = center.clone().add(Math.cos(angle) * radius, random.nextDouble() * 0.5, Math.sin(angle) * radius);
                ParticleBudget.spawnDust(world, point, new Particle.DustOptions(residueColor, 1.0f), 1);
            }
            if (tick[0] % 5 == 0) {
                for (LivingEntity entity : EntityQueries.living(world, center, FYM_RESIDUE_RADIUS)) {
                    if (entity == player || entity.getType().name().equals("ARMOR_STAND") || entity.isDead()) {
                        continue;
                    }
                    damageEntity(entity, player, damage * FYM_RESIDUE_DAMAGE_RATIO, attr);
                }
            }
            tick[0]++;
        }, 0L, 1L);
    }

    private static void spawnSphereParticles(org.bukkit.World world, Location center) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Particle.DustOptions noteDust = new Particle.DustOptions(Color.fromRGB(180, 255, 120), 1.4f);
        for (int i = 0; i < 8; i++) {
            double theta = random.nextDouble(Math.PI * 2);
            double phi = random.nextDouble(Math.PI);
            double radius = KYJ_SPHERE_RADIUS * (0.8 + random.nextDouble() * 0.2);
            Location point = new Location(world,
                center.getX() + radius * Math.sin(phi) * Math.cos(theta),
                center.getY() + radius * Math.sin(phi) * Math.sin(theta),
                center.getZ() + radius * Math.cos(phi));
            world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, noteDust);
            world.spawnParticle(Particle.NOTE, point, 1, 0, 0, 0, random.nextInt(25));
        }
    }

    private static void spawnCircleParticles(org.bukkit.World world, Location center) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Particle.DustOptions ringDust = new Particle.DustOptions(Color.fromRGB(255, 215, 0), 1.1f);
        for (int i = 0; i < 10; i++) {
            double angle = random.nextDouble(Math.PI * 2);
            double radius = random.nextDouble() * KYJ_CIRCLE_RADIUS;
            Location point = new Location(world,
                center.getX() + Math.cos(angle) * radius,
                center.getY() + (random.nextDouble() - 0.5) * KYJ_CIRCLE_HEIGHT * 2,
                center.getZ() + Math.sin(angle) * radius);
            world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, ringDust);
            if (i % 2 == 0) {
                world.spawnParticle(Particle.NOTE, point, 1, 0, 0, 0, random.nextInt(25));
            }
        }
    }

    private static int noteExplosion(org.bukkit.World world, Location center, Player player, Map<String, Object> attr,
                                     double damage, double pullStrength) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int particles = ParticleBudget.request(world, 24);
        for (int i = 0; i < particles; i++) {
            double angle = random.nextDouble(Math.PI * 2);
            double radius = random.nextDouble() * 5.0;
            world.spawnParticle(Particle.NOTE,
                center.getX() + Math.cos(angle) * radius,
                center.getY() + random.nextDouble() * 2.0,
                center.getZ() + Math.sin(angle) * radius,
                1, 0, 0, 0, random.nextInt(25));
        }
        int totalChange = 0;
        for (LivingEntity entity : EntityQueries.living(world, center, 5)) {
            if (entity == player || entity.getType().name().equals("ARMOR_STAND") || entity.isDead()) {
                continue;
            }
            if (entity instanceof Player) {
                entity.damage(damage, player);
                continue;
            }
            entity.damage(damage, player);
            totalChange += applyMeritChange(attr, entity.getType().name());
            PotionEffectType slowness = PotionEffectType.SLOWNESS;
            if (slowness != null) {
                entity.addPotionEffect(new PotionEffect(slowness, 40, 1));
            }
        }
        if (pullStrength > 0) {
            safePull(world, center, player, pullStrength);
        }
        world.playSound(center, "block.note_block.chime", 0.8f, 1.5f);
        return totalChange;
    }

    private static int applyAreaDamage(org.bukkit.World world, Location center, Player player, double damage,
                                       double rx, double ry, double rz, Map<String, Object> attr) {
        int total = 0;
        for (LivingEntity entity : EntityQueries.living(world, center, rx, ry, rz)) {
            total += processEntityDamage(entity, player, damage, attr);
        }
        return total;
    }

    private static int processEntityDamage(LivingEntity entity, Player player, double damage, Map<String, Object> attr) {
        if (entity == player || !entity.isValid() || entity.getType().name().equals("ARMOR_STAND") || entity.isDead()) {
            return 0;
        }
        return damageEntity(entity, player, damage, attr);
    }

    private static int damageEntity(LivingEntity entity, Player player, double damage, Map<String, Object> attr) {
        if (entity instanceof Player) {
            entity.damage(damage, player);
            return 0;
        }
        entity.damage(damage, player);
        int change = applyMeritChange(attr, entity.getType().name());
        PotionEffectType blindness = PotionEffectType.BLINDNESS;
        if (blindness != null) {
            entity.addPotionEffect(new PotionEffect(blindness, FYM_BLIND_DURATION, 0));
        }
        return change;
    }

    private static int applyMeritChange(Map<String, Object> attr, String entityType) {
        return ScrollCombatEngine.meritChangeDelta(attr, entityType);
    }

    private static void safePull(org.bukkit.World world, Location center, Player player, double strength) {
        safePull(world, center, player, strength, 4);
    }

    private static void safePull(org.bukkit.World world, Location center, Player player, double strength, double radius) {
        for (LivingEntity entity : EntityQueries.living(world, center, radius)) {
            if (entity == player || entity.getType().name().equals("ARMOR_STAND") || entity.isDead()) {
                continue;
            }
            Vector diff = center.toVector().subtract(entity.getLocation().toVector());
            if (diff.lengthSquared() > 0.0001) {
                entity.setVelocity(entity.getVelocity().add(diff.normalize().multiply(strength)));
            }
        }
    }

    private static Plugin plugin() {
        return Bukkit.getPluginManager().getPlugin(NativeAddonConfig.PLUGIN_NAME);
    }
}
