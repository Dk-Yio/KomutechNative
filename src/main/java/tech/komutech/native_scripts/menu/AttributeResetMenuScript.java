package tech.komutech.native_scripts.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechChatInput;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.MenuGuiHelper;

public final class AttributeResetMenuScript implements NativeLifecycleScript, KomutechMenuHandler {

    private static final String TITLE_PLAYER = "§a§l属性查看 - 个人模式";
    private static final String TITLE_ADMIN_LIST = "§c§l属性管理 - 管理员模式";
    private static final String TITLE_ADMIN_VIEW = "§d§l属性详情 - ";
    private static final int[] BORDER = {0, 1, 2, 3, 5, 6, 7, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47, 48, 50, 51, 52};
    private static final int[] ATTR_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20};

    private record AttrDef(String id, Attribute attribute, Material mat, String display) {
    }

    private record AdminState(int page, int total, Map<Integer, String> slotMap) {
    }

    private static final List<AttrDef> ATTRIBUTES = buildAttributes();

    private final Map<Player, AdminState> openPlayers = new HashMap<>();
    private Plugin plugin;

    private static List<AttrDef> buildAttributes() {
        List<AttrDef> list = new ArrayList<>();
        addAttr(list, "生命值", "GENERIC_MAX_HEALTH", Material.APPLE, "§c生命值");
        addAttr(list, "攻击伤害", "GENERIC_ATTACK_DAMAGE", Material.IRON_SWORD, "§c攻击伤害");
        addAttr(list, "移动速度", "GENERIC_MOVEMENT_SPEED", Material.FEATHER, "§f移动速度");
        addAttr(list, "护甲值", "GENERIC_ARMOR", Material.IRON_CHESTPLATE, "§a护甲值");
        addAttr(list, "盔甲韧性", "GENERIC_ARMOR_TOUGHNESS", Material.IRON_LEGGINGS, "§a盔甲韧性");
        addAttr(list, "攻击速度", "GENERIC_ATTACK_SPEED", Material.WOODEN_SWORD, "§e攻击速度");
        addAttr(list, "击退抗性", "GENERIC_KNOCKBACK_RESISTANCE", Material.SHIELD, "§7击退抗性");
        return list;
    }

    private static void addAttr(List<AttrDef> list, String id, String name, Material mat, String display) {
        try {
            list.add(new AttrDef(id, Attribute.valueOf(name), mat, display));
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void registerLifecycle(Plugin plugin) {
        this.plugin = plugin;
        KomutechMenuRouter.registerHandler(this);
    }

    @Override
    public boolean handles(InventoryView view) {
        String title = view.getTitle();
        return TITLE_PLAYER.equals(title) || TITLE_ADMIN_LIST.equals(title) || title.startsWith(TITLE_ADMIN_VIEW);
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onButtonGroupClick".equals(functionName) && args[0] instanceof Player player) {
            openPlayerMenu(player);
            return true;
        }
        return null;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String title = event.getView().getTitle();
        if (!TITLE_PLAYER.equals(title) && !TITLE_ADMIN_LIST.equals(title) && !title.startsWith(TITLE_ADMIN_VIEW)) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getSlot();
        if (TITLE_PLAYER.equals(title)) {
            if (slot == 53) {
                int removed = resetPlayerAttrs(player);
                KomutechSupport.send(player, "§a已重置自身属性，移除了 " + removed + " 个修饰器。");
                player.closeInventory();
            } else if (slot == 49) {
                player.closeInventory();
            } else if (slot == 8) {
                if (!isAdmin(player)) {
                    KomutechSupport.send(player, "§c无管理员权限");
                    return;
                }
                openAdminList(player, 1);
            }
            return;
        }
        if (TITLE_ADMIN_LIST.equals(title)) {
            AdminState state = openPlayers.get(player);
            int page = state == null ? 1 : state.page();
            if (slot == 53 && isAdmin(player)) {
                KomutechSupport.send(player, "§c确定要重置所有在线玩家属性吗？输入 §6确认重置 §c以确认:");
                KomutechChatInput.waitFor(player, input -> {
                    if ("确认重置".equals(input)) {
                        int total = 0;
                        for (Player online : plugin.getServer().getOnlinePlayers()) {
                            total += resetPlayerAttrs(online);
                        }
                        KomutechSupport.send(player, "§a已重置所有在线玩家属性，共移除 " + total + " 个修饰器。");
                        player.closeInventory();
                    } else {
                        KomutechSupport.send(player, "§c操作已取消");
                    }
                });
            } else if (slot == 49) {
                player.closeInventory();
            } else if (slot == 8) {
                openPlayerMenu(player);
            } else if (slot == 48 && page > 1) {
                openAdminList(player, page - 1);
            } else if (slot == 50 && state != null && page < state.total()) {
                openAdminList(player, page + 1);
            } else if (state != null && state.slotMap().containsKey(slot)) {
                String targetName = state.slotMap().get(slot);
                Player target = plugin.getServer().getPlayer(targetName);
                if (target == null) {
                    KomutechSupport.send(player, "§c玩家离线");
                    return;
                }
                if (event.isShiftClick() && event.isLeftClick()) {
                    KomutechSupport.send(player, "§c确定要重置玩家 §e" + targetName + " §c的属性吗？输入 §6确认重置 §c以确认:");
                    KomutechChatInput.waitFor(player, input -> {
                        if ("确认重置".equals(input)) {
                            KomutechSupport.send(player, "§a已重置玩家 " + targetName + " 的属性，移除了 "
                                + resetPlayerAttrs(target) + " 个修饰器。");
                            openAdminList(player, page);
                        } else {
                            KomutechSupport.send(player, "§c操作已取消");
                        }
                    });
                } else if (event.isLeftClick()) {
                    openAdminView(player, target);
                }
            }
            return;
        }
        if (title.startsWith(TITLE_ADMIN_VIEW)) {
            String targetName = title.substring(TITLE_ADMIN_VIEW.length());
            Player target = plugin.getServer().getPlayer(targetName);
            if (slot == 49) {
                openAdminList(player, 1);
            } else if (slot == 53 && target != null) {
                KomutechSupport.send(player, "§a已重置玩家 " + targetName + " 的属性，移除了 "
                    + resetPlayerAttrs(target) + " 个修饰器。");
                player.closeInventory();
            }
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            openPlayers.remove(player);
        }
    }

    private void openPlayerMenu(Player player) {
        Inventory inv = MenuGuiHelper.create(54, TITLE_PLAYER);
        MenuGuiHelper.applyBorder(inv, BORDER);
        inv.setItem(4, MenuGuiHelper.item(Material.PLAYER_HEAD, "§6" + player.getName(), List.of("§7你的属性面板")));
        inv.setItem(8, MenuGuiHelper.item(Material.COMPASS, "§6管理员模式", List.of("§7切换至管理员界面")));
        inv.setItem(49, MenuGuiHelper.item(Material.BARRIER, "§c关闭", List.of()));
        inv.setItem(53, MenuGuiHelper.item(Material.BARRIER, "§c§l重置所有属性", List.of("§7点击移除自身所有属性加成")));
        for (int i = 0; i < ATTRIBUTES.size() && i < ATTR_SLOTS.length; i++) {
            AttrDef def = ATTRIBUTES.get(i);
            inv.setItem(ATTR_SLOTS[i], MenuGuiHelper.item(def.mat(), def.display(), getAttrLore(player, def)));
        }
        player.openInventory(inv);
    }

    private void openAdminList(Player player, int page) {
        if (!isAdmin(player)) {
            openPlayerMenu(player);
            return;
        }
        List<String> names = plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        int perPage = 28;
        int total = Math.max(1, (int) Math.ceil(names.size() / (double) perPage));
        page = Math.max(1, Math.min(page, total));
        int start = (page - 1) * perPage;
        Inventory inv = MenuGuiHelper.create(54, TITLE_ADMIN_LIST);
        MenuGuiHelper.applyBorder(inv, BORDER);
        inv.setItem(4, MenuGuiHelper.item(Material.NETHER_STAR, "§c玩家列表",
            List.of("§7在线: " + names.size(), "§7第 " + page + "/" + total + " 页")));
        inv.setItem(8, MenuGuiHelper.item(Material.COMPASS, "§a个人模式", List.of("§7返回个人属性菜单")));
        inv.setItem(49, MenuGuiHelper.item(Material.BARRIER, "§c关闭", List.of()));
        inv.setItem(53, MenuGuiHelper.item(Material.TNT, "§c§l一键重置所有在线玩家", List.of("§7点击移除所有在线玩家属性加成")));
        Map<Integer, String> slotMap = new HashMap<>();
        int[] rows = {10, 19, 28, 37};
        int idx = 0;
        for (int row : rows) {
            for (int col = 0; col < 7 && start + idx < names.size(); col++) {
                String name = names.get(start + idx);
                int slot = row + col;
                inv.setItem(slot, MenuGuiHelper.item(Material.PLAYER_HEAD, "§e" + name,
                    List.of("§7左键查看", "§7Shift+左键重置")));
                slotMap.put(slot, name);
                idx++;
            }
        }
        if (page > 1) {
            inv.setItem(48, MenuGuiHelper.item(Material.ARROW, "§a上一页", List.of()));
        }
        if (page < total) {
            inv.setItem(50, MenuGuiHelper.item(Material.ARROW, "§a下一页", List.of()));
        }
        player.openInventory(inv);
        openPlayers.put(player, new AdminState(page, total, slotMap));
    }

    private void openAdminView(Player viewer, Player target) {
        Inventory inv = MenuGuiHelper.create(54, TITLE_ADMIN_VIEW + target.getName());
        MenuGuiHelper.applyBorder(inv, BORDER);
        inv.setItem(4, MenuGuiHelper.item(Material.PLAYER_HEAD, "§6" + target.getName(), List.of("§7属性详情")));
        inv.setItem(49, MenuGuiHelper.item(Material.ARROW, "§a返回列表", List.of()));
        inv.setItem(53, MenuGuiHelper.item(Material.BARRIER, "§c§l重置此玩家属性", List.of("§7点击移除该玩家所有加成")));
        for (int i = 0; i < ATTRIBUTES.size() && i < ATTR_SLOTS.length; i++) {
            AttrDef def = ATTRIBUTES.get(i);
            inv.setItem(ATTR_SLOTS[i], MenuGuiHelper.item(def.mat(), def.display(), getAttrLore(target, def)));
        }
        viewer.openInventory(inv);
    }

    private static List<String> getAttrLore(Player player, AttrDef def) {
        if (def.attribute() == null) {
            return List.of("§c属性不可用");
        }
        AttributeInstance inst = player.getAttribute(def.attribute());
        if (inst == null) {
            return List.of("§c属性未找到");
        }
        List<String> lines = new ArrayList<>();
        lines.add("§7当前值: §f" + String.format("%.2f", inst.getValue())
            + "§7（基础 " + String.format("%.2f", inst.getBaseValue()) + "）");
        if (inst.getModifiers().isEmpty()) {
            lines.add("§7无额外加成");
        } else {
            lines.add("§7修饰器:");
            for (AttributeModifier mod : inst.getModifiers()) {
                lines.add("  §8- §7" + mod.getName() + " §8| §a" + mod.getAmount());
            }
        }
        return lines;
    }

    private static int resetPlayerAttrs(Player player) {
        int count = 0;
        for (AttrDef def : ATTRIBUTES) {
            if (def.attribute() == null) {
                continue;
            }
            AttributeInstance inst = player.getAttribute(def.attribute());
            if (inst == null) {
                continue;
            }
            for (AttributeModifier mod : new ArrayList<>(inst.getModifiers())) {
                inst.removeModifier(mod);
                count++;
            }
        }
        return count;
    }

    private static boolean isAdmin(Player player) {
        return player.isOp() || "Komu_A".equals(player.getName());
    }
}
