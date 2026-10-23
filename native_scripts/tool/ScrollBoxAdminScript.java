package tech.komutech.native_scripts.tool;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.combat.ScrollCombatEngine;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.ChatInputService;
import tech.komutech.native_scripts.support.KomutechJson;
import tech.komutech.native_scripts.support.KomutechPaths;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;
import tech.komutech.native_scripts.support.MenuGuiHelper;

public final class ScrollBoxAdminScript implements NativeLifecycleScript, KomutechMenuHandler {

    private static final String TITLE_LIST = "§c§l云篆匣管理";
    private static final String TITLE_DETAIL = "§d§l玩家卷轴详情 - ";
    private static final String SCROLL_PREFIX = "KOMUTECH_L_JZ_";
    private static final int SIZE = 54;
    private static final int[] BORDER_SLOTS = {0, 1, 2, 3, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47, 51, 52, 53};
    private static final int[] AVAILABLE_SLOTS = {
        10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43
    };

    private final Map<UUID, MenuState> openPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> awaitingInput = new ConcurrentHashMap<>();
    private Map<String, Object> scrollConfig = Map.of();
    private Plugin plugin;

    @Override
    public void registerLifecycle(Plugin plugin) {
        this.plugin = plugin;
        KomutechMenuRouter.registerHandler(this);
        reloadConfig();
    }

