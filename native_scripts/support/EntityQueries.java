package tech.komutech.native_scripts.support;

import java.util.Collection;
import java.util.Collections;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/** 集中封装附近实体查询，避免各脚本重复调用 Bukkit 扫描 API。 */
public final class EntityQueries {

    private EntityQueries() {
    }

    public static Collection<LivingEntity> living(Location center, double radius) {
        if (center == null) {
            return Collections.emptyList();
        }
        World world = center.getWorld();
        if (world == null) {
            return Collections.emptyList();
        }
        return living(world, center, radius);
    }

    public static Collection<LivingEntity> living(World world, Location center, double radius) {
        return world.getNearbyLivingEntities(center, radius, radius, radius);
    }

    public static Collection<LivingEntity> living(World world, Location center, double rx, double ry, double rz) {
        return world.getNearbyLivingEntities(center, rx, ry, rz);
    }

    public static Collection<Entity> entities(Location center, double radius) {
        if (center == null) {
            return Collections.emptyList();
        }
        World world = center.getWorld();
        if (world == null) {
            return Collections.emptyList();
        }
        return entities(world, center, radius);
    }

    public static Collection<Entity> entities(World world, Location center, double radius) {
        return world.getNearbyEntities(center, radius, radius, radius);
    }

    public static Collection<Entity> entities(org.bukkit.entity.Entity anchor, double radius) {
        if (anchor == null) {
            return Collections.emptyList();
        }
        return entities(anchor.getLocation(), radius);
    }

    public static Collection<Entity> entities(World world, Location center, double rx, double ry, double rz) {
        return world.getNearbyEntities(center, rx, ry, rz);
    }
}
