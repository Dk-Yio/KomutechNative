package tech.komutech.native_scripts.item;

import tech.komutech.native_scripts.support.EntityQueries;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import tech.origintech.NativeAddonConfig;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.combat.CombatHitGate;
import tech.komutech.native_scripts.combat.ScrollCombatEngine;
import tech.komutech.native_scripts.combat.ScrollCombatHelper;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;
import tech.komutech.native_scripts.support.PlayerAttributeStore;

public final class HuazangStaffScript implements NativeScript {

    private static final int PETAL_COUNT = 6;
    private static final double PETAL_SPEED = 1.3;
    private static final int PETAL_LIFETIME = 30;
    private static final double BLOOM_RADIUS = 3.5;
    private static final int BLOOM_DURATION = 36;
    private static final int BLOOM_DAMAGE_INTERVAL = 12;
    private static final double BLOOM_DAMAGE_RATIO = 0.10;
    private static final double INITIAL_HIT_RATIO = 0.65;
    private static final int TARGET_HIT_ICD_TICKS = 16;
    private static final double HEAL_RATIO = 0.008;
    private static final Color[] PETAL_COLORS = {
        Color.fromRGB(255, 183, 197),
        Color.fromRGB(255, 107, 129),
        Color.fromRGB(255, 71, 87),
        Color.fromRGB(255, 255, 255)
    };

    private static final Map<UUID, BloomCounter> ACTIVE_BLOOMS = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(String functionName, Object... args) {
        if ("onUse".equals(functionName)) {
            return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
        }
        if ("castScroll".equals(functionName) && args.length >= 3 && args[0] instanceof Player player) {
            Map<String, Object> attr = args[2] instanceof Map<?, ?> map
                ? PlayerAttributeStore.deepCopy((Map<String, Object>) map) : null;
            if (attr != null) {
                ItemStack staff = args[1] instanceof ItemStack stack ? stack : player.getInventory().getItemInMainHand();
                String skill = ScrollCombatEngine.getBoundSkill(staff);
                if (skill != null) {
                    ScrollCombatEngine.castScroll(player, staff, attr, skill);
                    PlayerAttributeStore.save(player.getName(), attr);
                }
            }
        }
        return null;
    }

    private Object handleUse(UseEvents.Context ctx) {
        if (ctx.hand() != EquipmentSlot.HAND) {
            return null;
        }
        Player player = ctx.player();
        ItemStack item = ctx.item();
        Map<String, Object> attr = PlayerAttributeStore.load(player.getName());
        if (attr == null) {
            KomutechSupport.send(player, "§c无法读取属性数据");
            return null;
        }
        if (player.isSneaking()) {
            String skill = ScrollCombatEngine.getBoundSkill(item);
            if (skill == null) {
                KomutechSupport.send(player, "§c未绑定卷轴");
                return null;
            }
            ScrollCombatEngine.castScroll(player, item, attr, skill);
            PlayerAttributeStore.save(player.getName(), attr);
            return null;
        }
        if (ScrollCombatEngine.handleStaffRightClick(player, () -> {
            if (!ScrollCombatEngine.tryStaffAttack(player, item, attr, "花葬")) {
                return;
            }
            spawnPetals(player, attr, item);
            PlayerAttributeStore.save(player.getName(), attr);
        })) {
            ScrollCombatEngine.switchScroll(player, item);
        }
        return null;
    }

