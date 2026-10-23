package tech.komutech.native_scripts.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.ChatInputService;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;

public final class TrialZombieScript implements NativeScript {

    private static final Pattern ARMOR = Pattern.compile("保护(\\d+)");
    private static final Pattern HEALTH = Pattern.compile("(\\d+)\\s*血");
    private static final Map<UUID, Integer> MODES = new ConcurrentHashMap<>();

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        ItemStack item = ctx.item();
        if (player.isSneaking()) {
            int mode = MODES.getOrDefault(player.getUniqueId(), 0);
            ChatInputService.request(player, mode == 0 ? "§e请输入护甲等级(-1~255):" : "§e请输入血量:",
                input -> handleInput(player, item, mode, input));
            return null;
        }
        Settings settings = readSettings(item.getItemMeta());
        spawnZombie(player, settings);
        return null;
    }

    private void handleInput(Player player, ItemStack item, int mode, String input) {
        MODES.remove(player.getUniqueId());
        try {
            if (mode == 0) {
                int armor = Integer.parseInt(input.trim());
                updateLore(item, "§b§l已设置护甲 :", formatArmor(armor));
                MODES.put(player.getUniqueId(), 1);
                ChatInputService.request(player, "§e请输入血量:", next -> handleInput(player, item, 1, next));
            } else {
                int health = Integer.parseInt(input.trim());
                updateLore(item, "§b§l已设置血量 :", "§a§l" + health + " 血");
                KomutechSupport.send(player, "§a设置完成");
            }
        } catch (NumberFormatException exception) {
            KomutechSupport.send(player, "§c无效数字");
        }
    }

    private static void spawnZombie(Player player, Settings settings) {
        Location spawn = player.getLocation().add(player.getLocation().getDirection().multiply(2)).add(0, 1, 0);
        Zombie zombie = (Zombie) player.getWorld().spawnEntity(spawn, EntityType.ZOMBIE);
        zombie.setAdult();
        zombie.setMaxHealth(settings.health);
        zombie.setHealth(settings.health);
        zombie.setAware(false);
        zombie.setCanPickupItems(false);
        zombie.customName(net.kyori.adventure.text.Component.text("§c测试"));
        zombie.setCustomNameVisible(true);
        var equipment = zombie.getEquipment();
        if (equipment != null) {
            equipment.setHelmetDropChance(0);
            equipment.setChestplateDropChance(0);
            equipment.setLeggingsDropChance(0);
            equipment.setBootsDropChance(0);
            if (settings.armorLevel >= 0) {
                Material[] pieces = {
                    Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                    Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS
                };
                ItemStack[] armor = new ItemStack[pieces.length];
                for (int i = 0; i < pieces.length; i++) {
                    armor[i] = new ItemStack(pieces[i]);
                    if (settings.armorLevel > 0) {
                        ItemMeta meta = armor[i].getItemMeta();
                        if (meta != null) {
                            meta.addEnchant(Enchantment.PROTECTION, Math.min(settings.armorLevel, 255), true);
                            armor[i].setItemMeta(meta);
                        }
                    }
                }
                equipment.setHelmet(armor[0]);
                equipment.setChestplate(armor[1]);
                equipment.setLeggings(armor[2]);
                equipment.setBoots(armor[3]);
            }
        }
        KomutechSupport.send(player, "§a试炼僵尸已生成");
    }

    private static Settings readSettings(ItemMeta meta) {
        int armor = 0;
        double health = 1024;
        if (meta != null && meta.hasLore()) {
            for (String line : meta.getLore()) {
                if (line == null) {
                    continue;
                }
                if (line.startsWith("§b§l已设置护甲 :")) {
                    if (line.contains("无护甲")) {
                        armor = -1;
                    } else if (line.contains("合金甲无保护")) {
                        armor = 0;
                    } else {
                        Matcher matcher = ARMOR.matcher(line);
                        if (matcher.find()) {
                            armor = Integer.parseInt(matcher.group(1));
                        }
                    }
                } else if (line.startsWith("§b§l已设置血量 :")) {
                    Matcher matcher = HEALTH.matcher(line);
                    if (matcher.find()) {
                        health = Double.parseDouble(matcher.group(1));
                    }
                }
            }
        }
        return new Settings(armor, health);
    }

    private static void updateLore(ItemStack item, String key, String valueLine) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        String line = key + " " + valueLine;
        boolean found = false;
        for (int i = 0; i < lore.size(); i++) {
            if (lore.get(i).startsWith(key)) {
                lore.set(i, line);
                found = true;
                break;
            }
        }
        if (!found) {
            lore.add(line);
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private static String formatArmor(int armor) {
        if (armor < 0) {
            return "§a§l无护甲";
        }
        if (armor == 0) {
            return "§a§l合金甲无保护";
        }
        return "§a§l保护" + armor + "合金甲";
    }

    private record Settings(int armorLevel, double health) {
    }
}
