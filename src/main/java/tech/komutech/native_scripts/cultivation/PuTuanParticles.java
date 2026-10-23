package tech.komutech.native_scripts.cultivation;

import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.ParticleBudget;

final class PuTuanParticles {

    private static final Color CYAN = Color.fromRGB(120, 220, 255);
    private static final Color PURPLE = Color.fromRGB(180, 90, 255);
    private static final Color GOLD = Color.fromRGB(255, 215, 0);
    private static final Color WHITE = Color.fromRGB(255, 255, 220);
    private static final Color RED = Color.fromRGB(200, 40, 40);
    private static final Color ELECTRIC = Color.fromRGB(180, 220, 255);

    private PuTuanParticles() {
    }

    /** 修炼中：极轻量环绕粒子 */
    static void meditation(Player player) {
        Location base = player.getLocation();
        int budget = ParticleBudget.request(player.getWorld(), 3);
        if (budget <= 0) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int count = Math.min(budget, 2 + random.nextInt(2));
        spawnRing(player, Particle.END_ROD, count, base, 0.55, 0.35, 0.45, 0.9, null);
        if (count < budget) {
            spawnDust(player, base.clone().add(0, 0.75, 0), CYAN, 1.0f);
        }
    }

    /** 突破中：中等强度持续粒子 */
    static void breakthroughProgress(Player player) {
        Location base = player.getLocation();
        int budget = ParticleBudget.request(player.getWorld(), 8);
        if (budget <= 0) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int count = Math.min(budget - 1, 4 + random.nextInt(3));
        spawnRing(player, Particle.ENCHANT, count, base, 0.5, 0.55, 0.35, 1.1, null);
        spawnDust(player, base.clone().add(0, 1.0, 0), PURPLE, 1.2f);
        if (budget >= 7 && random.nextBoolean()) {
            spawnDust(player, base.clone().add(0, 0.5, 0), Color.fromRGB(255, 120, 200), 0.9f);
        }
    }

    /** 突破成功：短时多波庆祝粒子（仅玩家可见，不伤害方块） */
    static void breakthroughSuccess(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        scheduleBurst(player, 3, 4, tick -> {
            Location center = player.getLocation().add(0, 0.9 + tick * 0.15, 0);
            int budget = ParticleBudget.request(player.getWorld(), 10);
            if (budget <= 0) {
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int count = Math.min(budget, 6 + tick);
            for (int i = 0; i < count; i++) {
                double angle = random.nextDouble(Math.PI * 2);
                double radius = 0.4 + random.nextDouble(1.0 + tick * 0.2);
                Location point = center.clone().add(
                    Math.cos(angle) * radius,
                    random.nextDouble(1.2),
                    Math.sin(angle) * radius);
                Color color = i % 2 == 0 ? GOLD : WHITE;
                spawnDust(player, point, color, 1.4f);
            }
            if (tick == 2 && ParticleBudget.request(player.getWorld(), 2) > 0) {
                player.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 3, 0.25, 0.4, 0.25, 0.02);
            }
        });
    }

    /** 突破失败：红色溃散粒子 */
    static void breakthroughFailure(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 0.7f);
        scheduleBurst(player, 2, 5, tick -> {
            Location center = player.getLocation().add(0, 0.7, 0);
            int budget = ParticleBudget.request(player.getWorld(), 10);
            if (budget <= 0) {
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int count = Math.min(budget - 1, 5 + tick * 2);
            for (int i = 0; i < count; i++) {
                double angle = random.nextDouble(Math.PI * 2);
                double radius = 0.35 + random.nextDouble(0.9);
                Location point = center.clone().add(
                    Math.cos(angle) * radius,
                    random.nextDouble(0.8),
                    Math.sin(angle) * radius);
                spawnDust(player, point, RED, 1.1f);
            }
            if (ParticleBudget.request(player.getWorld(), 1) > 0) {
                player.spawnParticle(Particle.SMOKE, center, 2, 0.2, 0.25, 0.2, 0.01);
            }
        });
    }

    /** 雷劫：纯粒子+音效，不使用真实雷电 */
    static void tribulationStrike(Player player) {
        Location center = player.getLocation();
        player.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.45f, 1.1f);
        player.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.35f, 1.3f);
        int budget = ParticleBudget.request(player.getWorld(), 10);
        if (budget <= 0) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (ParticleBudget.request(player.getWorld(), 1) > 0) {
            player.spawnParticle(Particle.FLASH, center.clone().add(0, 1.2, 0), 1, 0, 0, 0, 0, WHITE);
        }
        int sparks = Math.min(budget, 4 + random.nextInt(3));
        for (int i = 0; i < sparks; i++) {
            Location from = center.clone().add(
                (random.nextDouble() - 0.5) * 0.6,
                2.5 + random.nextDouble(1.5),
                (random.nextDouble() - 0.5) * 0.6);
            Location to = center.clone().add(
                (random.nextDouble() - 0.5) * 0.8,
                0.3 + random.nextDouble(0.8),
                (random.nextDouble() - 0.5) * 0.8);
            player.spawnParticle(Particle.ELECTRIC_SPARK, from, 1, 0, -0.15, 0, 0.02);
            player.spawnParticle(Particle.ELECTRIC_SPARK, to, 1, 0.04, 0.04, 0.04, 0.01);
            spawnDust(player, to, ELECTRIC, 0.9f);
        }
    }

    private static void spawnRing(Player player, Particle particle, int count, Location base,
                                  double radiusMin, double radiusRange, double yMin, double yRange,
                                  Particle.DustOptions dust) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble(Math.PI * 2);
            double radius = radiusMin + random.nextDouble(radiusRange);
            Location point = base.clone().add(
                radius * Math.cos(angle),
                yMin + random.nextDouble(yRange),
                radius * Math.sin(angle));
            if (dust != null) {
                player.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
            } else {
                player.spawnParticle(particle, point, 1, 0, 0, 0, 0);
            }
        }
    }

    private static void spawnDust(Player player, Location point, Color color, float size) {
        if (ParticleBudget.request(player.getWorld(), 1) <= 0) {
            return;
        }
        player.spawnParticle(Particle.DUST, point, 1, 0.03, 0.05, 0.03, 0,
            new Particle.DustOptions(color, size));
    }

    private static void scheduleBurst(Player player, int waves, long intervalTicks, BurstWave wave) {
        Plugin plugin = KomutechSupport.plugin();
        if (plugin == null) {
            wave.run(0);
            return;
        }
        for (int i = 0; i < waves; i++) {
            int tick = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    wave.run(tick);
                }
            }, intervalTicks * i);
        }
    }

    @FunctionalInterface
    private interface BurstWave {
        void run(int tick);
    }
}