    private static void spawnPetals(Player player, Map<String, Object> attr, ItemStack item) {
        var world = player.getWorld();
        double damage = ScrollCombatEngine.calcStaffDamage(player, item, attr);
        Location playerLoc = player.getLocation();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        Location start = eye.clone().add(dir);
        double maxDist = ScrollCombatEngine.staffMaxDistance("花葬");
        RayTraceResult hit = world.rayTrace(start, dir, maxDist, FluidCollisionMode.NEVER, false, 0.1,
            entity -> entity instanceof LivingEntity living && ScrollCombatHelper.isValidTarget(player, living));
        LivingEntity aimed = hit != null && hit.getHitEntity() instanceof LivingEntity living
            && ScrollCombatHelper.isValidTarget(player, living) ? living : null;
        var nearby = EntityQueries.living(world, playerLoc, maxDist);
        List<Petal> petals = new ArrayList<>();
        for (int i = 0; i < PETAL_COUNT; i++) {
            double angle = (Math.PI * 2 / PETAL_COUNT) * i + ThreadLocalRandom.current().nextDouble(0.4);
            Location loc = new Location(world,
                playerLoc.getX() + Math.cos(angle) * 1.5,
                playerLoc.getY() + 1.2 + ThreadLocalRandom.current().nextDouble(0.5),
                playerLoc.getZ() + Math.sin(angle) * 1.5);
            // 无目标时按环身角度径向飞出；Location 默认朝向为 0，不能用 getDirection()
            Vector outward = new Vector(
                Math.cos(angle),
                0.12 + ThreadLocalRandom.current().nextDouble(0.18),
                Math.sin(angle)
            ).normalize();
            LivingEntity target = null;
            if (aimed != null && i < PETAL_COUNT / 2) {
                target = aimed;
            } else if (!nearby.isEmpty()) {
                LivingEntity closest = null;
                double closestDist = Double.MAX_VALUE;
                for (LivingEntity entity : nearby) {
                    if (!ScrollCombatHelper.isValidTarget(player, entity)) {
                        continue;
                    }
                    double dist = loc.distanceSquared(entity.getLocation());
                    if (dist < closestDist) {
                        closestDist = dist;
                        closest = entity;
                    }
                }
                target = closest;
            }
            petals.add(new Petal(loc, target, outward));
            // 释放瞬间在身边爆一圈花瓣，避免只看见命中才有粒子
            world.spawnParticle(Particle.CHERRY_LEAVES, loc, 6, 0.2, 0.2, 0.2, 0.02);
            world.spawnParticle(Particle.DUST, loc, 2, 0.1, 0.1, 0.1, 0,
                new Particle.DustOptions(PETAL_COLORS[i % PETAL_COLORS.length], 1.4f));
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin(NativeAddonConfig.PLUGIN_NAME);
        // 同一释放内：每个目标最多触发一次绽放，避免八瓣叠爆
        final Set<UUID> bloomedTargets = new HashSet<>();
        final int[] tick = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (tick[0] >= PETAL_LIFETIME || !player.isOnline() || player.isDead()) {
                task.cancel();
                return;
            }
            for (Petal petal : petals) {
                if (!petal.alive) {
                    continue;
                }
                if (petal.target != null && !petal.target.isDead()) {
                    Vector delta = petal.target.getLocation().add(0, 1, 0).toVector().subtract(petal.loc.toVector());
                    if (delta.lengthSquared() > 0.01) {
                        petal.loc.add(delta.normalize().multiply(PETAL_SPEED));
                    }
                    if (petal.loc.distanceSquared(petal.target.getLocation().add(0, 1, 0)) < 1.5) {
                        UUID tid = petal.target.getUniqueId();
                        if (bloomedTargets.add(tid)) {
                            spawnBloom(world, petal.loc, player, attr, damage);
                        }
                        petal.alive = false;
                    }
                } else {
                    // 八瓣沿各自径向散开，不再共用固定朝向
                    petal.loc.add(petal.outward.clone().multiply(PETAL_SPEED * 0.55));
                    if (tick[0] >= 14) {
                        petal.alive = false;
                    }
                }
                if (petal.alive) {
                    // 花瓣轨迹不走重特效预算，避免副本里整圈看不见
                    world.spawnParticle(Particle.CHERRY_LEAVES, petal.loc, 2, 0.05, 0.05, 0.05, 0);
                    Color petalColor = PETAL_COLORS[ThreadLocalRandom.current().nextInt(PETAL_COLORS.length)];
                    world.spawnParticle(Particle.DUST, petal.loc, 1, 0, 0, 0, 0,
                        new Particle.DustOptions(petalColor, 1.2f));
                }
            }
            tick[0]++;
        }, 0L, 1L);
    }

    private static void spawnBloom(org.bukkit.World world, Location center, Player player, Map<String, Object> attr, double damage) {
        UUID uuid = player.getUniqueId();
        BloomCounter counter = ACTIVE_BLOOMS.computeIfAbsent(uuid, k -> new BloomCounter());
        counter.count++;
        Set<LivingEntity> marked = new HashSet<>();
        var initial = EntityQueries.living(world, center, BLOOM_RADIUS);
        double totalHeal = 0;
        String playerName = player.getName();
        for (LivingEntity entity : initial) {
            if (!ScrollCombatHelper.isValidTarget(player, entity)) {
                continue;
            }
            marked.add(entity);
            if (!CombatHitGate.damage(player, entity, damage * INITIAL_HIT_RATIO, TARGET_HIT_ICD_TICKS)) {
                continue;
            }
            if (!(entity instanceof Player)) {
                ScrollCombatEngine.applyMeritChange(attr, entity.getType().name());
            }
            totalHeal += damage * HEAL_RATIO;
        }
        safePull(world, center, player, 0.10);
        if (totalHeal > 0) {
            healPlayer(player, totalHeal);
        }
        int arms = 3;
        int steps = 20;
        for (int arm = 0; arm < arms; arm++) {
            double baseAngle = arm * Math.PI * 2 / arms;
            for (int j = 0; j < steps; j++) {
                double progress = j / (double) steps;
                double radius = progress * BLOOM_RADIUS;
                double angle = baseAngle + progress * Math.PI * 2;
                double x = center.getX() + Math.cos(angle) * radius;
                double z = center.getZ() + Math.sin(angle) * radius;
                double y = center.getY() + progress * 1.2;
                float size = (float) (0.6 + progress * 0.8);
                Color color = PETAL_COLORS[ThreadLocalRandom.current().nextInt(PETAL_COLORS.length)];
                world.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, new Particle.DustOptions(color, size));
            }
        }
        world.playSound(center, "block.spore_blossom.break", 0.6f, 1.2f);
        Plugin plugin = Bukkit.getPluginManager().getPlugin(NativeAddonConfig.PLUGIN_NAME);
        final int[] tick = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (tick[0] >= BLOOM_DURATION || !player.isOnline() || player.isDead()) {
                counter.count--;
                if (counter.count <= 0) {
                    ACTIVE_BLOOMS.remove(uuid);
                    KomutechSupport.send(player, "§d生命绽放 · 花葬");
                }
                task.cancel();
                return;
            }
            // 绽放余韵粒子不走重特效门禁
            for (int i = 0; i < 6; i++) {
                double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                double r = BLOOM_RADIUS * (0.3 + ThreadLocalRandom.current().nextDouble(0.7));
                Color color = PETAL_COLORS[ThreadLocalRandom.current().nextInt(PETAL_COLORS.length)];
                world.spawnParticle(Particle.DUST,
                    center.getX() + Math.cos(angle) * r,
                    center.getY() + Math.sin(tick[0] * 0.2 + i) * 0.5,
                    center.getZ() + Math.sin(angle) * r,
                    1, 0, 0, 0, 0, new Particle.DustOptions(color, 0.5f));
                if (i % 2 == 0) {
                    world.spawnParticle(Particle.CHERRY_LEAVES,
                        center.getX() + Math.cos(angle) * r,
                        center.getY() + 0.3,
                        center.getZ() + Math.sin(angle) * r,
                        1, 0.05, 0.05, 0.05, 0);
                }
            }
            if (tick[0] % BLOOM_DAMAGE_INTERVAL == 0) {
                double localHeal = 0;
                for (LivingEntity entity : EntityQueries.living(world, center, BLOOM_RADIUS)) {
                    if (!marked.contains(entity) || !ScrollCombatHelper.isValidTarget(player, entity)) {
                        continue;
                    }
                    double bloomDamage = damage * BLOOM_DAMAGE_RATIO;
                    if (!CombatHitGate.damage(player, entity, bloomDamage, TARGET_HIT_ICD_TICKS)) {
                        continue;
                    }
                    if (!(entity instanceof Player)) {
                        ScrollCombatEngine.applyMeritChange(attr, entity.getType().name());
                    }
                    localHeal += bloomDamage * HEAL_RATIO;
                }
                if (localHeal > 0) {
                    healPlayer(player, localHeal);
                }
            }
            tick[0]++;
        }, 0L, 1L);
    }

    private static void safePull(org.bukkit.World world, Location center, Player player, double strength) {
        for (LivingEntity entity : EntityQueries.living(world, center, BLOOM_RADIUS)) {
            if (!ScrollCombatHelper.isValidTarget(player, entity)) {
                continue;
            }
            Vector diff = center.toVector().subtract(entity.getLocation().toVector());
            if (diff.lengthSquared() > 0.0001) {
                entity.setVelocity(entity.getVelocity().add(diff.normalize().multiply(strength)));
            }
        }
    }

    private static void healPlayer(Player player, double amount) {
        double maxHealth = player.getMaxHealth();
        double current = player.getHealth();
        double healAmount = Math.min(amount, maxHealth - current);
        if (healAmount > 0) {
            player.setHealth(current + healAmount);
            return;
        }
        player.setAbsorptionAmount(player.getAbsorptionAmount() + (amount - healAmount));
    }

    private static final class Petal {
        Location loc;
        LivingEntity target;
        final Vector outward;
        boolean alive = true;

        Petal(Location loc, LivingEntity target, Vector outward) {
            this.loc = loc;
            this.target = target;
            this.outward = outward;
        }
    }

    private static final class BloomCounter {
        int count;
    }
}
