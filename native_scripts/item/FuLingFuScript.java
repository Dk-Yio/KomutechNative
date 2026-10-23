package tech.komutech.native_scripts.item;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;
import tech.komutech.native_scripts.support.ParticleBudget;
import tech.komutech.native_scripts.support.PlayerAttributeStore;

public final class FuLingFuScript implements NativeScript {

    private static final int SPIRIT_COST = 5;
    private static final long COOLDOWN_MS = 1000L;
    private static final int RANGE = 8;
    private static final double DETECTION_RADIUS = 1.0;
    private static final int ANIMATION_DURATION = 60;
    private static final int SPIRAL_COUNT = 3;
    private static final int PARTICLES_PER_SPIRAL = 8;
    private static final double SPIRAL_RADIUS = 1.2;
    private static final double SPIRAL_SPEED = 0.2;
    private static final String SPECIAL_ID = "KOMUTECH_L_F_灵墟缚灵符";

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, CaptureAnimation> captureAnimations = new HashMap<>();
    private final Map<UUID, BukkitTask> animationTasks = new HashMap<>();

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now - cooldowns.getOrDefault(id, 0L) < COOLDOWN_MS) {
            KomutechSupport.actionBar(player, "§c冷却中");
            return null;
        }
        if (captureAnimations.containsKey(id)) {
            KomutechSupport.actionBar(player, "§c你正在进行捕捉！");
            return null;
        }
        cooldowns.put(id, now);

        ItemStack hand = ctx.item();
        boolean special = false;
        SlimefunItem sf = SlimefunItem.getByItem(hand);
        if (sf != null && SPECIAL_ID.equals(sf.getId())) {
            special = true;
        }

        LivingEntity target = getTargetEntity(player);
        shootRayParticles(player, target);
        if (target == null) {
            KomutechSupport.actionBar(player, "§c未命中生物！");
            return null;
        }

        Map<String, Object> data = PlayerAttributeStore.load(player.getName());
        if (data == null) {
            KomutechSupport.actionBar(player, "§c无玩家属性数据");
            return null;
        }
        PlayerAttributeStore.Lingli lingli = PlayerAttributeStore.parseLingli(data.get("灵力"));
        if (lingli.current() < SPIRIT_COST) {
            KomutechSupport.actionBar(player, "§c灵力不足！需要 " + SPIRIT_COST);
            return null;
        }
        lingli = new PlayerAttributeStore.Lingli(lingli.current() - SPIRIT_COST, lingli.max(), lingli.bonus());
        data.put("灵力", PlayerAttributeStore.formatLingli(lingli));
        PlayerAttributeStore.save(player.getName(), data);

        double playerHp = PlayerAttributeStore.getDouble(data, "血量_实际", 0) + 20;
        double targetHp = target.getHealth();
        double chance = special
            ? Math.min(100, targetHp > playerHp ? 25 : 25 + (playerHp - targetHp) / playerHp * 100)
            : targetHp >= playerHp ? 0 : Math.min(50, (playerHp - targetHp) / playerHp * 50);
        int percent = (int) Math.floor(chance);
        KomutechSupport.actionBar(player, "§e捕捉概率: " + percent + "%");

        if (Math.random() * 100 >= chance) {
            handleCaptureFailure(player, ctx, percent, PlayerAttributeStore.getDouble(data, "灵识", 0), chance);
            return null;
        }
        if (!consumeOne(player)) {
            KomutechSupport.actionBar(player, "§c物品已消失，取消捕捉");
            return null;
        }
        KomutechSupport.actionBar(player, "§a捕捉成功！正在束缚... (" + percent + "%)");
        startCaptureAnimation(player, target, percent, special);
        return null;
    }

    private void handleCaptureFailure(Player player, UseEvents.Context ctx, int percent, double lingShi, double chance) {
        double consumeProb = Math.min(100, Math.max(0, (100 - chance) - lingShi));
        if (Math.random() * 100 < consumeProb) {
            if (consumeOne(player)) {
                KomutechSupport.actionBar(player, "§c捕捉失败！符箓已损坏 (" + percent + "%)");
            } else {
                KomutechSupport.actionBar(player, "§c捕捉失败！");
            }
        } else {
            KomutechSupport.actionBar(player, "§c捕捉失败！但符箓完好无损 (" + percent + "%)");
        }
    }

    private void startCaptureAnimation(Player player, LivingEntity entity, int catchPercent, boolean special) {
        UUID uuid = player.getUniqueId();
        cancelAnimation(uuid);
        PotionEffectType slow = slownessEffect();
        if (slow != null) {
            entity.addPotionEffect(new PotionEffect(slow, 60, 255, true, false));
        }
        CaptureAnimation animation = new CaptureAnimation(player, entity, catchPercent, special);
        captureAnimations.put(uuid, animation);
        BukkitTask task = KomutechSupport.plugin().getServer().getScheduler().runTaskTimer(KomutechSupport.plugin(), () -> {
            CaptureAnimation current = captureAnimations.get(uuid);
            if (current == null) {
                cancelAnimation(uuid);
                return;
            }
            if (current.tick >= ANIMATION_DURATION) {
                finishCaptureAnimation(uuid, true);
                return;
            }
            if (current.entity.isDead() || !current.entity.isValid() || !current.player.isOnline()) {
                finishCaptureAnimation(uuid, false);
                return;
            }
            generateSpiralParticles(current.entity, current.tick);
            current.tick++;
        }, 0L, 2L);
        animationTasks.put(uuid, task);
    }

    private void finishCaptureAnimation(UUID uuid, boolean success) {
        CaptureAnimation animation = captureAnimations.remove(uuid);
        cancelAnimation(uuid);
        if (animation == null) {
            return;
        }
        PotionEffectType slow = slownessEffect();
        if (slow != null && animation.entity.isValid()) {
            animation.entity.removePotionEffect(slow);
        }
        if (success && animation.entity.isValid() && !animation.entity.isDead()) {
            giveSpawnEgg(animation.player, animation.entity, animation.catchPercent, animation.special);
        } else if (animation.player.isOnline()) {
            KomutechSupport.actionBar(animation.player, "§c捕捉失败！");
        }
    }

    private void cancelAnimation(UUID uuid) {
        BukkitTask task = animationTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    private static void giveSpawnEgg(Player player, LivingEntity entity, int catchPercent, boolean special) {
        Material egg;
        try {
            egg = Material.valueOf(entity.getType().name() + "_SPAWN_EGG");
        } catch (IllegalArgumentException exception) {
            egg = Material.EGG;
        }
        Location location = entity.getLocation();
        entity.remove();
        ItemStack stack = new ItemStack(egg, 1);
        Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
        if (!left.isEmpty()) {
            player.getWorld().dropItem(location, stack);
            KomutechSupport.actionBar(player, "§e背包已满，刷怪蛋已掉落！");
        }
        KomutechSupport.actionBar(player, (special ? "§6灵墟§a" : "§a") + "捕捉成功！ (" + catchPercent + "%)");
    }

    private static void generateSpiralParticles(LivingEntity entity, int tick) {
        if (!ParticleBudget.canRunHeavyEffect(entity.getWorld())) {
            return;
        }
        Location location = entity.getLocation();
        double height = entity.getHeight();
        int budget = ParticleBudget.request(entity.getWorld(), SPIRAL_COUNT * PARTICLES_PER_SPIRAL);
        int spawned = 0;
        for (int spiral = 0; spiral < SPIRAL_COUNT && spawned < budget; spiral++) {
            double spiralOffset = spiral * (Math.PI * 2 / SPIRAL_COUNT);
            double spiralHeight = height * (spiral / (double) SPIRAL_COUNT);
            for (int i = 0; i < PARTICLES_PER_SPIRAL && spawned < budget; i++) {
                double angle = (i * (Math.PI * 2 / PARTICLES_PER_SPIRAL) + tick * SPIRAL_SPEED + spiralOffset) % (Math.PI * 2);
                double x = Math.cos(angle) * SPIRAL_RADIUS;
                double z = Math.sin(angle) * SPIRAL_RADIUS;
                Location particleLoc = location.clone().add(x, spiralHeight + 0.1, z);
                ParticleBudget.spawnDust(entity.getWorld(), particleLoc,
                    new DustOptions(Color.fromRGB(255, 105, 180), 0.5f), 1);
                spawned++;
                if (spawned < budget && i % 2 == 0) {
                    ParticleBudget.spawnDust(entity.getWorld(), particleLoc,
                        new DustOptions(Color.fromRGB(0, 255, 255), 0.4f), 1);
                    spawned++;
                }
            }
        }
    }

    private static void shootRayParticles(Player player, LivingEntity target) {
        Location start = player.getEyeLocation();
        Location end;
        if (target != null && target.isValid()) {
            end = target.getLocation().add(0, target.getHeight() / 2, 0);
        } else {
            Vector direction = start.getDirection().normalize();
            end = start.clone().add(direction.multiply(RANGE));
        }
        Vector direction = end.toVector().subtract(start.toVector());
        double distance = Math.min(direction.length(), RANGE);
        if (distance < 0.5) {
            return;
        }
        direction.normalize();
        for (double d = 0; d <= distance; d += 0.25) {
            double progress = d / distance;
            int r = (int) Math.round(0 + (255 - 0) * progress);
            int g = (int) Math.round(255 + (105 - 255) * progress);
            int b = (int) Math.round(255 + (180 - 255) * progress);
            Location point = start.clone().add(direction.clone().multiply(d));
            player.getWorld().spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0,
                new DustOptions(Color.fromRGB(r, g, b), 0.6f));
        }
    }

    private static LivingEntity getTargetEntity(Player player) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
            player.getEyeLocation(),
            player.getEyeLocation().getDirection(),
            RANGE,
            DETECTION_RADIUS,
            entity -> entity instanceof LivingEntity living && !(living instanceof Player) && living != player
        );
        return result == null || !(result.getHitEntity() instanceof LivingEntity living) ? null : living;
    }

    private static boolean consumeOne(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
        return true;
    }

    private static PotionEffectType slownessEffect() {
        PotionEffectType type = PotionEffectType.getByName("SLOWNESS");
        return type != null ? type : PotionEffectType.getByName("SLOW");
    }

    private static final class CaptureAnimation {
        final Player player;
        final LivingEntity entity;
        final int catchPercent;
        final boolean special;
        int tick;

        CaptureAnimation(Player player, LivingEntity entity, int catchPercent, boolean special) {
            this.player = player;
            this.entity = entity;
            this.catchPercent = catchPercent;
            this.special = special;
        }
    }
}
