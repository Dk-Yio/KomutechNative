package tech.komutech.native_scripts.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import tech.komutech.native_scripts.storage.WanXiangGuiStorage;
import tech.komutech.native_scripts.storage.WanXiangGuiStorage.StoreResult;
import tech.komutech.native_scripts.support.KomutechAsyncScheduler;
import tech.komutech.native_scripts.support.KomutechChatInput;
import tech.komutech.native_scripts.support.KomutechJson;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.MenuGuiHelper;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;

public final class WanXiangGuiConfigMenuScript implements NativeLifecycleScript, KomutechMenuHandler {

    private static final String PLAYER_TITLE = "§a§l我的存储";
    private static final String ADMIN_TITLE = "§c§l管理员模式";
    private static final String STORAGE_LIST_PREFIX = "§e§l存储列表 - ";
    private static final String STORAGE_VIEW_PREFIX = "§d§l存储内容 - ";
    private static final int MODE_SLOT = 4;
    private static final int INFO_SLOT = 8;
    private static final int BACK_SLOT = 49;
    private static final int SORT_SLOT = 47;
    private static final int SEARCH_SLOT = 51;
    private static final int BACK_BUTTON_SLOT = 45;
    private static final int[] BORDER_SLOTS = {0, 1, 2, 3, 5, 6, 7, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47, 48, 50, 51, 52, 53};

    private final Map<Player, MenuState> openPlayers = new HashMap<>();
    private final Map<Player, Boolean> turning = new HashMap<>();
    private final Map<Player, Boolean> awaitingSearch = new HashMap<>();

    @Override
    public void registerLifecycle(Plugin plugin) {
        KomutechMenuRouter.registerHandler(this);
    }

