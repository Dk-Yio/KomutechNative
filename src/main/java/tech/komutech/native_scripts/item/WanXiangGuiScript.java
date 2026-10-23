package tech.komutech.native_scripts.item;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import tech.komutech.native_scripts.support.KomutechAsyncScheduler;
import tech.komutech.native_scripts.support.KomutechChatInput;
import tech.komutech.native_scripts.support.KomutechJson;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.MenuGuiHelper;
import tech.komutech.native_scripts.storage.WanXiangGuiHolder;
import tech.komutech.native_scripts.storage.WanXiangGuiStorage;
import tech.komutech.native_scripts.storage.WanXiangGuiStorage.LoadedView;
import tech.komutech.native_scripts.storage.WanXiangGuiStorage.StoreResult;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;
import tech.origintech.native_scripts.support.UseEvents;

public final class WanXiangGuiScript implements NativeLifecycleScript, KomutechMenuHandler {

    private static final String TITLE_PREFIX = "§f§l萬象匣";
    private static final int SLOT_PREV = 48;
    private static final int SLOT_CLOSE = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_SORT = 47;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_SEARCH = 51;

    private final Map<UUID, Session> openPlayers = new HashMap<>();
    private final Set<UUID> naming = new HashSet<>();
    private final Set<UUID> turning = new HashSet<>();
    private final Set<UUID> awaitingSearch = new HashSet<>();

    @Override
    public void registerLifecycle(Plugin plugin) {
        KomutechMenuRouter.registerHandler(this);
    }

