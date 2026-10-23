package tech.komutech.native_scripts.cultivation;

import tech.komutech.native_scripts.support.EntityQueries;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.persistence.PersistentDataType;
import tech.origintech.NativeAddonConfig;

final class PuTuanSitHelper {

    private static final NamespacedKey SEAT_TAG = new NamespacedKey(
        NativeAddonConfig.PLUGIN_NAME.toLowerCase(), "pu_tuan_seat");
    private static final String GSIT_API = "dev.geco.gsit.api.GSitAPI";
    private static final String GSIT_STOP_REASON = "dev.geco.gsit.model.StopReason";

    private PuTuanSitHelper() {
    }

    static void sit(Player player, Location matLoc) {
        release(player);
        if (tryGSit(player, matLoc)) {
            return;
        }
        spawnArmorStandSeat(player, matLoc);
    }

    static void maintain(Player player, Location matLoc) {
        if (isGSitOnMat(player, matLoc)) {
            return;
        }
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof ArmorStand stand && isTaggedSeat(stand)) {
            Location seatLoc = seatLocation(matLoc, player);
            if (vehicle.getLocation().distanceSquared(seatLoc) > 0.01) {
                vehicle.teleport(seatLoc);
            }
            if (!vehicle.getPassengers().contains(player)) {
                vehicle.addPassenger(player);
            }
            player.setPose(Pose.SITTING);
            return;
        }
        sit(player, matLoc);
    }

    static void release(Player player) {
        releaseGSit(player);
        ejectTaggedSeat(player);
        player.setPose(Pose.STANDING);
    }

    static void cleanupAt(Location matLoc) {
        removeSeatsAt(matLoc);
    }

    static boolean isOnMat(Player player, Location matLoc) {
        if (!player.isOnline() || matLoc.getWorld() == null
            || !player.getWorld().equals(matLoc.getWorld())) {
            return false;
        }
        if (isGSitOnMat(player, matLoc)) {
            return true;
        }
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof ArmorStand stand && isTaggedSeat(stand)) {
            return isNearMat(vehicle.getLocation(), matLoc);
        }
        if (player.getPose() == Pose.SITTING && isNearMat(player.getLocation(), matLoc)) {
            return true;
        }
        Block standingOn = player.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN);
        if (blockMatches(standingOn, matLoc)) {
            return true;
        }
        Location feet = player.getLocation();
        int px = feet.getBlockX();
        int py = feet.getBlockY();
        int pz = feet.getBlockZ();
        return px == matLoc.getBlockX() && pz == matLoc.getBlockZ()
            && py >= matLoc.getBlockY() && py <= matLoc.getBlockY() + 1;
    }

    private static boolean tryGSit(Player player, Location matLoc) {
        if (Bukkit.getPluginManager().getPlugin("GSit") == null) {
            return false;
        }
        try {
            Class<?> api = Class.forName(GSIT_API);
            Object seat = api.getMethod("createSeat", Block.class, LivingEntity.class,
                    boolean.class, float.class, boolean.class)
                .invoke(null, matLoc.getBlock(), player, false, player.getLocation().getYaw(), true);
            return seat != null;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static boolean isGSitOnMat(Player player, Location matLoc) {
        if (Bukkit.getPluginManager().getPlugin("GSit") == null) {
            return false;
        }
        try {
            Class<?> api = Class.forName(GSIT_API);
            boolean sitting = (boolean) api.getMethod("isEntitySitting", LivingEntity.class)
                .invoke(null, player);
            if (!sitting) {
                return false;
            }
            Object seat = api.getMethod("getSeatByEntity", LivingEntity.class).invoke(null, player);
            if (seat == null) {
                return isNearMat(player.getLocation(), matLoc);
            }
            Object block = seat.getClass().getMethod("getBlock").invoke(seat);
            if (block instanceof Block seatBlock) {
                return blockMatches(seatBlock, matLoc);
            }
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
        return false;
    }

    private static void releaseGSit(Player player) {
        if (Bukkit.getPluginManager().getPlugin("GSit") == null) {
            return;
        }
        try {
            Class<?> api = Class.forName(GSIT_API);
            Object seat = api.getMethod("getSeatByEntity", LivingEntity.class).invoke(null, player);
            if (seat == null) {
                return;
            }
            Class<?> stopReasonClass = Class.forName(GSIT_STOP_REASON);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object pluginReason = Enum.valueOf((Class) stopReasonClass, "PLUGIN");
            api.getMethod("removeSeat", seat.getClass(), stopReasonClass, boolean.class)
                .invoke(null, seat, pluginReason, false);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static void spawnArmorStandSeat(Player player, Location matLoc) {
        removeSeatsAt(matLoc);
        Location seatLoc = seatLocation(matLoc, player);
        ArmorStand seat = matLoc.getWorld().spawn(seatLoc, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setGravity(false);
            stand.setAI(false);
            stand.setCollidable(false);
            stand.setSmall(true);
            stand.setArms(false);
            stand.setBasePlate(false);
            stand.setMarker(true);
            stand.getPersistentDataContainer().set(SEAT_TAG, PersistentDataType.STRING,
                PuTuanDisplayHelper.locationKey(matLoc));
        });
        seat.addPassenger(player);
        player.setPose(Pose.SITTING);
    }

    private static void ejectTaggedSeat(Player player) {
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof ArmorStand stand && isTaggedSeat(stand)) {
            vehicle.eject();
            if (!stand.isDead()) {
                stand.remove();
            }
        }
    }

    private static void removeSeatsAt(Location matLoc) {
        if (matLoc.getWorld() == null) {
            return;
        }
        String key = PuTuanDisplayHelper.locationKey(matLoc);
        for (Entity entity : EntityQueries.entities(matLoc, 2)) {
            if (!(entity instanceof ArmorStand stand) || !isTaggedSeat(stand)) {
                continue;
            }
            String tagged = stand.getPersistentDataContainer().get(SEAT_TAG, PersistentDataType.STRING);
            if (!key.equals(tagged)) {
                continue;
            }
            stand.eject();
            if (!stand.isDead()) {
                stand.remove();
            }
        }
    }

    private static boolean isTaggedSeat(Entity entity) {
        return entity instanceof ArmorStand stand
            && stand.getPersistentDataContainer().has(SEAT_TAG, PersistentDataType.STRING);
    }

    private static Location seatLocation(Location matLoc, Player player) {
        Location sit = matLoc.clone().add(0.5, -0.25, 0.5);
        sit.setYaw(player.getLocation().getYaw());
        sit.setPitch(0);
        return sit;
    }

    private static boolean isNearMat(Location loc, Location matLoc) {
        if (loc.getWorld() == null || !loc.getWorld().equals(matLoc.getWorld())) {
            return false;
        }
        double dx = loc.getX() - (matLoc.getBlockX() + 0.5);
        double dz = loc.getZ() - (matLoc.getBlockZ() + 0.5);
        return dx * dx + dz * dz <= 1.2 && loc.getY() >= matLoc.getY() - 0.6 && loc.getY() <= matLoc.getBlockY() + 1.5;
    }

    private static boolean blockMatches(Block block, Location matLoc) {
        return block.getWorld().equals(matLoc.getWorld())
            && block.getX() == matLoc.getBlockX()
            && block.getY() == matLoc.getBlockY()
            && block.getZ() == matLoc.getBlockZ();
    }
}
