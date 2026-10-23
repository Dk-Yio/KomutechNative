package tech.komutech.native_scripts.machine;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.MachineScriptHelper;

public final class ItemDisplayStandScript implements NativeScript {

    private static final String MACHINE_ID = "KOMUTECH_JZ_JCJQ_物品展台";
    private static final NamespacedKey MACHINE_KEY = new NamespacedKey("komutech", "wpzt");

    private static final int ITEM_SLOT = 13;
    private static final int STATUS_SLOT = 4;
    private static final int MANUAL_SLOT = 8;
    private static final int OPEN_SLOT = 22;
    private static final int CLOSE_SLOT = 26;
    private static final int MOVE_UP_SLOT = 0;
    private static final int MOVE_DOWN_SLOT = 18;
    private static final int YAW_LEFT_SLOT = 1;
    private static final int YAW_RIGHT_SLOT = 19;
    private static final int PITCH_DOWN_SLOT = 2;
    private static final int PITCH_UP_SLOT = 20;
    private static final int ROLL_LEFT_SLOT = 3;
    private static final int ROLL_RIGHT_SLOT = 21;
    private static final int RESET_POS_SLOT = 9;
    private static final int RESET_YAW_SLOT = 10;
    private static final int RESET_PITCH_SLOT = 11;
    private static final int RESET_ROLL_SLOT = 12;
    private static final int GLOW_SLOT = 6;
    private static final int NAME_SLOT = 24;

    private static final double MOVE_STEP = 0.1;
    private static final float ANGLE_STEP = 5f;