    @Override
    public boolean handles(InventoryView view) {
        String title = MenuGuiHelper.legacyTitle(view);
        return PLAYER_TITLE.equals(title)
            || ADMIN_TITLE.equals(title)
            || title.startsWith(STORAGE_LIST_PREFIX)
            || title.startsWith(STORAGE_VIEW_PREFIX);
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onButtonGroupClick".equals(functionName) && args[0] instanceof Player player) {
            openPlayerMenu(player);
            return true;
        }
        if ("onUse".equals(functionName)) {
            try {
                Player player = (Player) args[0].getClass().getMethod("getPlayer").invoke(args[0]);
                openPlayerMenu(player);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String title = MenuGuiHelper.legacyTitle(event.getView());
        if (!handles(event.getView())) {
            return;
        }
        event.setCancelled(true);
        MenuState state = openPlayers.get(player);
        if (state == null) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        int slot = event.getSlot();
        if (PLAYER_TITLE.equals(title)) {
            handlePlayerMenuClick(player, state, slot);
        } else if (ADMIN_TITLE.equals(title)) {
            handleAdminMenuClick(player, state, slot);
        } else if (title.startsWith(STORAGE_LIST_PREFIX)) {
            handleStorageListClick(player, state, slot);
        } else if (title.startsWith(STORAGE_VIEW_PREFIX)) {
            handleStorageViewClick(player, state, slot, event);
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (Boolean.TRUE.equals(turning.remove(player))) {
            return;
        }
        if (Boolean.TRUE.equals(awaitingSearch.remove(player))) {
            MenuState state = openPlayers.get(player);
            if (state instanceof ViewState viewState) {
                KomutechSupport.send(player, "§a请在聊天栏输入搜索关键词，输入 cancel 取消:");
                KomutechChatInput.waitFor(player, input -> {
                    if ("cancel".equalsIgnoreCase(input.trim())) {
                        KomutechSupport.send(player, "§c已取消搜索");
                        return;
                    }
                    String keyword = input.trim();
                    if (keyword.isEmpty()) {
                        KomutechSupport.send(player, "§c关键词不能为空");
                        return;
                    }
                    Map<String, Object> data = WanXiangGuiStorage.read(viewState.owner(), viewState.storage());
                    Map<String, Object> result = WanXiangGuiStorage.search(data, keyword);
                    List<Object> first = KomutechJson.asList(KomutechJson.asMap(result.get("pages")).get("1"));
                    if (first == null || first.isEmpty() || first.get(0) == null) {
                        KomutechSupport.send(player, "§c没有找到匹配的物品");
                        return;
                    }
                    WanXiangGuiStorage.setMeta(data, "1", "search", keyword);
                    WanXiangGuiStorage.write(viewState.owner(), viewState.storage(), data);
                    turning.put(player, true);
                    openStorageView(player, viewState.owner(), viewState.storage(), "1", "search", result, keyword);
                    KomutechSupport.send(player, "§a找到 " + WanXiangGuiStorage.countStoredItems(result) + " 个物品");
                });
            }
            return;
        }
        openPlayers.remove(player);
        KomutechMenuRouter.unbindInventory(event.getView().getTopInventory());
    }

    private void handlePlayerMenuClick(Player player, MenuState state, int slot) {
        if (!(state instanceof PlayerState playerState)) {
            return;
        }
        if (slot == MODE_SLOT && isAdmin(player)) {
            turning.put(player, true);
            openAdminMenu(player, 1);
            return;
        }
        if (slot == BACK_SLOT) {
            player.closeInventory();
            return;
        }
        int row = slot / 9;
        int col = slot % 9;
        if (col < 1 || col > 7) {
            return;
        }
        int index = col - 1;
        if (row == 1 && index < playerState.storages().size()) {
            return;
        }
        if (row == 2 && index < playerState.storages().size()) {
            String name = playerState.storages().get(index);
            confirmDelete(player, name, () -> {
                WanXiangGuiStorage.deleteStorage(player.getName(), name);
                KomutechSupport.send(player, "§a已删除存储 " + name);
                openPlayerMenu(player);
            });
            return;
        }
        if (row == 3 && index < playerState.storages().size()) {
            String oldName = playerState.storages().get(index);
            promptRename(player, oldName);
        }
    }

    private void handleAdminMenuClick(Player player, MenuState state, int slot) {
        if (!(state instanceof AdminState adminState)) {
            return;
        }
        if (!isAdmin(player)) {
            KomutechSupport.send(player, "§c你没有权限使用管理员模式");
            openPlayerMenu(player);
            return;
        }
        if (slot == MODE_SLOT) {
            openPlayerMenu(player);
            return;
        }
        if (slot == BACK_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == 53) {
            KomutechSupport.send(player, "§c确定要清空所有存储数据吗？此操作不可逆。请输入密码以确认:");
            KomutechChatInput.waitFor(player, input -> {
                if (WanXiangGuiStorage.clearPassword().equals(input)) {
                    KomutechAsyncScheduler.submit(
                        WanXiangGuiStorage::deleteAllStorages,
                        ignored -> {
                            KomutechSupport.send(player, "§a已清空所有存储数据");
                            openAdminMenu(player, 1);
                        }
                    );
                } else {
                    KomutechSupport.send(player, "§c密码错误，操作已取消");
                }
            });
            return;
        }
        if (slot == 48 && adminState.page() > 1) {
            openAdminMenu(player, adminState.page() - 1);
            return;
        }
        if (slot == 50 && adminState.page() < adminState.total()) {
            openAdminMenu(player, adminState.page() + 1);
            return;
        }
        String target = adminState.slotMap().get(slot);
        if (target != null) {
            openStorageList(player, target);
        }
    }

    private void handleStorageListClick(Player player, MenuState state, int slot) {
        if (!(state instanceof ListState listState)) {
            return;
        }
        if (slot == 4) {
            openAdminMenu(player, 1);
            return;
        }
        if (slot == BACK_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == 53) {
            confirmDelete(player, listState.playerName() + " 的所有存储", () -> {
                KomutechAsyncScheduler.submit(
                    () -> WanXiangGuiStorage.deletePlayerStorages(listState.playerName()),
                    ignored -> {
                        KomutechSupport.send(player, "§a已删除玩家 " + listState.playerName() + " 的所有存储");
                        openAdminMenu(player, 1);
                    }
                );
            });
            return;
        }
        int row = slot / 9;
        int col = slot % 9;
        if (col < 1 || col > 7) {
            return;
        }
        int index = col - 1;
        if (row == 1 && index < listState.storages().size()) {
            openStorageView(player, listState.playerName(), listState.storages().get(index), "1", "normal", null, "");
            return;
        }
        if (row == 2 && index < listState.storages().size()) {
            String name = listState.storages().get(index);
            confirmDelete(player, name, () -> {
                WanXiangGuiStorage.deleteStorage(listState.playerName(), name);
                KomutechSupport.send(player, "§a已删除存储 " + name);
                openStorageList(player, listState.playerName());
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void handleStorageViewClick(Player player, MenuState state, int slot, InventoryClickEvent event) {
        if (!(state instanceof ViewState viewState)) {
            return;
        }
        Map<String, Object> data = WanXiangGuiStorage.read(viewState.owner(), viewState.storage());
        if (slot == BACK_BUTTON_SLOT && "search".equals(viewState.mode())) {
            WanXiangGuiStorage.setMeta(data, "1", "normal", "");
            WanXiangGuiStorage.write(viewState.owner(), viewState.storage(), data);
            turning.put(player, true);
            openStorageView(player, viewState.owner(), viewState.storage(), "1", "normal", null, "");
            return;
        }
        if (slot == 48 && viewState.pageInt() > 1) {
            String prev = String.valueOf(viewState.pageInt() - 1);
            WanXiangGuiStorage.setMeta(data, prev, viewState.mode(), viewState.keyword());
            WanXiangGuiStorage.write(viewState.owner(), viewState.storage(), data);
            turning.put(player, true);
            openStorageView(player, viewState.owner(), viewState.storage(), prev, viewState.mode(),
                viewState.searchData(), viewState.keyword());
            return;
        }
        if (slot == 49) {
            turning.put(player, true);
            openStorageList(player, viewState.owner());
            return;
        }
        if (slot == 50) {
            Map<String, Object> display = displayData(viewState);
            if (KomutechJson.asMap(display.get("pages")).containsKey(String.valueOf(viewState.pageInt() + 1))) {
                String next = String.valueOf(viewState.pageInt() + 1);
                WanXiangGuiStorage.setMeta(data, next, viewState.mode(), viewState.keyword());
                WanXiangGuiStorage.write(viewState.owner(), viewState.storage(), data);
                turning.put(player, true);
                openStorageView(player, viewState.owner(), viewState.storage(), next, viewState.mode(),
                    viewState.searchData(), viewState.keyword());
            }
            return;
        }
        if (slot == SORT_SLOT && "normal".equals(viewState.mode())) {
            Map<String, Object> sorted = WanXiangGuiStorage.sortAll(data);
            WanXiangGuiStorage.write(viewState.owner(), viewState.storage(), sorted);
            turning.put(player, true);
            openStorageView(player, viewState.owner(), viewState.storage(), "1", "normal", null, "");
            KomutechSupport.send(player, "§a整理完成！");
            return;
        }
        if (slot == SEARCH_SLOT) {
            awaitingSearch.put(player, true);
            player.closeInventory();
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (slot >= 0 && slot < 45 && event.getClickedInventory() == top) {
            ItemStack item = event.getCurrentItem();
            if (item == null || item.getType().isAir()) {
                return;
            }
            if (player.getInventory().firstEmpty() == -1) {
                KomutechSupport.send(player, "§c背包已满");
                return;
            }
            Map<String, Object> display = displayData(viewState);
            List<Object> page = KomutechJson.asList(KomutechJson.asMap(display.get("pages")).get(viewState.page()));
            Object serialized = page.get(slot);
            if ("search".equals(viewState.mode())) {
                WanXiangGuiStorage.removeSerialized(data, serialized);
                page.set(slot, null);
            } else if (data != null) {
                Map<String, Object> pages = KomutechJson.asMap(data.get("pages"));
                List<Object> sourcePage = KomutechJson.asList(pages.get(viewState.page()));
                sourcePage.set(slot, null);
                pages.put(viewState.page(), sourcePage);
                data.put("pages", pages);
            }
            WanXiangGuiStorage.write(viewState.owner(), viewState.storage(), data);
            KomutechSupport.giveOrDrop(player, item.clone());
            KomutechSupport.send(player, "§a已取出物品");
            turning.put(player, true);
            if ("search".equals(viewState.mode())) {
                Map<String, Object> refreshed = WanXiangGuiStorage.search(data, viewState.keyword());
                openStorageView(player, viewState.owner(), viewState.storage(), viewState.page(),
                    viewState.mode(), refreshed, viewState.keyword());
            } else {
                openStorageView(player, viewState.owner(), viewState.storage(), viewState.page(),
                    viewState.mode(), null, "");
            }
            return;
        }
        if (slot >= 0 && slot < 45 && event.getClickedInventory() != top && "normal".equals(viewState.mode())) {
            ItemStack hand = event.getCurrentItem();
            if (hand == null || hand.getType().isAir()) {
                return;
            }
            StoreResult result = WanXiangGuiStorage.store(data, hand);
            if (!result.success()) {
                KomutechSupport.send(player, "§c存入失败: " + result.reason());
                return;
            }
            WanXiangGuiStorage.write(viewState.owner(), viewState.storage(), data);
            if (hand.getAmount() > 1) {
                hand.setAmount(hand.getAmount() - 1);
            } else {
                event.getClickedInventory().setItem(event.getSlot(), null);
            }
            KomutechSupport.send(player, "§a已存入物品");
            turning.put(player, true);
            openStorageView(player, viewState.owner(), viewState.storage(), result.page(), viewState.mode(), null, "");
        }
    }

    private static Map<String, Object> displayData(ViewState viewState) {
        if ("search".equals(viewState.mode()) && viewState.searchData() != null) {
            return viewState.searchData();
        }
        return WanXiangGuiStorage.read(viewState.owner(), viewState.storage());
    }

    private void openPlayerMenu(Player player) {
        List<String> storages = WanXiangGuiStorage.listStorages(player.getName());
        Inventory inv = MenuGuiHelper.create(54, PLAYER_TITLE);
        applyBorder(inv);
        inv.setItem(MODE_SLOT, MenuGuiHelper.item("COMPASS", "§a切换模式", List.of("§7点击切换到管理员模式")));
        inv.setItem(INFO_SLOT, MenuGuiHelper.item("PAPER", "§a你的存储", List.of("§7共 " + storages.size() + " 个")));
        int slot = 10;
        for (int i = 0; i < storages.size(); i++) {
            if (slot > 16) {
                break;
            }
            String name = storages.get(i);
            inv.setItem(slot, MenuGuiHelper.item("PAPER", "§f存储名: §e" + name, null));
            inv.setItem(slot + 9, MenuGuiHelper.item("REDSTONE_BLOCK", "§c删除", List.of("§7删除存储 " + name)));
            inv.setItem(slot + 18, MenuGuiHelper.item("NAME_TAG", "§a重命名", List.of("§7重命名存储 " + name)));
            slot++;
            if ((slot - 9) % 9 == 0) {
                slot += 2;
            }
        }
        inv.setItem(BACK_SLOT, MenuGuiHelper.item("BARRIER", "§c关闭", List.of("§7关闭菜单")));
        bindAndOpen(player, inv, new PlayerState(storages));
    }

    private void openAdminMenu(Player player, int page) {
        if (!isAdmin(player)) {
            KomutechSupport.send(player, "§c你没有权限使用管理员模式");
            openPlayerMenu(player);
            return;
        }
        List<String> players = WanXiangGuiStorage.listAllPlayers();
        int total = Math.max(1, (int) Math.ceil(players.size() / 28.0));
        int current = Math.min(Math.max(page, 1), total);
        int start = (current - 1) * 28;
        int end = Math.min(start + 28, players.size());
        Inventory inv = MenuGuiHelper.create(54, ADMIN_TITLE);
        applyBorder(inv);
        inv.setItem(MODE_SLOT, MenuGuiHelper.item("COMPASS", "§a切换模式", List.of("§7点击切换到玩家模式")));
        inv.setItem(INFO_SLOT, MenuGuiHelper.item("PAPER", "§6管理员面板",
            List.of("§7总玩家数: " + players.size(), "§7第 " + current + "/" + total + " 页")));
        inv.setItem(53, MenuGuiHelper.item("TNT", "§c清空所有数据", List.of("§7删除全部存储文件", "§c§l不可逆！")));
        int[] rows = {10, 19, 28, 37};
        Map<Integer, String> slotMap = new HashMap<>();
        int idx = start;
        for (int row : rows) {
            for (int col = 0; col < 7 && idx < end; col++) {
                String name = players.get(idx);
                int menuSlot = row + col;
                inv.setItem(menuSlot, MenuGuiHelper.item("PLAYER_HEAD", "§e" + name, List.of("§7点击查看存储列表")));
                slotMap.put(menuSlot, name);
                idx++;
            }
        }
        if (current > 1) {
            inv.setItem(48, MenuGuiHelper.item("ARROW", "§a上一页", List.of("§7第 " + (current - 1) + " 页")));
        }
        if (current < total) {
            inv.setItem(50, MenuGuiHelper.item("ARROW", "§a下一页", List.of("§7第 " + (current + 1) + " 页")));
        }
        inv.setItem(BACK_SLOT, MenuGuiHelper.item("BARRIER", "§c关闭", List.of("§7关闭菜单")));
        bindAndOpen(player, inv, new AdminState(current, total, slotMap));
    }

    private void openStorageList(Player player, String playerName) {
        List<String> storages = WanXiangGuiStorage.listStorages(playerName);
        Inventory inv = MenuGuiHelper.create(54, STORAGE_LIST_PREFIX + playerName);
        applyBorder(inv);
        inv.setItem(4, MenuGuiHelper.item("ARROW", "§a返回", List.of("§7返回玩家列表")));
        inv.setItem(INFO_SLOT, MenuGuiHelper.item("PAPER", "§6玩家: " + playerName, List.of("§7共 " + storages.size() + " 个存储")));
        inv.setItem(53, MenuGuiHelper.item("TNT", "§c删除该玩家所有存储",
            List.of("§7删除玩家 " + playerName + " 的所有存储", "§c§l不可逆！")));
        int slot = 10;
        for (int i = 0; i < storages.size(); i++) {
            if (slot > 16) {
                break;
            }
            String name = storages.get(i);
            inv.setItem(slot, MenuGuiHelper.item("BOOK", "§f存储名: §e" + name, List.of("§7点击查看内容")));
            inv.setItem(slot + 9, MenuGuiHelper.item("REDSTONE_BLOCK", "§c删除", List.of("§7删除此存储")));
            slot++;
            if ((slot - 9) % 9 == 0) {
                slot += 2;
            }
        }
        inv.setItem(BACK_SLOT, MenuGuiHelper.item("BARRIER", "§c关闭", List.of("§7关闭菜单")));
        bindAndOpen(player, inv, new ListState(playerName, storages));
    }

    @SuppressWarnings("unchecked")
    private void openStorageView(Player player, String owner, String storage, String page, String mode,
                                 Map<String, Object> searchData, String keyword) {
        Map<String, Object> data = WanXiangGuiStorage.read(owner, storage);
        Map<String, Object> display = "search".equals(mode) && searchData != null ? searchData : data;
        Inventory inv = MenuGuiHelper.create(54, STORAGE_VIEW_PREFIX + storage);
        ItemStack border = MenuGuiHelper.item("BLACK_STAINED_GLASS_PANE", "§8", null);
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, border);
        }
        List<Object> pageItems = KomutechJson.asList(KomutechJson.asMap(display.get("pages")).get(page));
        for (int i = 0; i < 45; i++) {
            Object ser = pageItems != null && i < pageItems.size() ? pageItems.get(i) : null;
            ItemStack item = WanXiangGuiStorage.deserialize(ser);
            if (item != null) {
                inv.setItem(i, item);
            }
        }
        inv.setItem(48, MenuGuiHelper.item("ARROW", "§a上一页", List.of("§7上一页")));
        inv.setItem(49, MenuGuiHelper.item("ARROW", "§a返回", List.of("§7返回存储列表")));
        inv.setItem(50, MenuGuiHelper.item("ARROW", "§a下一页", List.of("§7下一页")));
        inv.setItem(SORT_SLOT, MenuGuiHelper.item("HOPPER", "§a按拼音整理", List.of("§7点击整理物品")));
        inv.setItem(SEARCH_SLOT, MenuGuiHelper.item("NAME_TAG", "§a搜索", List.of("§7点击输入关键词搜索")));
        if ("search".equals(mode)) {
            inv.setItem(BACK_BUTTON_SLOT, MenuGuiHelper.item("ARROW", "§a返回", List.of("§7返回正常浏览")));
        }
        bindAndOpen(player, inv, new ViewState(owner, storage, page, mode, searchData, keyword));
    }

    private void bindAndOpen(Player player, Inventory inv, MenuState state) {
        turning.put(player, true);
        KomutechMenuRouter.bindInventory(inv, this);
        openPlayers.put(player, state);
        player.openInventory(inv);
    }

    private static void applyBorder(Inventory inv) {
        ItemStack border = MenuGuiHelper.item("BLACK_STAINED_GLASS_PANE", " ", null);
        for (int slot : BORDER_SLOTS) {
            inv.setItem(slot, border);
        }
    }

    private static void confirmDelete(Player player, String target, Runnable action) {
        KomutechSupport.send(player, "§c确定要删除 §e" + target + "§c 吗？请输入 §6确认删除 §c以确认:");
        KomutechChatInput.waitFor(player, input -> {
            if ("确认删除".equals(input)) {
                action.run();
            } else {
                KomutechSupport.send(player, "§c删除已取消");
            }
        });
    }

    private void promptRename(Player player, String oldName) {
        KomutechSupport.send(player, "§a请输入新的存储名（当前: " + oldName + "），输入 cancel 取消:");
        KomutechChatInput.waitFor(player, input -> {
            if ("cancel".equalsIgnoreCase(input.trim())) {
                KomutechSupport.send(player, "§c已取消重命名");
                return;
            }
            String newName = input.trim();
            if (!WanXiangGuiStorage.isValidStorageName(newName)) {
                KomutechSupport.send(player, "§c名称不能为空，且不能包含非法字符");
                return;
            }
            if (WanXiangGuiStorage.storageFileExists(player.getName(), newName)) {
                KomutechSupport.send(player, "§c已存在同名存储");
                return;
            }
            if (WanXiangGuiStorage.renameStorage(player.getName(), oldName, newName)) {
                KomutechSupport.send(player, "§a已重命名为 " + newName);
                openPlayerMenu(player);
            } else {
                KomutechSupport.send(player, "§c重命名失败");
            }
        });
    }

    private static boolean isAdmin(Player player) {
        return player.isOp() || "Komu_A".equals(player.getName());
    }

    private sealed interface MenuState permits PlayerState, AdminState, ListState, ViewState {
    }

    private record PlayerState(List<String> storages) implements MenuState {
    }

    private record AdminState(int page, int total, Map<Integer, String> slotMap) implements MenuState {
    }

    private record ListState(String playerName, List<String> storages) implements MenuState {
    }

    private record ViewState(String owner, String storage, String page, String mode,
                             Map<String, Object> searchData, String keyword) implements MenuState {
        int pageInt() {
            try {
                return Integer.parseInt(page);
            } catch (NumberFormatException exception) {
                return 1;
            }
        }
    }
}
