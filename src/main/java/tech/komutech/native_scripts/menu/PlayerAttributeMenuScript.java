package tech.komutech.native_scripts.menu;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.AttributeApplier;
import tech.komutech.native_scripts.support.AttributePointLimits;
import tech.komutech.native_scripts.support.CultivationMath;
import tech.komutech.native_scripts.support.KomutechChatInput;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.MenuGuiHelper;
import tech.komutech.native_scripts.support.PlayerAttributeStore;

public final class PlayerAttributeMenuScript implements NativeLifecycleScript, KomutechMenuHandler {

    private static final String TITLE_PLAYER = "§a§l玩家属性 - 个人模式";
    private static final String TITLE_ADMIN_LIST = "§c§l玩家属性 - 管理员模式";
    private static final String TITLE_ADMIN_VIEW = "§d§l玩家属性详情 - ";
    private static final String ADMIN_PASSWORD = "0108";

    private static final int[] BORDER = {0, 1, 2, 3, 5, 7, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47, 52};
    private static final int[] ADMIN_SLOTS = {
        10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43
    };

    private static final Map<Integer, String> ATTR_SLOTS = Map.ofEntries(
        Map.entry(10, "功德"),
        Map.entry(12, "煞气"),
        Map.entry(14, "根基"),
        Map.entry(15, "悟性"),
        Map.entry(16, "根骨"),
        Map.entry(31, "灵力"),
        Map.entry(23, "血量"),
        Map.entry(24, "攻击力"),
        Map.entry(25, "防御力"),
        Map.entry(32, "速度"),
        Map.entry(33, "灵识"),
        Map.entry(34, "灵气获取"),
        Map.entry(6, "属性点"),
        Map.entry(22, "灵气"),
        Map.entry(13, "修为"),
        Map.entry(40, "灵根属性")
    );
    private static final Map<Integer, String> UPGRADE_ATTRS = Map.of(
        23, "血量", 24, "攻击力", 25, "防御力", 32, "速度", 34, "灵气获取"
    );
    private static final Set<String> POINT_ATTRS = Set.of("血量", "攻击力", "防御力", "速度", "灵识", "灵气获取");
    private static final long TOGGLE_DEBOUNCE_MS = 350L;
    private static final Map<Integer, String> QUALITY_SLOTS = Map.of(
        20, "金", 28, "木", 30, "水", 37, "火", 39, "土", 29, "雷", 19, "风", 21, "冰", 11, "光", 38, "暗"
    );

    private final Map<Player, Session> openPlayers = new HashMap<>();
    private final Set<Player> awaiting = new HashSet<>();
    private final Map<UUID, Long> toggleReadyAt = new ConcurrentHashMap<>();
    private Plugin plugin;

    @Override
    public void registerLifecycle(Plugin plugin) {
        this.plugin = plugin;
        AttributeApplier.registerCompatListeners(plugin);
        KomutechMenuRouter.registerHandler(this);
        // 热加载/重启后把已在线玩家的溢出加成重新截断到上限
        Bukkit.getScheduler().runTaskLater(plugin, AttributeApplier::reapplyAllOnline, 40L);
    }