    private static final Map<String, ItemDisplay> PROJECTIONS = new HashMap<>();
    private static final Map<String, Float> PROJ_YAW = new HashMap<>();
    private static final Map<String, Float> PROJ_PITCH = new HashMap<>();
    private static final Map<String, Float> PROJ_ROLL = new HashMap<>();

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onOpen".equals(functionName) && args[0] instanceof Player player) {
            onOpen(player);
        } else if ("onClose".equals(functionName) && args[0] instanceof Player player) {
            onClose(player);
        } else if ("onClick".equals(functionName) && args.length >= 2 && args[0] instanceof Player player) {
            onClick(player, (Integer) args[1]);
        } else if ("onBreak".equals(functionName) && args[0] instanceof BlockBreakEvent event) {
            removeProjection(event.getBlock());
        }
        return null;
    }

    private void onOpen(Player player) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        Block machine = getMachineFromStatus(inv);
        if (machine == null || !isValidMachine(machine)) {
            machine = getTargetBlock(player);
            if (machine == null) {
                KomutechSupport.send(player, "§c错误：请瞄准要操作的机器！");
                return;
            }
            if (!isValidMachine(machine)) {
                KomutechSupport.send(player, "§c错误：瞄准的方块不是可操作的机器！");
                return;
            }
        } else if (!restoreFromStatus(inv, machine)) {
            ItemDisplay projection = findProjectionByMachine(machine);
            if (projection != null) {
                String key = locationKey(machine);
                PROJECTIONS.put(key, projection);
                PROJ_YAW.put(key, 0f);
                PROJ_PITCH.put(key, 0f);
                PROJ_ROLL.put(key, 0f);
            }
        }
        refreshButtons(inv, machine);
    }

    private void onClose(Player player) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        Block machine = getMachineFromStatus(inv);
        if (machine == null) {
            return;
        }
        String key = locationKey(machine);
        ItemDisplay projection = PROJECTIONS.get(key);
        if (projection == null || projection.isDead()) {
            return;
        }
        ItemStack slotItem = inv.getItem(ITEM_SLOT);
        ItemStack projectionItem = projection.getItemStack();
        if (slotItem == null || slotItem.getType().isAir() || !slotItem.isSimilar(projectionItem)) {
            projection.remove();
            PROJECTIONS.remove(key);
            PROJ_YAW.remove(key);
            PROJ_PITCH.remove(key);
            PROJ_ROLL.remove(key);
            inv.setItem(STATUS_SLOT, null);
        }
    }

    private void onClick(Player player, int slot) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        if (slot == STATUS_SLOT) {
            KomutechSupport.send(player, "§c不能操作状态槽");
            return;
        }
        Block machine = getMachineFromStatus(inv);
        if (machine == null) {
            KomutechSupport.send(player, "§c错误：未检测到机器，请瞄准后重新打开菜单");
            return;
        }
        if (!isValidMachine(machine)) {
            KomutechSupport.send(player, "§c错误：机器失效，请重新打开菜单");
            return;
        }
        String key = locationKey(machine);
        ItemDisplay projection = PROJECTIONS.get(key);

        if (slot == MANUAL_SLOT) {
            Block target = getTargetBlock(player);
            if (target == null) {
                KomutechSupport.send(player, "§c请先瞄准机器");
                return;
            }
            if (!isValidMachine(target)) {
                KomutechSupport.send(player, "§c瞄准的方块不是机器");
                return;
            }
            String newKey = locationKey(target);
            if (!newKey.equals(key)) {
                machine = target;
                key = newKey;
                projection = PROJECTIONS.get(key);
                KomutechSupport.send(player, "§a已切换到新机器");
            }
            ItemDisplay found = findProjectionByMachine(machine);
            if (found != null) {
                PROJECTIONS.put(key, found);
                PROJ_YAW.put(key, 0f);
                PROJ_PITCH.put(key, 0f);
                PROJ_ROLL.put(key, 0f);
                KomutechSupport.send(player, "§a找到并绑定投影");
            } else {
                KomutechSupport.send(player, "§e未找到投影，可点击开启");
            }
            saveStatusToSlot(inv, machine);
            refreshButtons(inv, machine);
            return;
        }
        if (slot == OPEN_SLOT) {
            ItemStack item = inv.getItem(ITEM_SLOT);
            if (item == null || item.getType().isAir()) {
                KomutechSupport.send(player, "§c请放入物品");
                return;
            }
            if (projection != null && !projection.isDead()) {
                projection.remove();
            }
            try {
                projection = createProjection(machine, item);
                PROJECTIONS.put(key, projection);
                PROJ_YAW.put(key, 0f);
                PROJ_PITCH.put(key, 0f);
                PROJ_ROLL.put(key, 0f);
                KomutechSupport.send(player, "§a投影已开启");
            } catch (RuntimeException exception) {
                KomutechSupport.send(player, "§c开启失败");
            }
            saveStatusToSlot(inv, machine);
            refreshButtons(inv, machine);
            return;
        }
        if (slot == CLOSE_SLOT) {
            if (projection != null && !projection.isDead()) {
                projection.remove();
            }
            PROJECTIONS.remove(key);
            PROJ_YAW.remove(key);
            PROJ_PITCH.remove(key);
            PROJ_ROLL.remove(key);
            inv.setItem(STATUS_SLOT, null);
            KomutechSupport.send(player, "§a投影已关闭");
            refreshButtons(inv, machine);
            return;
        }
        if (projection == null || projection.isDead()) {
            KomutechSupport.send(player, "§c请先开启投影");
            return;
        }

        float yaw = PROJ_YAW.getOrDefault(key, 0f);
        float pitch = PROJ_PITCH.getOrDefault(key, 0f);
        float roll = PROJ_ROLL.getOrDefault(key, 0f);
        if (slot == MOVE_UP_SLOT) {
            projection.teleport(projection.getLocation().add(0, MOVE_STEP, 0));
            KomutechSupport.send(player, "§a向上移动 " + MOVE_STEP + " 格");
        } else if (slot == MOVE_DOWN_SLOT) {
            projection.teleport(projection.getLocation().add(0, -MOVE_STEP, 0));
            KomutechSupport.send(player, "§a向下移动 " + MOVE_STEP + " 格");
        } else if (slot == YAW_LEFT_SLOT) {
            yaw = updateAngle(yaw, -ANGLE_STEP);
            PROJ_YAW.put(key, yaw);
            applyRotation(projection, yaw, pitch, roll);
            KomutechSupport.send(player, "§a向左旋转 " + (int) ANGLE_STEP + "°，当前偏航 " + (int) yaw);
        } else if (slot == YAW_RIGHT_SLOT) {
            yaw = updateAngle(yaw, ANGLE_STEP);
            PROJ_YAW.put(key, yaw);
            applyRotation(projection, yaw, pitch, roll);
            KomutechSupport.send(player, "§a向右旋转 " + (int) ANGLE_STEP + "°，当前偏航 " + (int) yaw);
        } else if (slot == PITCH_DOWN_SLOT) {
            pitch = updateAngle(pitch, -ANGLE_STEP);
            PROJ_PITCH.put(key, pitch);
            applyRotation(projection, yaw, pitch, roll);
            KomutechSupport.send(player, "§a向下俯仰 " + (int) ANGLE_STEP + "°，当前俯仰 " + (int) pitch);
        } else if (slot == PITCH_UP_SLOT) {
            pitch = updateAngle(pitch, ANGLE_STEP);
            PROJ_PITCH.put(key, pitch);
            applyRotation(projection, yaw, pitch, roll);
            KomutechSupport.send(player, "§a向上俯仰 " + (int) ANGLE_STEP + "°，当前俯仰 " + (int) pitch);
        } else if (slot == ROLL_LEFT_SLOT) {
            roll = updateAngle(roll, -ANGLE_STEP);
            PROJ_ROLL.put(key, roll);
            applyRotation(projection, yaw, pitch, roll);
            KomutechSupport.send(player, "§a向左翻滚 " + (int) ANGLE_STEP + "°，当前翻滚 " + (int) roll);
        } else if (slot == ROLL_RIGHT_SLOT) {
            roll = updateAngle(roll, ANGLE_STEP);
            PROJ_ROLL.put(key, roll);
            applyRotation(projection, yaw, pitch, roll);
            KomutechSupport.send(player, "§a向右翻滚 " + (int) ANGLE_STEP + "°，当前翻滚 " + (int) roll);
        } else if (slot == RESET_POS_SLOT) {
            projection.teleport(machine.getLocation().clone().add(0.5, 2.0, 0.5));
            KomutechSupport.send(player, "§a位置已重置");
        } else if (slot == RESET_YAW_SLOT) {
            PROJ_YAW.put(key, 0f);
            applyRotation(projection, 0f, pitch, roll);
            KomutechSupport.send(player, "§a偏航已重置");
        } else if (slot == RESET_PITCH_SLOT) {
            PROJ_PITCH.put(key, 0f);
            applyRotation(projection, yaw, 0f, roll);
            KomutechSupport.send(player, "§a俯仰已重置");
        } else if (slot == RESET_ROLL_SLOT) {
            PROJ_ROLL.put(key, 0f);
            applyRotation(projection, yaw, pitch, 0f);
            KomutechSupport.send(player, "§a翻滚已重置");
        } else if (slot == GLOW_SLOT) {
            projection.setGlowing(!projection.isGlowing());
            KomutechSupport.send(player, projection.isGlowing() ? "§a发光开启" : "§7发光关闭");
        } else if (slot == NAME_SLOT) {
            projection.setCustomNameVisible(!projection.isCustomNameVisible());
            KomutechSupport.send(player, projection.isCustomNameVisible() ? "§a名称显示" : "§7名称隐藏");
        }
        saveStatusToSlot(inv, machine);
        refreshButtons(inv, machine);
    }

    private static ItemDisplay createProjection(Block machine, ItemStack item) {
        Location spawn = machine.getLocation().clone().add(0.5, 2.0, 0.5);
        ItemDisplay display = machine.getWorld().spawn(spawn, ItemDisplay.class, entity -> {
            entity.setItemStack(item.clone());
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setBrightness(new Display.Brightness(15, 15));
            ItemMeta meta = item.getItemMeta();
            entity.setCustomName(meta != null && meta.hasDisplayName()
                ? meta.getDisplayName()
                : item.getType().name().toLowerCase().replace('_', ' '));
            entity.setCustomNameVisible(false);
            entity.getPersistentDataContainer().set(MACHINE_KEY, PersistentDataType.STRING, locationKey(machine));
        });
        applyRotation(display, 0f, 0f, 0f);
        return display;
    }

    private static void applyRotation(ItemDisplay projection, float yaw, float pitch, float roll) {
        try {
            Class<?> vector3f = Class.forName("org.joml.Vector3f");
            Class<?> quaternionf = Class.forName("org.joml.Quaternionf");
            Class<?> axisAngle4f = Class.forName("org.joml.AxisAngle4f");
            Object rotation = quaternionf.getConstructor().newInstance();
            quaternionf.getMethod("rotateY", float.class).invoke(rotation, (float) Math.toRadians(yaw));
            quaternionf.getMethod("rotateX", float.class).invoke(rotation, (float) Math.toRadians(pitch));
            quaternionf.getMethod("rotateZ", float.class).invoke(rotation, (float) Math.toRadians(roll));
            Object translation = vector3f.getConstructor(float.class, float.class, float.class).newInstance(0f, 0f, 0f);
            Object scale = vector3f.getConstructor(float.class, float.class, float.class).newInstance(1f, 1f, 1f);
            Object rightRotation = axisAngle4f.getConstructor(float.class, float.class, float.class, float.class)
                .newInstance(0f, 0f, 1f, 0f);
            Transformation transformation = (Transformation) Transformation.class
                .getConstructor(vector3f, quaternionf, vector3f, axisAngle4f)
                .newInstance(translation, rotation, scale, rightRotation);
            projection.setTransformation(transformation);
        } catch (ReflectiveOperationException exception) {
            projection.setRotation(yaw, pitch);
        }
    }

    private static float updateAngle(float current, float delta) {
        return (current + delta + 360f) % 360f;
    }

    private static void removeProjection(Block block) {
        String key = locationKey(block);
        ItemDisplay projection = PROJECTIONS.get(key);
        if (projection != null && !projection.isDead()) {
            projection.remove();
        }
        PROJECTIONS.remove(key);
        PROJ_YAW.remove(key);
        PROJ_PITCH.remove(key);
        PROJ_ROLL.remove(key);
    }

    private static boolean hasProjection(Block block) {
        String key = locationKey(block);
        ItemDisplay projection = PROJECTIONS.get(key);
        return projection != null && !projection.isDead();
    }

    private static ItemDisplay findProjectionByMachine(Block block) {
        String target = locationKey(block);
        for (Entity entity : block.getWorld().getEntities()) {
            if (!(entity instanceof ItemDisplay display) || display.isDead()) {
                continue;
            }
            String stored = display.getPersistentDataContainer().get(MACHINE_KEY, PersistentDataType.STRING);
            if (target.equals(stored)) {
                return display;
            }
        }
        return null;
    }

    private static Block getTargetBlock(Player player) {
        RayTraceResult result = player.getWorld().rayTraceBlocks(
            player.getEyeLocation(),
            player.getEyeLocation().getDirection(),
            5,
            FluidCollisionMode.NEVER,
            true
        );
        return result == null ? null : result.getHitBlock();
    }

    private static boolean isValidMachine(Block block) {
        if (block == null) {
            return false;
        }
        SlimefunItem sf = MachineScriptHelper.getSfItem(block.getLocation());
        return sf != null && MACHINE_ID.equals(sf.getId());
    }

    private static String locationKey(Block block) {
        Location location = block.getLocation();
        return location.getWorld().getName() + "," + location.getBlockX() + ","
            + location.getBlockY() + "," + location.getBlockZ();
    }

    private static String getMachineKeyFromStatus(Inventory inv) {
        ItemStack status = inv.getItem(STATUS_SLOT);
        if (status == null || status.getType() != Material.PAPER || !status.hasItemMeta()) {
            return null;
        }
        List<String> lore = status.getItemMeta().getLore();
        if (lore == null || lore.isEmpty()) {
            return null;
        }
        try {
            return lore.get(0).replaceAll("§[0-9a-fklmnor]", "").substring(5).trim();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Block getMachineFromStatus(Inventory inv) {
        String key = getMachineKeyFromStatus(inv);
        if (key == null) {
            return null;
        }
        String[] parts = key.split(",");
        if (parts.length != 4) {
            return null;
        }
        org.bukkit.World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        return world.getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    private static void saveStatusToSlot(Inventory inv, Block machine) {
        String key = locationKey(machine);
        boolean has = hasProjection(machine);
        ItemStack status = new ItemStack(Material.PAPER);
        ItemMeta meta = status.getItemMeta();
        meta.setDisplayName("§7机器状态");
        List<String> lore = new ArrayList<>();
        lore.add("§b机器位置: " + key);
        if (has) {
            ItemDisplay projection = PROJECTIONS.get(key);
            lore.add("§bUUID: " + projection.getUniqueId());
            lore.add("§b偏航: " + PROJ_YAW.getOrDefault(key, 0f));
            lore.add("§b俯仰: " + PROJ_PITCH.getOrDefault(key, 0f));
            lore.add("§b翻滚: " + PROJ_ROLL.getOrDefault(key, 0f));
            lore.add("§b发光: " + projection.isGlowing());
            lore.add("§b名称显示: " + projection.isCustomNameVisible());
        } else {
            lore.add("§7无投影");
        }
        meta.setLore(lore);
        status.setItemMeta(meta);
        inv.setItem(STATUS_SLOT, status);
    }

    private static boolean restoreFromStatus(Inventory inv, Block machine) {
        ItemStack status = inv.getItem(STATUS_SLOT);
        if (status == null || status.getType() != Material.PAPER || !status.hasItemMeta()) {
            return false;
        }
        List<String> lore = status.getItemMeta().getLore();
        if (lore == null || lore.size() < 2 || "无投影".equals(lore.get(1).replaceAll("§[0-9a-fklmnor]", ""))) {
            return false;
        }
        try {
            String key = lore.get(0).replaceAll("§[0-9a-fklmnor]", "").substring(5).trim();
            if (!locationKey(machine).equals(key)) {
                return false;
            }
            String uuidStr = null;
            float yaw = 0f;
            float pitch = 0f;
            float roll = 0f;
            boolean glowing = false;
            boolean nameVisible = false;
            for (String line : lore) {
                String plain = line.replaceAll("§[0-9a-fklmnor]", "");
                if (plain.startsWith("UUID:")) {
                    uuidStr = plain.substring(5).trim();
                } else if (plain.startsWith("偏航:")) {
                    yaw = Float.parseFloat(plain.substring(3).trim());
                } else if (plain.startsWith("俯仰:")) {
                    pitch = Float.parseFloat(plain.substring(3).trim());
                } else if (plain.startsWith("翻滚:")) {
                    roll = Float.parseFloat(plain.substring(3).trim());
                } else if (plain.startsWith("发光:")) {
                    glowing = "true".equals(plain.substring(3).trim());
                } else if (plain.startsWith("名称显示:")) {
                    nameVisible = "true".equals(plain.substring(5).trim());
                }
            }
            if (uuidStr == null) {
                return false;
            }
            Entity entity = Bukkit.getEntity(UUID.fromString(uuidStr));
            if (!(entity instanceof ItemDisplay display) || display.isDead()) {
                return false;
            }
            PROJECTIONS.put(key, display);
            PROJ_YAW.put(key, yaw);
            PROJ_PITCH.put(key, pitch);
            PROJ_ROLL.put(key, roll);
            if (glowing) {
                display.setGlowing(true);
            }
            display.setCustomNameVisible(nameVisible);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void refreshButtons(Inventory inv, Block machine) {
        ItemStack displayItem = inv.getItem(ITEM_SLOT);
        boolean has = hasProjection(machine);
        String key = has ? locationKey(machine) : null;
        updateLore(inv, OPEN_SLOT, List.of(
            "§7点击将13号槽物品投影",
            "§7当前物品: " + getItemDisplayName(displayItem),
            has ? "§a投影已开启" : "§7投影未开启"
        ));
        updateLore(inv, CLOSE_SLOT, List.of("§7点击关闭投影", has ? "§a点击关闭" : "§7无投影"));
        updateLore(inv, MOVE_UP_SLOT, List.of("§7向上移动" + (has ? "" : " §c(需先开启)")));
        updateLore(inv, MOVE_DOWN_SLOT, List.of("§7向下移动" + (has ? "" : " §c(需先开启)")));
        updateLore(inv, YAW_LEFT_SLOT, List.of("§7向左旋转" + (has ? " §a当前:" + PROJ_YAW.getOrDefault(key, 0f) : " §c(需先开启)")));
        updateLore(inv, YAW_RIGHT_SLOT, List.of("§7向右旋转" + (has ? " §a当前:" + PROJ_YAW.getOrDefault(key, 0f) : " §c(需先开启)")));
        updateLore(inv, PITCH_DOWN_SLOT, List.of("§7向下俯仰" + (has ? " §a当前:" + PROJ_PITCH.getOrDefault(key, 0f) : " §c(需先开启)")));
        updateLore(inv, PITCH_UP_SLOT, List.of("§7向上俯仰" + (has ? " §a当前:" + PROJ_PITCH.getOrDefault(key, 0f) : " §c(需先开启)")));
        updateLore(inv, ROLL_LEFT_SLOT, List.of("§7向左翻滚" + (has ? " §a当前:" + PROJ_ROLL.getOrDefault(key, 0f) : " §c(需先开启)")));
        updateLore(inv, ROLL_RIGHT_SLOT, List.of("§7向右翻滚" + (has ? " §a当前:" + PROJ_ROLL.getOrDefault(key, 0f) : " §c(需先开启)")));
        updateLore(inv, RESET_POS_SLOT, List.of("§7重置位置" + (has ? "" : " §c(需先开启)")));
        updateLore(inv, RESET_YAW_SLOT, List.of("§7重置偏航" + (has ? "" : " §c(需先开启)")));
        updateLore(inv, RESET_PITCH_SLOT, List.of("§7重置俯仰" + (has ? "" : " §c(需先开启)")));
        updateLore(inv, RESET_ROLL_SLOT, List.of("§7重置翻滚" + (has ? "" : " §c(需先开启)")));
        String glowState = has ? (PROJECTIONS.get(key).isGlowing() ? "§a开启" : "§7关闭") : "§7关闭";
        updateLore(inv, GLOW_SLOT, List.of("§7切换发光", "§7当前: " + glowState + (has ? "" : " §c(需先开启)")));
        String nameState = has ? (PROJECTIONS.get(key).isCustomNameVisible() ? "§a显示" : "§7隐藏") : "§7隐藏";
        updateLore(inv, NAME_SLOT, List.of("§7切换名称", "§7当前: " + nameState + (has ? "" : " §c(需先开启)")));
        updateLore(inv, MANUAL_SLOT, List.of("§7手动检测并绑定当前瞄准的机器"));
        if (machine != null) {
            saveStatusToSlot(inv, machine);
        }
    }

    private static void updateLore(Inventory inv, int slot, List<String> lore) {
        ItemStack item = inv.getItem(slot);
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private static String getItemDisplayName(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "§c空";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName() + " §7(" + item.getType().name() + ")";
        }
        return "§e" + item.getType().name().toLowerCase().replace('_', ' ');
    }
}