    @Override
    public boolean handles(InventoryView view) {
        String title = view.getTitle();
        return TITLE_LIST.equals(title) || title.startsWith(TITLE_DETAIL);
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onUse".equals(functionName)) {
            return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
        }
        return null;
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        if (!player.isOp() && !"Komu_A".equalsIgnoreCase(player.getName())) {
            KomutechSupport.send(player, "§c你没有权限使用此道具");
            return null;
        }
        openList(player, 1);
        return null;
    }

    private void reloadConfig() {
        scrollConfig = tech.komutech.native_scripts.support.KomutechConfigMerge.loadMerged(
            KomutechPaths.scrollConfig(), "addon_configs/Komutech/卷轴属性.json");
    }

    private void openList(Player player, int page) {
        List<String> players = listPlayerFiles();
        int totalPages = Math.max(1, (int) Math.ceil(players.size() / (double) AVAILABLE_SLOTS.length));
        page = Math.max(1, Math.min(page, totalPages));
        Inventory inv = MenuGuiHelper.create(SIZE, TITLE_LIST);
        MenuGuiHelper.applyBorder(inv, BORDER_SLOTS);
        inv.setItem(4, MenuGuiHelper.item(Material.PAINTING, "§e云篆匣管理", List.of("§7总玩家数: " + players.size())));
        inv.setItem(49, MenuGuiHelper.item(Material.BARRIER, "§c关闭", List.of()));
        inv.setItem(48, page > 1
            ? MenuGuiHelper.item(Material.ARROW, "§a上一页", List.of())
            : MenuGuiHelper.border());
        inv.setItem(50, page < totalPages
            ? MenuGuiHelper.item(Material.ARROW, "§a下一页", List.of())
            : MenuGuiHelper.border());
        Map<Integer, String> slotMap = new HashMap<>();
        int start = (page - 1) * AVAILABLE_SLOTS.length;
        for (int i = 0; i < AVAILABLE_SLOTS.length && start + i < players.size(); i++) {
            String name = players.get(start + i);
            int slot = AVAILABLE_SLOTS[i];
            inv.setItem(slot, MenuGuiHelper.item(Material.PLAYER_HEAD, "§e" + name, List.of("§7左键查看卷轴")));
            slotMap.put(slot, name);
        }
        player.openInventory(inv);
        openPlayers.put(player.getUniqueId(), MenuState.list(page, totalPages, slotMap));
    }

    private void openDetail(Player player, String playerName) {
        List<String> scrolls = getScrollList(playerName);
        Inventory inv = MenuGuiHelper.create(SIZE, TITLE_DETAIL + playerName);
        MenuGuiHelper.applyBorder(inv, BORDER_SLOTS);
        inv.setItem(4, MenuGuiHelper.item(Material.PLAYER_HEAD, "§6" + playerName, List.of()));
        inv.setItem(0, MenuGuiHelper.item(Material.ARROW, "§a返回", List.of("§7返回玩家列表")));
        inv.setItem(49, MenuGuiHelper.item(Material.BARRIER, "§c关闭", List.of()));
        inv.setItem(48, MenuGuiHelper.border());
        inv.setItem(50, MenuGuiHelper.border());
        Map<Integer, String> scrollMap = new HashMap<>();
        if (scrolls.isEmpty()) {
            inv.setItem(22, MenuGuiHelper.item(Material.PAPER, "§c该玩家暂未存入卷轴", List.of()));
        } else {
            for (int i = 0; i < Math.min(scrolls.size(), AVAILABLE_SLOTS.length); i++) {
                String fullId = scrolls.get(i);
                String skillName = skillName(fullId);
                int prof = getScrollProf(playerName, skillName);
                int slot = AVAILABLE_SLOTS[i];
                inv.setItem(slot, makeDisplayItem(fullId, prof));
                scrollMap.put(slot, fullId);
            }
        }
        player.openInventory(inv);
        openPlayers.put(player.getUniqueId(), MenuState.detail(playerName, scrollMap));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        MenuState state = openPlayers.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        String title = event.getView().getTitle();
        if (!TITLE_LIST.equals(title) && !title.startsWith(TITLE_DETAIL)) {
            return;
        }
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir()) {
            return;
        }
        int slot = event.getSlot();
        if (TITLE_LIST.equals(title)) {
            handleListClick(player, slot, state);
        } else {
            handleDetailClick(player, slot, state, event.isRightClick());
        }
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && openPlayers.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            openPlayers.remove(player.getUniqueId());
            awaitingInput.remove(player.getUniqueId());
        }
    }

    private void handleListClick(Player player, int slot, MenuState state) {
        if (slot == 49) {
            player.closeInventory();
        } else if (slot == 48 && state.page > 1) {
            openList(player, state.page - 1);
        } else if (slot == 50 && state.page < state.totalPages) {
            openList(player, state.page + 1);
        } else if (state.slotMap != null && state.slotMap.containsKey(slot)) {
            openDetail(player, state.slotMap.get(slot));
        }
    }

    private void handleDetailClick(Player player, int slot, MenuState state, boolean rightClick) {
        if (slot == 0) {
            openList(player, 1);
        } else if (slot == 49) {
            player.closeInventory();
        } else if (rightClick && state.scrollMap != null && state.scrollMap.containsKey(slot)) {
            if (awaitingInput.containsKey(player.getUniqueId())) {
                return;
            }
            String fullId = state.scrollMap.get(slot);
            String skillName = skillName(fullId);
            String playerName = state.playerName;
            awaitingInput.put(player.getUniqueId(), true);
            ChatInputService.request(player, "§a请输入 §e" + skillName + " §a的新熟练度值（数字）:",
                input -> {
                    awaitingInput.remove(player.getUniqueId());
                    if ("cancel".equalsIgnoreCase(input.trim())) {
                        KomutechSupport.send(player, "§c已取消");
                        return;
                    }
                    try {
                        int val = Integer.parseInt(input.trim());
                        if (val < 0) {
                            KomutechSupport.send(player, "§c请输入非负整数");
                            return;
                        }
                        int max = getMaxProf(skillName);
                        int finalVal = max > 0 ? Math.min(val, max) : val;
                        if (setScrollProf(playerName, skillName, finalVal)) {
                            KomutechSupport.send(player, "§a熟练度已更新为 " + finalVal);
                            openDetail(player, playerName);
                        } else {
                            KomutechSupport.send(player, "§c保存失败");
                        }
                    } catch (NumberFormatException ex) {
                        KomutechSupport.send(player, "§c请输入非负整数");
                    }
                });
        }
    }

    private List<String> listPlayerFiles() {
        try {
            var dir = KomutechPaths.yunZhuanXia();
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.filter(path -> {
                    String fname = path.getFileName().toString();
                    return fname.startsWith("[") && fname.endsWith("]云篆匣.json");
                }).forEach(path -> {
                    String fname = path.getFileName().toString();
                    names.add(fname.substring(1, fname.length() - "]云篆匣.json".length()));
                });
            }
            names.sort(String::compareTo);
            return names;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> loadPlayerData(String playerName) {
        try {
            var path = KomutechPaths.yunZhuanXia().resolve("[" + playerName + "]云篆匣.json");
            if (!Files.exists(path)) {
                return null;
            }
            return KomutechJson.asMap(KomutechJson.parse(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean savePlayerData(String playerName, Map<String, Object> data) {
        try {
            Files.createDirectories(KomutechPaths.yunZhuanXia());
            Files.writeString(
                KomutechPaths.yunZhuanXia().resolve("[" + playerName + "]云篆匣.json"),
                KomutechJson.stringify(data),
                StandardCharsets.UTF_8
            );
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<String> getScrollList(String playerName) {
        Map<String, Object> data = loadPlayerData(playerName);
        if (data == null) {
            return List.of();
        }
        List<Object> scrolls = KomutechJson.asList(data.get("卷轴数据"));
        List<String> result = new ArrayList<>();
        for (Object scroll : scrolls) {
            if (scroll != null) {
                result.add(String.valueOf(scroll));
            }
        }
        return result;
    }

    private int getScrollProf(String playerName, String skillName) {
        Map<String, Object> data = loadPlayerData(playerName);
        if (data == null) {
            return 0;
        }
        Object prof = KomutechJson.asMap(data.get("熟练度记录")).get(skillName);
        return prof instanceof Number number ? number.intValue() : 0;
    }

    private boolean setScrollProf(String playerName, String skillName, int value) {
        Map<String, Object> data = loadPlayerData(playerName);
        if (data == null) {
            return false;
        }
        Map<String, Object> profMap = new HashMap<>(KomutechJson.asMap(data.get("熟练度记录")));
        profMap.put(skillName, value);
        data.put("熟练度记录", profMap);
        return savePlayerData(playerName, data);
    }

    private int getMaxProf(String skillName) {
        String key = ScrollCombatEngine.normalizeSkillId(skillName);
        reloadConfig();
        Object skill = scrollConfig.get(key);
        if (skill instanceof Map<?, ?> map) {
            Object max = map.get("熟练度上限");
            return max instanceof Number number ? number.intValue() : 0;
        }
        return 0;
    }

    private static String skillName(String fullId) {
        return ScrollCombatEngine.normalizeSkillId(fullId);
    }

    private ItemStack makeDisplayItem(String fullId, int prof) {
        SlimefunItem sf = SlimefunItem.getById(fullId);
        if (sf == null) {
            return MenuGuiHelper.item(Material.BARRIER, "§c未知卷轴", List.of(fullId));
        }
        ItemStack item = sf.getItem().clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.removeIf(line -> line.startsWith("§7熟练度："));
        String skillName = skillName(fullId);
        int max = getMaxProf(skillName);
        lore.add("§7熟练度：§f" + prof + " §7/ §f" + max);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private record MenuState(int page, int totalPages, Map<Integer, String> slotMap, String playerName,
                             Map<Integer, String> scrollMap) {
        static MenuState list(int page, int totalPages, Map<Integer, String> slotMap) {
            return new MenuState(page, totalPages, slotMap, null, null);
        }

        static MenuState detail(String playerName, Map<Integer, String> scrollMap) {
            return new MenuState(1, 1, null, playerName, scrollMap);
        }
    }
}