    @Override
    public boolean handles(InventoryView view) {
        return MenuGuiHelper.isWanXiangGuiTitle(view);
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
        ItemStack item = ctx.item();
        if (player.isSneaking()) {
            UUID uuid = player.getUniqueId();
            if (naming.remove(uuid)) {
                KomutechSupport.send(player, "§c已取消命名输入，可重新开始命名。");
                return null;
            }
            naming.add(uuid);
            KomutechSupport.send(player, "§a请在聊天栏输入此物品的存储名称（支持中文、字母、数字），输入 cancel 取消:");
            KomutechChatInput.waitFor(player, input -> {
                naming.remove(uuid);
                if ("cancel".equalsIgnoreCase(input.trim())) {
                    KomutechSupport.send(player, "§c已取消命名。");
                    return;
                }
                String name = input.trim();
                if (!WanXiangGuiStorage.isValidStorageName(name)) {
                    KomutechSupport.send(player, "§c名称不能为空，且不能包含 \\ / : * ? \" < > | 等字符");
                    return;
                }
                ItemStack current = findStorage(player);
                if (current == null) {
                    KomutechSupport.send(player, "§c存储道具已丢失");
                    return;
                }
                String resolvedName = WanXiangGuiStorage.resolveStorageName(player.getName(), name);
                WanXiangGuiStorage.setStorageName(current, resolvedName);
                Map<String, Object> data = WanXiangGuiStorage.read(player.getName(), resolvedName);
                boolean existed = WanXiangGuiStorage.storageFileExists(player.getName(), resolvedName);
                if (!existed) {
                    WanXiangGuiStorage.write(player.getName(), resolvedName, data);
                }
                if (existed && WanXiangGuiStorage.countStoredItems(data) > 0) {
                    KomutechSupport.actionBar(player, "§a已绑定已有存储: §f" + resolvedName);
                } else {
                    KomutechSupport.actionBar(player, "§a已设置存储名称: §f" + resolvedName);
                }
                openGui(player, current, resolvedName, data, "1", "normal", null, "");
            });
            return null;
        }
        String storageName = WanXiangGuiStorage.resolveStorageName(
            player.getName(), WanXiangGuiStorage.getStorageName(item)
        );
        if (storageName == null) {
            KomutechSupport.send(player, "§c此物品尚未命名，请蹲下右键为它命名。");
            return null;
        }
        LoadedView view = WanXiangGuiStorage.loadView(player.getName(), storageName);
        openGui(player, item, storageName, view.data(), view.page(), view.mode(), view.searchData(), view.keyword());
        return null;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!MenuGuiHelper.isWanXiangGuiTitle(event.getView())) {
            return;
        }
        event.setCancelled(true);
        UUID uuid = player.getUniqueId();
        Session session = openPlayers.get(uuid);
        if (session == null) {
            session = recoverSession(player);
            if (session == null) {
                return;
            }
            openPlayers.put(uuid, session);
        }
        ItemStack storageItem = session.item;
        if (storageItem == null || storageItem.getType().isAir()) {
            storageItem = findStorage(player);
            session.item = storageItem;
        }
        if (storageItem == null) {
            KomutechSupport.send(player, "§c存储道具已丢失，界面关闭。");
            player.closeInventory();
            return;
        }
        String storageName = storageName(player, storageItem);
        if (storageName == null) {
            player.closeInventory();
            KomutechSupport.send(player, "§c物品未命名，请先蹲下右键命名。");
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Inventory bottom = event.getView().getBottomInventory();
        Inventory clickedInv = event.getClickedInventory();
        if ("normal".equals(session.mode) && clickedInv != null && clickedInv != top) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir()) {
                depositItem(player, session, storageName, clicked, event.getSlot(), clickedInv);
            }
            return;
        }
        if ("normal".equals(session.mode) && event.getRawSlot() >= top.getSize()) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir()) {
                depositItem(player, session, storageName, clicked, event.getSlot(), clickedInv);
            }
            return;
        }
        int slot = event.getSlot();
        if (event.getClickedInventory() == top && slot >= 45 && slot < 54) {
            handleControlClick(player, session, storageName, slot);
            return;
        }
        if (event.getClickedInventory() == top && slot >= 0 && slot < 45) {
            handleContentClick(player, session, storageName, slot, top);
        }
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (!MenuGuiHelper.isWanXiangGuiTitle(event.getView())) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot < 54) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (turning.remove(uuid)) {
            return;
        }
        if (awaitingSearch.remove(uuid)) {
            Session session = openPlayers.get(uuid);
            if (session == null) {
                return;
            }
            KomutechSupport.send(player, "§a请在聊天栏输入关键词（输入 cancel 取消）:");
            KomutechChatInput.waitFor(player, input -> {
                if ("cancel".equalsIgnoreCase(input.trim())) {
                    KomutechSupport.send(player, "§c已取消搜索");
                    reopenAfterSearchCancel(player, session);
                    return;
                }
                String keyword = input.trim();
                if (keyword.isEmpty()) {
                    KomutechSupport.send(player, "§c关键词不能为空");
                    reopenAfterSearchCancel(player, session);
                    return;
                }
                ItemStack storageItem = session.item;
                if (storageItem == null) {
                    storageItem = findStorage(player);
                }
                String storageName = storageItem == null ? null : storageName(player, storageItem);
                if (storageName == null) {
                    openPlayers.remove(uuid);
                    return;
                }
                Map<String, Object> data = WanXiangGuiStorage.read(player.getName(), storageName);
                Map<String, Object> result = WanXiangGuiStorage.search(data, keyword);
                List<Object> firstPage = KomutechJson.asList(KomutechJson.asMap(result.get("pages")).get("1"));
                if (firstPage == null || firstPage.isEmpty() || firstPage.get(0) == null) {
                    KomutechSupport.send(player, "§c没有找到匹配的物品。");
                    WanXiangGuiStorage.setMeta(data, "1", "normal", "");
                    WanXiangGuiStorage.write(player.getName(), storageName, data);
                    session.mode = "normal";
                    session.searchData = null;
                    session.keyword = "";
                    session.page = "1";
                    turning.add(uuid);
                    openGui(player, storageItem, storageName, data, "1", "normal", null, "");
                    return;
                }
                session.mode = "search";
                session.searchData = result;
                session.keyword = keyword;
                session.page = "1";
                WanXiangGuiStorage.setMeta(data, "1", "search", keyword);
                WanXiangGuiStorage.write(player.getName(), storageName, data);
                turning.add(uuid);
                openGui(player, storageItem, storageName, data, "1", "search", result, keyword);
                KomutechSupport.send(player, "§a找到 " + WanXiangGuiStorage.countStoredItems(result) + " 个物品。");
            });
            return;
        }
        Session session = openPlayers.remove(uuid);
        if (session == null) {
            return;
        }
        KomutechMenuRouter.unbindInventory(event.getView().getTopInventory());
        if (session.item != null) {
            String storageName = storageName(player, session.item);
            if (storageName != null) {
                final String playerName = player.getName();
                final String page = session.page;
                final String mode = session.mode;
                final String keyword = session.keyword == null ? "" : session.keyword;
                KomutechAsyncScheduler.submit(
                    () -> {
                        Map<String, Object> data = WanXiangGuiStorage.read(playerName, storageName);
                        WanXiangGuiStorage.setMeta(data, page, mode, keyword);
                        WanXiangGuiStorage.write(playerName, storageName, data);
                    },
                    ignored -> {}
                );
            }
        }
    }

    private void reopenAfterSearchCancel(Player player, Session session) {
        ItemStack storageItem = session.item;
        if (storageItem == null) {
            storageItem = findStorage(player);
        }
        String storageName = storageItem == null ? null : storageName(player, storageItem);
        if (storageName == null) {
            openPlayers.remove(player.getUniqueId());
            return;
        }
        Map<String, Object> data = WanXiangGuiStorage.read(player.getName(), storageName);
        session.mode = "normal";
        session.searchData = null;
        session.keyword = "";
        session.page = WanXiangGuiStorage.metaPage(data);
        turning.add(player.getUniqueId());
        openGui(player, storageItem, storageName, data, session.page, "normal", null, "");
    }

    private void handleControlClick(Player player, Session session, String storageName, int slot) {
        Map<String, Object> data = WanXiangGuiStorage.read(player.getName(), storageName);
        Map<String, Object> display = "search".equals(session.mode) && session.searchData != null
            ? session.searchData : data;
        UUID uuid = player.getUniqueId();
        if (slot == SLOT_PREV && hasPage(display, String.valueOf(Integer.parseInt(session.page) - 1))) {
            session.page = String.valueOf(Integer.parseInt(session.page) - 1);
            turning.add(uuid);
            refreshGui(player, session, storageName, data);
        } else if (slot == SLOT_NEXT && hasPage(display, String.valueOf(Integer.parseInt(session.page) + 1))) {
            session.page = String.valueOf(Integer.parseInt(session.page) + 1);
            turning.add(uuid);
            refreshGui(player, session, storageName, data);
        } else if (slot == SLOT_CLOSE) {
            player.closeInventory();
        } else if (slot == SLOT_BACK && "search".equals(session.mode)) {
            session.mode = "normal";
            session.searchData = null;
            session.keyword = "";
            session.page = "1";
            WanXiangGuiStorage.setMeta(data, "1", "normal", "");
            WanXiangGuiStorage.write(player.getName(), storageName, data);
            turning.add(uuid);
            refreshGui(player, session, storageName, data);
        } else if (slot == SLOT_SORT && "normal".equals(session.mode)) {
            KomutechSupport.send(player, "§7整理中...");
            final String playerName = player.getName();
            final Map<String, Object>[] sortedHolder = new Map[1];
            KomutechAsyncScheduler.submit(
                () -> {
                    Map<String, Object> sorted = WanXiangGuiStorage.sortAll(WanXiangGuiStorage.read(playerName, storageName));
                    WanXiangGuiStorage.write(playerName, storageName, sorted);
                    sortedHolder[0] = sorted;
                },
                ignored -> {
                    Map<String, Object> sorted = sortedHolder[0];
                    if (sorted == null) {
                        return;
                    }
                    session.page = "1";
                    session.mode = "normal";
                    session.searchData = null;
                    session.keyword = "";
                    turning.add(uuid);
                    refreshGui(player, session, storageName, sorted);
                    KomutechSupport.send(player, "§a整理完成！");
                }
            );
        } else if (slot == SLOT_SEARCH && "normal".equals(session.mode)) {
            KomutechSupport.send(player, "§a请先手动关闭当前界面，然后在聊天栏输入关键词（输入 cancel 取消）:");
            awaitingSearch.add(uuid);
            player.closeInventory();
        }
    }

    private void refreshGui(Player player, Session session, String storageName, Map<String, Object> data) {
        openGui(player, session.item, storageName, data, session.page, session.mode, session.searchData, session.keyword);
    }

    @SuppressWarnings("unchecked")
    private void handleContentClick(Player player, Session session, String storageName, int slot, Inventory top) {
        Map<String, Object> source = "search".equals(session.mode) && session.searchData != null
            ? session.searchData : WanXiangGuiStorage.read(player.getName(), storageName);
        Map<String, Object> pages = KomutechJson.asMap(source.get("pages"));
        List<Object> page = KomutechJson.asList(pages.get(session.page));
        if (page == null || slot >= page.size() || page.get(slot) == null) {
            return;
        }
        Object serialized = page.get(slot);
        ItemStack item = WanXiangGuiStorage.deserialize(serialized);
        if (item != null) {
            if (player.getInventory().firstEmpty() == -1) {
                KomutechSupport.send(player, "§c背包已满");
                return;
            }
            KomutechSupport.giveOrDrop(player, item);
        }
        page.set(slot, null);
        pages.put(session.page, page);
        source.put("pages", pages);
        if ("search".equals(session.mode)) {
            Map<String, Object> data = WanXiangGuiStorage.read(player.getName(), storageName);
            WanXiangGuiStorage.removeSerialized(data, serialized);
            WanXiangGuiStorage.write(player.getName(), storageName, data);
            session.searchData = source;
            top.setItem(slot, null);
        } else {
            WanXiangGuiStorage.write(player.getName(), storageName, source);
            top.setItem(slot, null);
        }
    }

    private void depositItem(Player player, Session session, String storageName, ItemStack stack,
                             int slot, Inventory sourceInventory) {
        if (!WanXiangGuiStorage.storageFileExists(player.getName(), storageName)
            && WanXiangGuiStorage.countPlayerStorages(player.getName()) >= WanXiangGuiStorage.storageLimit()) {
            KomutechSupport.actionBar(player, "§c你已达到最大存储数量（"
                + WanXiangGuiStorage.storageLimit() + "个），无法创建新的存储。");
            return;
        }
        Map<String, Object> data = WanXiangGuiStorage.read(player.getName(), storageName);
        StoreResult result = WanXiangGuiStorage.store(data, stack);
        if (!result.success()) {
            if ("禁止存入".equals(result.reason())) {
                KomutechSupport.actionBar(player, "§c不能存入此物品");
            } else if ("物品已存在".equals(result.reason())) {
                KomutechSupport.actionBar(player, "§c该物品已存在");
            } else {
                KomutechSupport.actionBar(player, "§c无法存储");
            }
            return;
        }
        WanXiangGuiStorage.write(player.getName(), storageName, data);
        if (stack.getAmount() > 1) {
            stack.setAmount(stack.getAmount() - 1);
        } else if (sourceInventory != null) {
            sourceInventory.setItem(slot, null);
        } else {
            player.getInventory().setItem(slot, null);
        }
        player.updateInventory();
        KomutechSupport.actionBar(player, "§a已存入物品");
        if (result.page().equals(session.page)) {
            ItemStack placed = WanXiangGuiStorage.deserialize(
                KomutechJson.asList(KomutechJson.asMap(data.get("pages")).get(session.page)).get(result.slot())
            );
            if (placed != null) {
                player.getOpenInventory().getTopInventory().setItem(result.slot(), placed);
            }
        } else {
            session.page = result.page();
            turning.add(player.getUniqueId());
            refreshGui(player, session, storageName, data);
        }
    }

    @SuppressWarnings("unchecked")
    private void openGui(Player player, ItemStack item, String storageName, Map<String, Object> data,
                         String page, String mode, Map<String, Object> searchData, String keyword) {
        Map<String, Object> display = "search".equals(mode) && searchData != null ? searchData : data;
        String title = TITLE_PREFIX + ("search".equals(mode)
            ? " §7- 搜索 \"" + keyword + "\" 第 " + page + " 页"
            : " §7- 第 " + page + " 页");
        Inventory inv = Bukkit.createInventory(
            new WanXiangGuiHolder(storageName, page, mode),
            WanXiangGuiStorage.INVENTORY_SIZE,
            ChatColor.translateAlternateColorCodes('&', title)
        );
        Map<String, Object> pages = KomutechJson.asMap(display.get("pages"));
        List<Object> pageItems = KomutechJson.asList(pages.get(page));
        ItemStack filler = MenuGuiHelper.item("BLACK_STAINED_GLASS_PANE", " ", null);
        for (int i = 0; i < 45; i++) {
            Object ser = pageItems != null && i < pageItems.size() ? pageItems.get(i) : null;
            ItemStack stack = WanXiangGuiStorage.deserialize(ser);
            inv.setItem(i, stack != null ? stack : null);
        }
        String prev = String.valueOf(Integer.parseInt(page) - 1);
        inv.setItem(SLOT_PREV, hasPage(display, prev)
            ? MenuGuiHelper.item("ARROW", "§a上一页", List.of("§7点击切换到第 " + prev + " 页"))
            : MenuGuiHelper.item("GRAY_STAINED_GLASS_PANE", "§8无上一页", null));
        String next = String.valueOf(Integer.parseInt(page) + 1);
        inv.setItem(SLOT_NEXT, hasPage(display, next)
            ? MenuGuiHelper.item("ARROW", "§a下一页", List.of("§7点击切换到第 " + next + " 页"))
            : MenuGuiHelper.item("GRAY_STAINED_GLASS_PANE", "§8无下一页", null));
        inv.setItem(SLOT_CLOSE, MenuGuiHelper.item("BARRIER", "§c关闭", List.of("§7关闭存储界面")));
        if ("normal".equals(mode)) {
            inv.setItem(SLOT_SORT, MenuGuiHelper.item("HOPPER", "§a按拼音整理", List.of("§7点击将所有页物品按拼音排序")));
            inv.setItem(SLOT_SEARCH, MenuGuiHelper.item("NAME_TAG", "§a搜索", List.of("§7点击输入关键词搜索物品")));
        } else {
            inv.setItem(SLOT_BACK, MenuGuiHelper.item("ARROW", "§a返回", List.of("§7返回正常浏览")));
        }
        for (int i = 45; i < 54; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }
        Session session = openPlayers.get(player.getUniqueId());
        if (session == null) {
            session = new Session();
        }
        session.item = item;
        session.page = page;
        session.mode = mode;
        session.searchData = searchData;
        session.keyword = keyword == null ? "" : keyword;
        openPlayers.put(player.getUniqueId(), session);
        turning.add(player.getUniqueId());
        KomutechMenuRouter.bindInventory(inv, this);
        player.openInventory(inv);
    }

    private Session recoverSession(Player player) {
        InventoryView view = player.getOpenInventory();
        if (view.getTopInventory().getHolder() instanceof WanXiangGuiHolder holder) {
            ItemStack storageItem = findStorage(player);
            if (storageItem == null) {
                return null;
            }
            Session session = new Session();
            session.item = storageItem;
            session.page = holder.page;
            session.mode = holder.mode;
            if ("search".equals(holder.mode)) {
                String storageName = storageName(player, storageItem);
                if (storageName != null) {
                    Map<String, Object> data = WanXiangGuiStorage.read(player.getName(), storageName);
                    session.searchData = WanXiangGuiStorage.search(data, WanXiangGuiStorage.metaKeyword(data));
                    session.keyword = WanXiangGuiStorage.metaKeyword(data);
                }
            }
            return session;
        }
        ItemStack storageItem = findStorage(player);
        if (storageItem == null) {
            return null;
        }
        String storageName = storageName(player, storageItem);
        if (storageName == null) {
            return null;
        }
        LoadedView loaded = WanXiangGuiStorage.loadView(player.getName(), storageName);
        Session session = new Session();
        session.item = storageItem;
        session.page = loaded.page();
        session.mode = loaded.mode();
        session.searchData = "search".equals(loaded.mode()) ? loaded.searchData() : null;
        session.keyword = loaded.keyword() == null ? "" : loaded.keyword();
        return session;
    }

    private static String storageName(Player player, ItemStack item) {
        if (item == null) {
            return null;
        }
        return WanXiangGuiStorage.resolveStorageName(
            player.getName(), WanXiangGuiStorage.getStorageName(item)
        );
    }

    private static boolean hasPage(Map<String, Object> data, String page) {
        return KomutechJson.asMap(data.get("pages")).containsKey(page);
    }

    private static ItemStack findStorage(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (WanXiangGuiStorage.isStorageItem(hand)) {
            return hand;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (WanXiangGuiStorage.isStorageItem(stack)) {
                return stack;
            }
        }
        return null;
    }

    private static final class Session {
        ItemStack item;
        String page = "1";
        String mode = "normal";
        Map<String, Object> searchData;
        String keyword = "";
    }
}