    @Override
    public boolean handles(InventoryView view) {
        String title = MenuGuiHelper.legacyTitle(view);
        return TITLE_PLAYER.equals(title) || TITLE_ADMIN_LIST.equals(title) || title.startsWith(TITLE_ADMIN_VIEW);
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onButtonGroupClick".equals(functionName) && args.length > 0 && args[0] instanceof Player player) {
            openPlayer(player);
            return true;
        }
        return null;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        var view = event.getView();
        String title = MenuGuiHelper.legacyTitle(view);
        if (!TITLE_PLAYER.equals(title) && !TITLE_ADMIN_LIST.equals(title) && !title.startsWith(TITLE_ADMIN_VIEW)) {
            return;
        }
        event.setCancelled(true);
        Session session = openPlayers.get(player);
        if (session == null) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType() == Material.AIR) {
            return;
        }
        int slot = event.getSlot();
        if (TITLE_PLAYER.equals(title)) {
            handlePlayerClick(player, session, slot, event);
        } else if (TITLE_ADMIN_LIST.equals(title)) {
            handleAdminListClick(player, session, slot, event);
        } else {
            handleAdminViewClick(player, session, slot, event);
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        String title = MenuGuiHelper.legacyTitle(event.getView());
        if (!TITLE_PLAYER.equals(title) && !TITLE_ADMIN_LIST.equals(title) && !title.startsWith(TITLE_ADMIN_VIEW)) {
            return;
        }
        if (player.getOpenInventory().getTopInventory() == event.getInventory()) {
            return;
        }
        openPlayers.remove(player);
        toggleReadyAt.remove(player.getUniqueId());
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String title = MenuGuiHelper.legacyTitle(event.getView());
        if (!TITLE_PLAYER.equals(title) && !TITLE_ADMIN_LIST.equals(title) && !title.startsWith(TITLE_ADMIN_VIEW)) {
            return;
        }
        event.setCancelled(true);
    }

    private void handlePlayerClick(Player player, Session session, int slot, InventoryClickEvent event) {
        if (slot == 51 && event.isLeftClick() && !event.isShiftClick() && session.tempData != null) {
            if (!allowToggle(player)) {
                return;
            }
            toggleAllAttributes(player, session.tempData);
            openPlayerWithTemp(player, session.tempData);
            return;
        }
        // Shift+左键：切换启用（防抖，避免按住/重开菜单连触）
        if (event.isShiftClick() && event.isLeftClick()) {
            String key = ATTR_SLOTS.get(slot);
            if (key != null && CultivationMath.SWITCHABLE_ATTRS.contains(key) && session.tempData != null) {
                if (!allowToggle(player)) {
                    return;
                }
                String enableKey = key + "_启用";
                boolean enabled = PlayerAttributeStore.getBoolean(session.tempData, enableKey, true);
                session.tempData.put(enableKey, !enabled);
                KomutechSupport.send(player, "§a" + key + " 已" + (!enabled ? "启用" : "禁用"));
                openPlayerWithTemp(player, session.tempData);
            }
            return;
        }
        if (slot == 8) {
            openAdminList(player, 1);
            return;
        }
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        if (slot == 53 && session.originalData != null && session.tempData != null) {
            // 个人模式：清除管理员豁免并强制按配置上限截断，保证显示与实际一致
            AttributePointLimits.clearExempt(session.tempData);
            AttributeApplier.applyPlayerAttributes(player, session.tempData, false);
            PlayerAttributeStore.save(player.getName(), session.tempData);
            KomutechSupport.send(player, "§a加点已保存并应用！");
            openPlayer(player);
            return;
        }
        if (UPGRADE_ATTRS.containsKey(slot) && session.originalData != null && session.tempData != null) {
            adjustUpgradeAttr(player, session, UPGRADE_ATTRS.get(slot), event);
            return;
        }
        if (slot == 33 && session.originalData != null && session.tempData != null) {
            if (!CultivationMath.isGoldenCoreRealm(session.tempData)) {
                KomutechSupport.send(player, "§c修为未达金丹期，无法分配灵识");
                return;
            }
            adjustUpgradeAttr(player, session, "灵识", event);
        }
    }

    private void adjustUpgradeAttr(Player player, Session session, String key, InventoryClickEvent event) {
        int original = PlayerAttributeStore.getInt(session.originalData, key, 0);
        int current = PlayerAttributeStore.getInt(session.tempData, key, 0);
        int points = PlayerAttributeStore.getInt(session.tempData, "属性点", 0);
        if (event.isShiftClick() && event.isRightClick()) {
            int want = Math.min(10, points);
            if (want <= 0) {
                KomutechSupport.send(player, "§c属性点不足");
                return;
            }
            int added = 0;
            while (added < want) {
                if (!AttributePointLimits.canAllocatePoint(session.tempData, key, current + added)) {
                    break;
                }
                added++;
            }
            if (added <= 0) {
                KomutechSupport.send(player, "§c" + key + "已达上限（" + AttributePointLimits.limitLabel(session.tempData, key) + "）");
                return;
            }
            session.tempData.put(key, current + added);
            session.tempData.put("属性点", points - added);
            if (added < 10) {
                KomutechSupport.send(player, "§e已加点 §a+" + added + " §7（点数或上限不足10）");
            }
            openPlayerWithTemp(player, session.tempData);
            return;
        }
        if (event.isLeftClick() && !event.isShiftClick()) {
            if (points <= 0) {
                KomutechSupport.send(player, "§c属性点不足");
            } else if (!AttributePointLimits.canAllocatePoint(session.tempData, key, current)) {
                KomutechSupport.send(player, "§c" + key + "已达上限（" + AttributePointLimits.limitLabel(session.tempData, key) + "）");
            } else {
                session.tempData.put(key, current + 1);
                session.tempData.put("属性点", points - 1);
            }
            openPlayerWithTemp(player, session.tempData);
            return;
        }
        if (event.isRightClick() && !event.isShiftClick()) {
            if (current > original) {
                session.tempData.put(key, current - 1);
                session.tempData.put("属性点", points + 1);
            } else {
                KomutechSupport.send(player, "§c不能低于原始值");
            }
            openPlayerWithTemp(player, session.tempData);
        }
    }

    private boolean allowToggle(Player player) {
        long now = System.currentTimeMillis();
        Long ready = toggleReadyAt.get(player.getUniqueId());
        if (ready != null && now < ready) {
            return false;
        }
        toggleReadyAt.put(player.getUniqueId(), now + TOGGLE_DEBOUNCE_MS);
        return true;
    }

    private void handleAdminListClick(Player player, Session session, int slot, InventoryClickEvent event) {
        if (slot == 8) {
            openPlayer(player);
            return;
        }
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        if (slot == 48 && session.page > 1) {
            openAdminList(player, session.page - 1);
            return;
        }
        if (slot == 50 && session.page < session.totalPages) {
            openAdminList(player, session.page + 1);
            return;
        }
        if (slot == 53 && event.isLeftClick()) {
            if (awaiting.contains(player)) {
                return;
            }
            awaiting.add(player);
            KomutechSupport.send(player, "§c请输入管理员密码:");
            KomutechChatInput.waitFor(player, input -> {
                awaiting.remove(player);
                if (!ADMIN_PASSWORD.equals(stripColor(input).trim())) {
                    KomutechSupport.send(player, "§c密码错误");
                    return;
                }
                int count = 0;
                try {
                    var dir = PlayerAttributeStore.fileFor("x").getParent();
                    if (Files.exists(dir)) {
                        try (var stream = Files.list(dir)) {
                            for (var path : stream.toList()) {
                                String name = path.getFileName().toString();
                                if (!name.startsWith("[") || !name.endsWith("].json")) {
                                    continue;
                                }
                                String playerName = name.substring(1, name.length() - 6);
                                Player target = Bukkit.getPlayer(playerName);
                                if (target != null && target.isOnline()) {
                                    AttributeApplier.clearSwitchable(target);
                                    KomutechSupport.send(target, "§c你的玩家属性数据已被管理员重置。");
                                }
                                Files.deleteIfExists(path);
                                count++;
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
                KomutechSupport.send(player, "§a已删除 " + count + " 个玩家数据。");
                openAdminList(player, session.page);
            });
            return;
        }
        String targetName = session.slotMap.get(slot);
        if (targetName == null) {
            return;
        }
        if (event.isShiftClick() && event.isRightClick()) {
            if (awaiting.contains(player)) {
                return;
            }
            awaiting.add(player);
            KomutechSupport.send(player, "§c输入 \"确认删除\" 以删除 " + targetName + ":");
            KomutechChatInput.waitFor(player, input -> {
                awaiting.remove(player);
                if (!"确认删除".equals(stripColor(input).trim())) {
                    KomutechSupport.send(player, "§c操作取消");
                    return;
                }
                Player target = Bukkit.getPlayer(targetName);
                if (target != null && target.isOnline()) {
                    AttributeApplier.clearSwitchable(target);
                    KomutechSupport.send(target, "§c你的玩家属性数据已被管理员移除。");
                }
                PlayerAttributeStore.delete(targetName);
                KomutechSupport.send(player, "§a已删除玩家 " + targetName + " 的属性数据。");
                openAdminList(player, session.page);
            });
            return;
        }
        if (event.isLeftClick() && !event.isShiftClick()) {
            openAdminView(player, targetName);
        }
    }

    private void handleAdminViewClick(Player player, Session session, int slot, InventoryClickEvent event) {
        if (session.viewData == null || session.viewPlayerName == null) {
            return;
        }
        if (slot == 8) {
            openPlayer(player);
            return;
        }
        if (slot == 48) {
            openAdminList(player, 1);
            return;
        }
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        if (slot == 53) {
            PlayerAttributeStore.save(session.viewPlayerName, session.viewData);
            Player target = Bukkit.getPlayer(session.viewPlayerName);
            if (target != null && target.isOnline()) {
                AttributeApplier.applyPlayerAttributes(target, session.viewData);
                KomutechSupport.send(player, "§a修改已保存并应用。");
            } else {
                KomutechSupport.send(player, "§a修改已保存，玩家上线后将自动生效。");
            }
            openAdminView(player, session.viewPlayerName);
            return;
        }
        if (event.isRightClick()) {
            if (slot == 7 && session.viewData.containsKey("突破失败")) {
                session.viewData.put("突破失败", !PlayerAttributeStore.getBoolean(session.viewData, "突破失败", false));
                refreshAdminView(player, session);
                return;
            }
            handleAdminRightClick(player, session, slot);
        }
    }

    private void handleAdminRightClick(Player player, Session session, int slot) {
        if (awaiting.contains(player)) {
            return;
        }
        Map<String, Object> data = session.viewData;
        String targetName = session.viewPlayerName;
        if (QUALITY_SLOTS.containsKey(slot)) {
            String element = QUALITY_SLOTS.get(slot);
            Map<String, Object> qualities = PlayerAttributeStore.getMap(data, "灵根品质");
            if (!qualities.containsKey(element)) {
                return;
            }
            handleQualityModify(player, targetName, data, element, qualities);
            return;
        }
        if (slot == 40 && PlayerAttributeStore.getString(data, "灵根", null) != null) {
            handleLinggenAttrModify(player, targetName, data);
            return;
        }
        String key = ATTR_SLOTS.get(slot);
        if (key == null || (!"根基".equals(key) && !data.containsKey(key))) {
            return;
        }
        if ("修为".equals(key)) {
            KomutechSupport.send(player, "§c修为不可直接修改，请通过修改灵气值来调整修为。");
            return;
        }
        if ("根基".equals(key)) {
            handleFoundationModify(player, targetName, data);
            return;
        }
        if (slot == 15 || slot == 16) {
            handleWuxingGenguModify(player, targetName, data, key);
            return;
        }
        if (slot == 32) {
            handleSpeedModify(player, targetName, data);
            return;
        }
        if (slot == 22 && data.containsKey("灵气")) {
            handleSpiritModify(player, targetName, data);
            return;
        }
        if (slot == 31 && data.containsKey("灵力")) {
            handleLingliBonusModify(player, targetName, data);
            return;
        }
        handleCommonAttrModify(player, targetName, data, key);
    }

    private void handleQualityModify(Player player, String targetName, Map<String, Object> data,
                                     String element, Map<String, Object> qualities) {
        awaiting.add(player);
        KomutechSupport.send(player, "§a输入" + element + "品质（0~1）:");
        KomutechChatInput.waitFor(player, input -> {
            awaiting.remove(player);
            if ("cancel".equalsIgnoreCase(stripColor(input).trim())) {
                return;
            }
            double value;
            try {
                value = Double.parseDouble(stripColor(input).trim());
            } catch (NumberFormatException e) {
                KomutechSupport.send(player, "§c无效数值");
                return;
            }
            if (value < 0 || value > 1) {
                KomutechSupport.send(player, "§c无效数值");
                return;
            }
            qualities.put(element, value);
            data.put("灵根品质", qualities);
            double total = 0;
            for (String el : QUALITY_SLOTS.values()) {
                total += PlayerAttributeStore.getDouble(qualities, el, 0);
            }
            data.put("总品质", Math.round(total * 100.0) / 100.0);
            List<String> elements = CultivationMath.splitLinggen(PlayerAttributeStore.getString(data, "灵根", ""));
            data.put("灵根属性", CultivationMath.computeLinggenAttr(elements, total));
            syncLingliBase(data);
            refreshAdminView(player, openSession(player, targetName, data));
        });
    }

    private void handleSpeedModify(Player player, String targetName, Map<String, Object> data) {
        awaiting.add(player);
        int currentPoints = PlayerAttributeStore.getInt(data, "速度", 0);
        List<String> elements = CultivationMath.splitLinggen(PlayerAttributeStore.getString(data, "灵根", ""));
        double quality = PlayerAttributeStore.getDouble(data, "总品质", 0.01);
        double bonus = CultivationMath.computeActualValue(currentPoints, CultivationMath.baseGrowth.getOrDefault("速度", 0.1),
            CultivationMath.getLinggenFinalCoefficient(elements, quality, "速度"),
            CultivationMath.getRealmCoefficient(data), quality, PlayerAttributeStore.getDouble(data, "根骨", 1));
        KomutechSupport.send(player, "§a当前速度点数: §e" + currentPoints + " §7(速度加成: §e+" + String.format("%.2f", bonus) + "%§7)");
        KomutechSupport.send(player, "§a请输入要设置的属性点数量（整数）:");
        KomutechChatInput.waitFor(player, input -> {
            awaiting.remove(player);
            if ("cancel".equalsIgnoreCase(stripColor(input).trim())) {
                return;
            }
            int value;
            try {
                value = Integer.parseInt(stripColor(input).trim());
            } catch (NumberFormatException e) {
                KomutechSupport.send(player, "§c点数必须为非负整数");
                return;
            }
            if (value < 0) {
                KomutechSupport.send(player, "§c点数必须为非负整数");
                return;
            }
            data.put("速度", value);
            AttributePointLimits.markAdminExempt(data);
            refreshAdminView(player, openSession(player, targetName, data));
        });
    }

    private void handleSpiritModify(Player player, String targetName, Map<String, Object> data) {
        awaiting.add(player);
        KomutechSupport.send(player, "§a请输入新的灵气值（直接输入数字如 100，或完整格式如 100/1000）:");
        KomutechChatInput.waitFor(player, input -> {
            awaiting.remove(player);
            if ("cancel".equalsIgnoreCase(stripColor(input).trim())) {
                return;
            }
            String trimmed = stripColor(input).trim();
            String newSpirit;
            if (trimmed.matches("\\d+")) {
                long current = Long.parseLong(trimmed);
                CultivationMath.CultivationInfo info = CultivationMath.getCultivationInfo(current);
                newSpirit = current + "/" + info.maxStr();
                data.put("修为", info.stage());
            } else if (trimmed.matches("\\d+/\\d+") || "無".equals(trimmed)) {
                newSpirit = trimmed;
                var match = java.util.regex.Pattern.compile("^(\\d+)/").matcher(trimmed);
                if (match.find()) {
                    data.put("修为", CultivationMath.getCultivationInfo(Long.parseLong(match.group(1))).stage());
                }
            } else {
                KomutechSupport.send(player, "§c格式错误");
                return;
            }
            data.put("灵气", newSpirit);
            syncLingliBase(data);
            refreshAdminView(player, openSession(player, targetName, data));
        });
    }

    private void handleLingliBonusModify(Player player, String targetName, Map<String, Object> data) {
        PlayerAttributeStore.Lingli ling = PlayerAttributeStore.parseLingli(data.get("灵力"));
        awaiting.add(player);
        KomutechSupport.send(player, "§a当前灵力: §e" + (int) ling.current() + "/" + (int) ling.totalMax()
            + " §7(额外: " + ling.bonus() + ")");
        KomutechSupport.send(player, "§a请输入新的额外加成值（整数，可正可负）:");
        KomutechChatInput.waitFor(player, input -> {
            awaiting.remove(player);
            if ("cancel".equalsIgnoreCase(stripColor(input).trim())) {
                return;
            }
            int newBonus;
            try {
                newBonus = Integer.parseInt(stripColor(input).trim());
            } catch (NumberFormatException e) {
                KomutechSupport.send(player, "§c无效数字");
                return;
            }
            PlayerAttributeStore.Lingli current = PlayerAttributeStore.parseLingli(data.get("灵力"));
            data.put("灵力", PlayerAttributeStore.formatLingli(
                new PlayerAttributeStore.Lingli(current.current(), current.max(), newBonus)));
            refreshAdminView(player, openSession(player, targetName, data));
        });
    }

    private void handleLinggenAttrModify(Player player, String targetName, Map<String, Object> data) {
        awaiting.add(player);
        KomutechSupport.send(player, "§a输入新灵根（用顿号分隔，或输入“混沌”）:");
        KomutechChatInput.waitFor(player, input -> {
            awaiting.remove(player);
            if ("cancel".equalsIgnoreCase(stripColor(input).trim())) {
                return;
            }
            String normalized = stripColor(input).trim();
            if ("混沌".equals(normalized) || "混沌灵根".equals(normalized)) {
                data.put("灵根", String.join("、", CultivationMath.VALID_LINGGEN));
                Map<String, Object> qualities = new HashMap<>();
                for (String element : CultivationMath.VALID_LINGGEN) {
                    qualities.put(element, 0.1);
                }
                data.put("灵根品质", qualities);
                data.put("总品质", 1.0);
                data.put("灵根属性", "混沌灵根");
                syncLingliBase(data);
                refreshAdminView(player, openSession(player, targetName, data));
                return;
            }
            if (!normalized.matches(".*[、，\\s].*")) {
                normalized = String.join("、", normalized.split(""));
            } else {
                normalized = String.join("、", CultivationMath.splitLinggen(normalized.replace('，', '、')));
            }
            List<String> newElements = CultivationMath.splitLinggen(normalized);
            List<String> invalid = newElements.stream().filter(e -> !CultivationMath.VALID_LINGGEN.contains(e)).toList();
            if (!invalid.isEmpty()) {
                KomutechSupport.send(player, "§c无效的灵根: " + String.join("、", invalid) + "，请重新输入");
                return;
            }
            List<String> oldElements = CultivationMath.splitLinggen(PlayerAttributeStore.getString(data, "灵根", ""));
            data.put("灵根", normalized);
            Map<String, Object> qualities = PlayerAttributeStore.getMap(data, "灵根品质");
            oldElements.stream().filter(e -> !newElements.contains(e)).forEach(qualities::remove);
            for (String element : newElements) {
                if (!oldElements.contains(element)) {
                    qualities.put(element, isMutatedLinggen(element) ? 1.0 : 0.01);
                }
            }
            data.put("灵根品质", qualities);
            double total = newElements.stream().mapToDouble(e -> PlayerAttributeStore.getDouble(qualities, e, 0)).sum();
            data.put("总品质", Math.round(total * 100.0) / 100.0);
            data.put("灵根属性", CultivationMath.computeLinggenAttr(newElements, total));
            syncLingliBase(data);
            refreshAdminView(player, openSession(player, targetName, data));
        });
    }

    private void handleCommonAttrModify(Player player, String targetName, Map<String, Object> data, String key) {
        awaiting.add(player);
        KomutechSupport.send(player, "§a请输入新的" + key + "值:");
        KomutechChatInput.waitFor(player, input -> {
            awaiting.remove(player);
            if ("cancel".equalsIgnoreCase(stripColor(input).trim())) {
                return;
            }
            int value;
            try {
                value = Integer.parseInt(stripColor(input).trim());
            } catch (NumberFormatException e) {
                KomutechSupport.send(player, "§c无效数字");
                return;
            }
            data.put(key, value);
            if (AttributePointLimits.isLimited(key)) {
                AttributePointLimits.markAdminExempt(data);
            }
            refreshAdminView(player, openSession(player, targetName, data));
        });
    }

    private void handleFoundationModify(Player player, String targetName, Map<String, Object> data) {
        awaiting.add(player);
        Map<String, Object> foundation = PlayerAttributeStore.getMap(data, "根基");
        KomutechSupport.send(player, "§a当前溢出的灵气: §e" + foundation.getOrDefault("溢出的灵气", "0/0"));
        KomutechSupport.send(player, "§a请输入新的溢出灵气值（格式如 12.5/50）:");
        KomutechChatInput.waitFor(player, input -> {
            awaiting.remove(player);
            if ("cancel".equalsIgnoreCase(stripColor(input).trim())) {
                return;
            }
            String trimmed = stripColor(input).trim();
            if (!trimmed.matches("\\d+\\.?\\d*/\\d+\\.?\\d*")) {
                KomutechSupport.send(player, "§c格式错误，正确格式如 12.5/50");
                return;
            }
            Map<String, Object> updated = PlayerAttributeStore.getMap(data, "根基");
            updated.put("溢出的灵气", trimmed);
            String[] parts = trimmed.split("/");
            double current = Double.parseDouble(parts[0]);
            double max = Double.parseDouble(parts[1]);
            updated.put("稳固值", Math.min(25, (int) Math.floor(current / (max * 0.01))));
            data.put("根基", updated);
            refreshAdminView(player, openSession(player, targetName, data));
        });
    }

    private void handleWuxingGenguModify(Player player, String targetName, Map<String, Object> data, String key) {
        awaiting.add(player);
        KomutechSupport.send(player, "§a请输入新的" + key + "值（当前: " + data.get(key) + "，需≤10）:");
        KomutechChatInput.waitFor(player, input -> {
            awaiting.remove(player);
            if ("cancel".equalsIgnoreCase(stripColor(input).trim())) {
                return;
            }
            double value;
            try {
                value = Double.parseDouble(stripColor(input).trim());
            } catch (NumberFormatException e) {
                KomutechSupport.send(player, "§c无效数值（必须≤10）");
                return;
            }
            if (value > 10) {
                KomutechSupport.send(player, "§c无效数值（必须≤10）");
                return;
            }
            data.put(key, Math.round(value * 100.0) / 100.0);
            refreshAdminView(player, openSession(player, targetName, data));
        });
    }

    private static void syncLingliBase(Map<String, Object> data) {
        PlayerAttributeStore.Lingli ling = PlayerAttributeStore.parseLingli(data.get("灵力"));
        int newBase = CultivationMath.computeBaseMaxLingli(data);
        data.put("灵力", PlayerAttributeStore.formatLingli(
            new PlayerAttributeStore.Lingli(ling.current(), newBase, ling.bonus())));
    }

    private static boolean isMutatedLinggen(String element) {
        return List.of("雷", "风", "冰", "光", "暗").contains(element);
    }

    private Session openSession(Player player, String targetName, Map<String, Object> data) {
        Session session = new Session();
        session.viewPlayerName = targetName;
        session.viewData = data;
        openPlayers.put(player, session);
        return session;
    }

    private void openPlayer(Player player) {
        Map<String, Object> data = PlayerAttributeStore.load(player.getName());
        if (data == null) {
            openMenu(player, buildPlayerMenu(player, null), new Session());
            return;
        }
        CultivationMath.ensureDataComplete(player.getName(), data);
        // 打开菜单时同步一次：把突破/根骨提升后超出上限的残留加成截断掉
        AttributeApplier.applyPlayerAttributes(player, data, false);
        PlayerAttributeStore.save(player.getName(), data);
        Map<String, Object> temp = PlayerAttributeStore.deepCopy(data);
        Map<String, Object> original = PlayerAttributeStore.deepCopy(data);
        Session session = new Session();
        session.originalData = original;
        session.tempData = temp;
        openMenu(player, buildPlayerMenu(player, temp), session);
    }

    private void openPlayerWithTemp(Player player, Map<String, Object> temp) {
        Session old = openPlayers.get(player);
        Session session = new Session();
        session.originalData = old == null ? null : old.originalData;
        session.tempData = temp;
        openMenu(player, buildPlayerMenu(player, temp), session);
    }

    private void openAdminList(Player player, int page) {
        if (!isAdmin(player)) {
            KomutechSupport.send(player, "§c无权限");
            openPlayer(player);
            return;
        }
        List<String> names = new ArrayList<>();
        try {
            var dir = PlayerAttributeStore.fileFor("x").getParent();
            if (Files.exists(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("[") && n.endsWith("].json");
                    }).sorted().forEach(p -> names.add(p.getFileName().toString().replaceAll("^\\[|\\]\\.json$", "")));
                }
            }
        } catch (IOException ignored) {
        }
        int perPage = ADMIN_SLOTS.length;
        int totalPages = Math.max(1, (int) Math.ceil(names.size() / (double) perPage));
        int currentPage = Math.min(Math.max(page, 1), totalPages);
        Inventory inv = MenuGuiHelper.create(54, TITLE_ADMIN_LIST);
        MenuGuiHelper.applyBorder(inv, BORDER);
        inv.setItem(4, MenuGuiHelper.item("COMPASS", "§6管理员模式", List.of("§7当前为管理员模式")));
        inv.setItem(6, MenuGuiHelper.item("PAPER", "§e总玩家数: " + names.size(), List.of()));
        inv.setItem(8, MenuGuiHelper.item("COMPASS", "§a个人模式", List.of("§7点击切换")));
        inv.setItem(49, MenuGuiHelper.item("BARRIER", "§c关闭", List.of()));
        inv.setItem(53, MenuGuiHelper.item("TNT", "§c§l清除所有玩家数据", List.of("§7点击后需输入密码")));
        inv.setItem(48, currentPage > 1 ? MenuGuiHelper.item("ARROW", "§a上一页", List.of()) : MenuGuiHelper.border());
        inv.setItem(50, currentPage < totalPages ? MenuGuiHelper.item("ARROW", "§a下一页", List.of()) : MenuGuiHelper.border());
        Session session = new Session();
        session.page = currentPage;
        session.totalPages = totalPages;
        int start = (currentPage - 1) * perPage;
        for (int i = 0; i < perPage && start + i < names.size(); i++) {
            String name = names.get(start + i);
            int slot = ADMIN_SLOTS[i];
            inv.setItem(slot, MenuGuiHelper.item("PLAYER_HEAD", "§e" + name, List.of("§7左键查看", "§7Shift+右键删除")));
            session.slotMap.put(slot, name);
        }
        openMenu(player, inv, session);
    }

    private void openAdminView(Player player, String targetName) {
        if (!isAdmin(player)) {
            KomutechSupport.send(player, "§c无权限");
            openPlayer(player);
            return;
        }
        Map<String, Object> data = PlayerAttributeStore.load(targetName);
        if (data == null) {
            KomutechSupport.send(player, "§c无数据");
            return;
        }
        Map<String, Object> temp = PlayerAttributeStore.deepCopy(data);
        Session session = new Session();
        session.viewPlayerName = targetName;
        session.viewData = temp;
        openMenu(player, buildAdminView(targetName, temp), session);
    }

    private void refreshAdminView(Player player, Session session) {
        openMenu(player, buildAdminView(session.viewPlayerName, session.viewData), session);
    }

    private Inventory buildPlayerMenu(Player player, Map<String, Object> data) {
        Inventory inv = MenuGuiHelper.create(54, TITLE_PLAYER);
        MenuGuiHelper.applyBorder(inv, BORDER);
        inv.setItem(4, MenuGuiHelper.item("PLAYER_HEAD", "§6" + player.getName(), List.of("§7当前玩家")));
        inv.setItem(8, MenuGuiHelper.item("COMPASS", "§a管理员模式", List.of("§7点击切换")));
        inv.setItem(49, MenuGuiHelper.item("BARRIER", "§c关闭", List.of()));
        inv.setItem(53, MenuGuiHelper.item("LIME_STAINED_GLASS_PANE", "§a确认加点", List.of("§7点击保存并应用属性")));
        inv.setItem(48, MenuGuiHelper.border());
        inv.setItem(50, MenuGuiHelper.border());
        if (data == null) {
            inv.setItem(22, MenuGuiHelper.item("PAPER", "§c无玩家属性数据", List.of("§7你还没有进行过灵根鉴定")));
            return inv;
        }
        renderCommon(inv, data, false);
        boolean allEnabled = CultivationMath.SWITCHABLE_ATTRS.stream()
            .allMatch(a -> PlayerAttributeStore.getBoolean(data, a + "_启用", true));
        inv.setItem(51, MenuGuiHelper.item("LEVER", allEnabled ? "§a全局启用中" : "§c全局禁用中",
            List.of("§7点击一键切换所有属性启用/禁用")));
        return inv;
    }

    private Inventory buildAdminView(String playerName, Map<String, Object> data) {
        Inventory inv = MenuGuiHelper.create(54, TITLE_ADMIN_VIEW + playerName);
        MenuGuiHelper.applyBorder(inv, BORDER);
        inv.setItem(4, MenuGuiHelper.item("PLAYER_HEAD", "§6" + playerName, List.of()));
        inv.setItem(8, MenuGuiHelper.item("COMPASS", "§a个人模式", List.of("§7返回个人属性")));
        inv.setItem(48, MenuGuiHelper.item("ARROW", "§a返回", List.of("§7返回玩家列表")));
        inv.setItem(49, MenuGuiHelper.item("BARRIER", "§c关闭", List.of()));
        inv.setItem(53, MenuGuiHelper.item("LIME_STAINED_GLASS_PANE", "§a确认修改", List.of("§7点击保存修改")));
        inv.setItem(50, MenuGuiHelper.border());
        renderCommon(inv, data, true);
        if (data.containsKey("突破失败")) {
            boolean failed = PlayerAttributeStore.getBoolean(data, "突破失败", false);
            inv.setItem(7, MenuGuiHelper.item(failed ? "REDSTONE" : "EMERALD", "§e突破状态",
                List.of("§7突破失败: " + (failed ? "§c是" : "§a否"), "§a右键切换状态")));
        }
        return inv;
    }

    private void renderCommon(Inventory inv, Map<String, Object> data, boolean admin) {
        if (PlayerAttributeStore.getString(data, "修为", null) != null) {
            inv.setItem(13, MenuGuiHelper.item("PAINTING", "§6修为", List.of("§7" + data.get("修为"))));
        }
        if (PlayerAttributeStore.getString(data, "灵气", null) != null) {
            List<String> lore = new ArrayList<>(List.of("§7" + data.get("灵气")));
            if (admin) {
                lore.add("§a右键修改");
            }
            inv.setItem(22, MenuGuiHelper.item("EXPERIENCE_BOTTLE", "§6灵气", lore));
        }
        for (var entry : ATTR_SLOTS.entrySet()) {
            int slot = entry.getKey();
            String key = entry.getValue();
            if ("根基".equals(key) && !data.containsKey("根基")) {
                continue;
            }
            if (!data.containsKey(key) && !"根基".equals(key)) {
                continue;
            }
            if ("灵识".equals(key) && !CultivationMath.isGoldenCoreRealm(data)) {
                continue;
            }
            List<String> lore = buildAttrLore(data, key, admin);
            String mat = switch (key) {
                case "功德", "煞气", "灵力" -> "GHAST_TEAR";
                case "根基" -> "BEDROCK";
                case "悟性", "根骨" -> "ENCHANTED_BOOK";
                case "血量" -> "APPLE";
                case "攻击力" -> "IRON_SWORD";
                case "防御力" -> "IRON_CHESTPLATE";
                case "速度" -> "FEATHER";
                case "灵气获取" -> "GLOWSTONE_DUST";
                case "灵识" -> "ENDER_EYE";
                case "属性点", "灵根属性" -> "NETHER_STAR";
                case "灵气" -> "EXPERIENCE_BOTTLE";
                case "修为" -> "PAINTING";
                default -> "PAPER";
            };
            inv.setItem(slot, MenuGuiHelper.item(mat, "§6" + key, lore));
        }
        String linggen = PlayerAttributeStore.getString(data, "灵根", null);
        if (linggen != null) {
            List<String> lore = new ArrayList<>(List.of(
                "§7灵根: §e" + linggen,
                "§7属性: §e" + PlayerAttributeStore.getString(data, "灵根属性", "未知"),
                "§7总品质: §e" + PlayerAttributeStore.getString(data, "总品质", "0.00")
            ));
            if (admin) {
                lore.add("§a右键修改灵根");
            }
            inv.setItem(40, MenuGuiHelper.item("NETHER_STAR", "§6灵根属性", lore));
        }
        Map<String, Object> qualities = PlayerAttributeStore.getMap(data, "灵根品质");
        Map<String, String> mats = Map.of(
            "金", "YELLOW_DYE", "木", "GREEN_DYE", "水", "BLUE_DYE", "火", "BLAZE_POWDER", "土", "BROWN_DYE",
            "雷", "PURPLE_DYE", "风", "WIND_CHARGE", "冰", "LIGHT_BLUE_DYE", "光", "WHITE_DYE", "暗", "BLACK_DYE"
        );
        for (var entry : QUALITY_SLOTS.entrySet()) {
            String element = entry.getValue();
            if (!qualities.containsKey(element)) {
                continue;
            }
            List<String> lore = new ArrayList<>(List.of("§7品质: §f" + formatQuality(qualities.get(element))));
            if (admin) {
                lore.add("§a右键修改");
            }
            inv.setItem(entry.getKey(), MenuGuiHelper.item(mats.getOrDefault(element, "PAPER"), "§e" + element, lore));
        }
    }

    private List<String> buildAttrLore(Map<String, Object> data, String key, boolean admin) {
        List<String> lore = new ArrayList<>();
        if ("灵力".equals(key)) {
            PlayerAttributeStore.Lingli ling = PlayerAttributeStore.parseLingli(data.get("灵力"));
            lore.add("§7当前/最大: §f" + (int) ling.current() + "/" + (int) ling.totalMax());
            lore.add("§7额外加成: §a" + (ling.bonus() >= 0 ? "+" + ling.bonus() : ling.bonus()));
            if (admin) {
                lore.add("§a右键修改额外加成");
            }
            return lore;
        }
        if ("根基".equals(key)) {
            Map<String, Object> foundation = PlayerAttributeStore.getMap(data, "根基");
            lore.add("§7稳固值: §f" + foundation.getOrDefault("稳固值", 0));
            lore.add("§7溢出的灵气: §f" + foundation.getOrDefault("溢出的灵气", "0/0"));
            if (admin) {
                lore.add("§a右键修改溢出灵气");
            }
            return lore;
        }
        lore.add("§7" + data.get(key));
        if (CultivationMath.SWITCHABLE_ATTRS.contains(key)) {
            lore.add(getActualBonusDisplay(data, key, admin));
            lore.add(PlayerAttributeStore.getBoolean(data, key + "_启用", true) ? "§a● 已启用" : "§c○ 已禁用");
            if (!admin) {
                lore.add("§7§oShift+左键切换状态");
            }
        }
        if ("灵气获取".equals(key)) {
            lore.add(getActualBonusDisplay(data, key, admin));
        }
        if (!admin && POINT_ATTRS.contains(key)) {
            lore.add("§a左键 +1  §eShift+右键 +10  §c右键 -1");
        }
        if ("属性点".equals(key) && !admin) {
            lore.add("§7在左侧属性图标上分配点数");
        }
        if (AttributePointLimits.isLimited(key) && !admin) {
            int points = PlayerAttributeStore.getInt(data, key, 0);
            lore.add("§7加点上限: §e" + AttributePointLimits.limitLabel(data, key));
            if (AttributePointLimits.isAtLimit(data, key, points)) {
                lore.add("§c已达上限");
            }
        }
        if (admin && !"修为".equals(key)) {
            lore.add("§a右键修改");
        }
        return lore;
    }

    private String getActualBonusDisplay(Map<String, Object> data, String attr, boolean admin) {
        if (!data.containsKey("灵根") || !data.containsKey("总品质")) {
            return "§7实际增加: §c未知";
        }
        List<String> elements = CultivationMath.splitLinggen(PlayerAttributeStore.getString(data, "灵根", ""));
        double quality = PlayerAttributeStore.getDouble(data, "总品质", 0.01);
        double realm = CultivationMath.getRealmCoefficient(data);
        double lc = CultivationMath.getLinggenFinalCoefficient(elements, quality, attr);
        double points = PlayerAttributeStore.getDouble(data, attr, 0);
        double gengu = PlayerAttributeStore.getDouble(data, "根骨", 1);
        if (points == 0) {
            if ("速度".equals(attr)) {
                return "§7实际增加: §a+0.00%";
            }
            if ("灵气获取".equals(attr)) {
                return "§7修炼效率: §a100%";
            }
            if ("灵识".equals(attr)) {
                return "§7实际增加: §a+0.00 格";
            }
            return "§7实际增加: §a0.00 " + attr;
        }
        if ("灵气获取".equals(attr)) {
            int pts = (int) points;
            double efficiency = AttributePointLimits.spiritGainPercent(data, pts);
            double cap = AttributePointLimits.limit(data, attr);
            return efficiency >= cap - 1e-6
                ? String.format("§7修炼效率: §a%.0f%% §7(上限 %.0f%%)", efficiency, cap)
                : String.format("§7修炼效率: §a%.0f%%", efficiency);
        }
        double raw = CultivationMath.computeActualValue(points, CultivationMath.baseGrowth.getOrDefault(attr, 0.0), lc, realm, quality, gengu);
        // 个人模式始终按上限显示；管理员豁免时显示未截断值
        double val = AttributePointLimits.clampForApply(data, attr, raw, admin);
        double cap = AttributePointLimits.isLimited(attr) ? AttributePointLimits.limit(data, attr) : Double.MAX_VALUE;
        if ("速度".equals(attr)) {
            return raw >= cap - 1e-6
                ? String.format("§7实际增加: §a+%.2f%% §7(上限 %.0f%%)", Math.min(val, cap), cap)
                : String.format("§7实际增加: §a+%.2f%%", val);
        }
        if ("灵识".equals(attr)) {
            double clamped = Math.min(val, AttributeApplier.MAX_REACH_BONUS);
            return raw >= AttributeApplier.MAX_REACH_BONUS
                ? String.format("§7实际增加: §a+%.2f 格 §7(已达上限)", clamped)
                : String.format("§7实际增加: §a+%.2f 格", val);
        }
        return raw >= cap - 1e-6
            ? String.format("§7实际增加: §a+%.2f %s §7(上限 %.0f)", Math.min(val, cap), attr, cap)
            : String.format("§7实际增加: §a+%.2f %s", val, attr);
    }

    private void toggleAllAttributes(Player player, Map<String, Object> data) {
        boolean next = !CultivationMath.SWITCHABLE_ATTRS.stream()
            .allMatch(a -> PlayerAttributeStore.getBoolean(data, a + "_启用", true));
        for (String attr : CultivationMath.SWITCHABLE_ATTRS) {
            data.put(attr + "_启用", next);
        }
        KomutechSupport.send(player, "§a所有战斗属性已" + (next ? "启用" : "禁用"));
    }

    private void openMenu(Player player, Inventory inventory, Session session) {
        player.openInventory(inventory);
        openPlayers.put(player, session);
    }

    private static boolean isAdmin(Player player) {
        return player.isOp() || "Komu_A".equals(player.getName());
    }

    private static String formatQuality(Object value) {
        if (value instanceof Number number) {
            return String.format("%.2f", number.doubleValue());
        }
        return String.valueOf(value);
    }

    private static String stripColor(String input) {
        return input == null ? "" : input.replaceAll("§.", "");
    }

    private static final class Session {
        Map<String, Object> originalData;
        Map<String, Object> tempData;
        Map<String, Object> viewData;
        String viewPlayerName;
        int page = 1;
        int totalPages = 1;
        Map<Integer, String> slotMap = new HashMap<>();
    }
}
