package tech.komutech.native_scripts.item;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;

public final class IslanderScript implements NativeScript {

    private static final Map<String, ItemStack> ITEM_CACHE = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWNS = new ConcurrentHashMap<>();
    private static final String[] CACHE_IDS = {
        "KOMUTECH_L_SW_XDRHH",
        "KOMUTECH_L_DJ_功德券",
        "KOMUTECH_L_J_布衣",
        "KOMUTECH_L_J_布裤",
        "KOMUTECH_L_J_布鞋",
        "KOMUTECH_L_W_玄铁剑",
        "KOMUTECH_L_W_玄铁弓"
    };

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(Boolean.FALSE);
    }

    private Boolean handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        long now = System.currentTimeMillis();
        Long last = COOLDOWNS.get(player.getUniqueId());
        if (last != null && now - last < 1000) {
            KomutechSupport.actionBar(player, "§c请不要连续召唤");
            return Boolean.FALSE;
        }
        COOLDOWNS.put(player.getUniqueId(), now);
        Location spawn = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(3));
        Material blockType = spawn.getBlock().getType();
        if (blockType.isSolid() || blockType == Material.WATER || blockType == Material.LAVA) {
            KomutechSupport.send(player, "§c目标位置无法生成生物！");
            return Boolean.FALSE;
        }
        boolean skeleton = Math.random() < 0.5;
        LivingEntity entity = skeleton
            ? (LivingEntity) player.getWorld().spawnEntity(spawn, EntityType.SKELETON)
            : (LivingEntity) player.getWorld().spawnEntity(spawn, EntityType.ZOMBIE);
        entity.customName(net.kyori.adventure.text.Component.text("§c§l小岛人"));
        entity.setCustomNameVisible(true);
        if (entity.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(100);
        }
        entity.setHealth(100);
        entity.setRemoveWhenFarAway(false);
        entity.setCanPickupItems(false);
        equip(entity, skeleton);
        applyEffects(entity);
        UseEvents.consumeOne(ctx);
        player.updateInventory();
        KomutechSupport.send(player, "§a小岛人（" + (skeleton ? "骷髅" : "僵尸") + "）生成完成");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        return Boolean.TRUE;
    }

    private static void equip(LivingEntity entity, boolean skeleton) {
        ensureItemsCached();
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return;
        }
        setPiece(equipment, EquipmentSlot.HEAD, "KOMUTECH_L_SW_XDRHH", 0.95f);
        setPiece(equipment, EquipmentSlot.CHEST, "KOMUTECH_L_J_布衣", 0f);
        setPiece(equipment, EquipmentSlot.LEGS, "KOMUTECH_L_J_布裤", 0f);
        setPiece(equipment, EquipmentSlot.FEET, "KOMUTECH_L_J_布鞋", 0f);
        setPiece(equipment, EquipmentSlot.OFF_HAND, "KOMUTECH_L_DJ_功德券", 0.75f);
        String weaponId = skeleton ? "KOMUTECH_L_W_玄铁弓" : "KOMUTECH_L_W_玄铁剑";
        ItemStack weapon = ITEM_CACHE.get(weaponId);
        if (weapon != null) {
            equipment.setItemInMainHand(weapon.clone());
            equipment.setItemInMainHandDropChance(0f);
        }
        if (entity instanceof Skeleton sk) {
            sk.setCanPickupItems(false);
        } else if (entity instanceof Zombie zombie) {
            zombie.setCanPickupItems(false);
        }
    }

    private static void setPiece(EntityEquipment equipment, EquipmentSlot slot, String id, float dropChance) {
        ItemStack item = ITEM_CACHE.get(id);
        if (item == null) {
            return;
        }
        switch (slot) {
            case HEAD -> {
                equipment.setHelmet(item.clone());
                equipment.setHelmetDropChance(dropChance);
            }
            case CHEST -> {
                equipment.setChestplate(item.clone());
                equipment.setChestplateDropChance(dropChance);
            }
            case LEGS -> {
                equipment.setLeggings(item.clone());
                equipment.setLeggingsDropChance(dropChance);
            }
            case FEET -> {
                equipment.setBoots(item.clone());
                equipment.setBootsDropChance(dropChance);
            }
            case OFF_HAND -> {
                equipment.setItemInOffHand(item.clone());
                equipment.setItemInOffHandDropChance(dropChance);
            }
            default -> {
            }
        }
    }

    private static void applyEffects(LivingEntity entity) {
        PotionEffectType resistance = PotionEffectType.getByName("RESISTANCE");
        if (resistance == null) {
            resistance = PotionEffectType.getByName("DAMAGE_RESISTANCE");
        }
        PotionEffectType speed = PotionEffectType.getByName("SPEED");
        int duration = 999999 * 20;
        if (resistance != null) {
            entity.addPotionEffect(new PotionEffect(resistance, duration, 1, true, true));
        }
        if (speed != null) {
            entity.addPotionEffect(new PotionEffect(speed, duration, 3, true, true));
        }
    }

    private static void ensureItemsCached() {
        for (String id : CACHE_IDS) {
            cache(id);
        }
    }

    private static void cache(String id) {
        if (ITEM_CACHE.containsKey(id)) {
            return;
        }
        SlimefunItem sf = SlimefunItem.getById(id);
        if (sf != null) {
            ITEM_CACHE.put(id, sf.getItem());
        }
    }
}
