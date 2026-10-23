package tech.komutech.native_scripts.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.World;
import org.bukkit.util.Vector;

public final class ParticleBudget {

    /** 副本密集特效下 160 易打满导致后半段粒子全丢；提高并给战斗特效留余量 */
    private static final int MAX_PER_WORLD_TICK = 480;
    private static final Map<String, Long> worldTicks = new ConcurrentHashMap<>();
    private static final Map<String, Integer> worldUsage = new ConcurrentHashMap<>();

    private ParticleBudget() {
    }

    public static int request(World world, int count) {
        if (world == null || count <= 0) {
            return 0;
        }
        String key = world.getName();
        long tick = world.getFullTime();
        worldTicks.compute(key, (name, last) -> {
            if (last == null || last != tick) {
                worldUsage.put(name, 0);
            }
            return tick;
        });
        int used = worldUsage.getOrDefault(key, 0);
        int allowed = Math.min(count, Math.max(0, MAX_PER_WORLD_TICK - used));
        if (allowed > 0) {
            worldUsage.put(key, used + allowed);
        }
        return allowed;
    }

    public static boolean canRunHeavyEffect(World world) {
        return request(world, 1) > 0;
    }

    public static int scaleTrailPoints(int defaultPoints) {
        return defaultPoints >= 12 ? 8 : defaultPoints;
    }

    public static void spawnDust(World world, Location location, DustOptions dust, int count) {
        if (world == null || location == null || dust == null || count <= 0) {
            return;
        }
        int allowed = request(world, count);
        if (allowed > 0) {
            world.spawnParticle(Particle.DUST, location, allowed, 0, 0, 0, 0, dust);
        }
    }

    public static void spawnEndRod(World world, Location location, double offsetX, double offsetY, double offsetZ,
                                   double speed, int count) {
        if (world == null || location == null || count <= 0) {
            return;
        }
        int allowed = request(world, count);
        if (allowed > 0) {
            world.spawnParticle(Particle.END_ROD, location, allowed, offsetX, offsetY, offsetZ, speed);
        }
    }

    public static void spawnRayTrail(World world, Location start, Vector direction, double maxDist,
                                     Color startColor, Color endColor, float size) {
        spawnRayTrail(world, start, direction, maxDist, startColor, endColor, size, 0.25);
    }

    public static void spawnRayTrail(World world, Location start, Vector direction, double maxDist,
                                     Color startColor, Color endColor, float size, double step) {
        if (world == null || start == null || direction == null || maxDist <= 0) {
            return;
        }
        Vector dir = direction.clone();
        if (dir.lengthSquared() < 0.0001) {
            return;
        }
        dir.normalize();
        double spacing = step > 0 ? step : 0.25;
        float dustSize = Math.max(1.0f, Math.min(4.0f, size));
        for (double distance = 0; distance <= maxDist; distance += spacing) {
            double t = distance / maxDist;
            int red = (int) Math.round(startColor.getRed() + (endColor.getRed() - startColor.getRed()) * t);
            int green = (int) Math.round(startColor.getGreen() + (endColor.getGreen() - startColor.getGreen()) * t);
            int blue = (int) Math.round(startColor.getBlue() + (endColor.getBlue() - startColor.getBlue()) * t);
            DustOptions dust = new DustOptions(Color.fromRGB(red, green, blue), dustSize);
            Location point = start.clone().add(dir.clone().multiply(distance));
            world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
        }
    }
}
