package tech.komutech.native_scripts.machine;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import tech.komutech.native_scripts.combat.ScrollCombatEngine;
import tech.komutech.native_scripts.support.KomutechJson;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.MachineMenuGuard;
import tech.komutech.native_scripts.support.MainThread;
import tech.komutech.native_scripts.support.MenuGuiHelper;
import tech.komutech.native_scripts.support.PlayerAttributeStore;
import tech.komutech.native_scripts.storage.WanXiangGuiStorage;
import tech.komutech.native_scripts.storage.WanYanYiMachineStorage;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;

public final class WanYanYiScript implements NativeScript, NativeLifecycleScript, KomutechMenuHandler {

    private static final String SEARCH_ITEM_ID = "MAGIC_EXPANSION_ITEM_NAME_TAG";
    private static final int INPUT_SLOT = 49;
    private static final int LOAD_BUTTON = 53;
    private static final int PREV_SLOT = 48;
    private static final int NEXT_SLOT = 50;
    private static final int SORT_SLOT = 47;
    private static final int SEARCH_SLOT = 51;
    /** 灵杖基础消耗3，万衍仪按取出数量计，系数显著降低 */
    private static final double WITHDRAW_BASE_PER_ITEM = 0.35;
    private static final double WITHDRAW_GENGU_SCALE = 0.25;
    /** ChestMenu 脚本返回值：false=取消点击（禁止拿走装饰） */
    private static final Boolean BLOCK_CLICK = Boolean.FALSE;

    private final Map<Player, Session> openPlayers = new HashMap<>();
    private static final Map<UUID, SavedState> savedStates = new ConcurrentHashMap<>();
    /** ChestMenu onClick 与 Bukkit InventoryClickEvent 会各触发一次，同一 tick 只处理一次 */
    private final Map<UUID, Long> handledClickTicks = new ConcurrentHashMap<>();

    @Override
    public void registerLifecycle(Plugin plugin) {
        KomutechMenuRouter.registerHandler(this);
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onInventoryOpen(InventoryOpenEvent event) {
                if (!(event.getPlayer() instanceof Player player)) {
                    return;
                }
                if (!handles(event.getView())) {
                    return;
                }
                KomutechMenuRouter.bindInventory(event.getView().getTopInventory(), WanYanYiScript.this);
                restoreBoundStorageItem(player);
            }
        }, plugin);
    }

    @Override
    public boolean handles(InventoryView view) {
        if (MachineMenuGuard.isOwnMachine(view, "KOMUTECH_L_ZJ_萬衍儀")) {
            return true;
        }
        return isWanYanYiTitle(MenuGuiHelper.legacyTitle(view));
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onOpen".equals(functionName) && args[0] instanceof Player player) {
            onOpen(player);
            return null;
        }
        if ("onClose".equals(functionName) && args[0] instanceof Player player) {
            onClose(player);
            return null;
        }
        if ("onClick".equals(functionName) && args.length >= 4 && args[0] instanceof Player player) {
            int slot = args[1] instanceof Number number ? number.intValue() : -1;
            if (!handles(player.getOpenInventory())) {
                return null;
            }
            if (slot == INPUT_SLOT) {
                scheduleInputSlotSync(player);
                return null;
            }
            handleScriptClick(player, slot, args[3]);
            return BLOCK_CLICK;
        }
        return null;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryView view = event.getView();
        int topSize = view.getTopInventory().getSize();
        int rawSlot = event.getRawSlot();

        // Shift 从背包塞入机器：只允许手动点击 49 槽，禁止批量 shift
        if (event.getClickedInventory() == view.getBottomInventory() && event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }

        if (rawSlot < 0 || rawSlot >= topSize) {
            // 双击收集同类物品到光标时 rawSlot 可能为 -999，需拦截从 GUI 拿走装饰物
            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getAction() == InventoryAction.UNKNOWN && event.getClick() == ClickType.DOUBLE_CLICK) {
                event.setCancelled(true);
            }
            return;
        }
        if (allowInputSlotInteraction(event)) {
            event.setCancelled(false);
            scheduleInputSlotSync(player);
            return;
        }
        event.setCancelled(true);
        if (rawSlot >= 0 && rawSlot < topSize) {
            handleScriptClick(player, rawSlot, event);
        }
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        MachineMenuGuard.protectTopInventoryDrag(event, slot -> slot == INPUT_SLOT);
        if (event.isCancelled()) {
            return;
        }
        if (event.getRawSlots().contains(INPUT_SLOT) && event.getWhoClicked() instanceof Player player) {
            scheduleInputSlotSync(player);
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        handledClickTicks.remove(player.getUniqueId());
        Session session = openPlayers.remove(player);
        if (session != null) {
            persistSession(player, session, event.getView().getTopInventory());
        }
        KomutechMenuRouter.unbindInventory(event.getView().getTopInventory());
    }

    private void onClose(Player player) {
        handledClickTicks.remove(player.getUniqueId());
        Session session = openPlayers.remove(player);
        if (session != null) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (handles(player.getOpenInventory())) {
                persistSession(player, session, top);
            } else {
                saveState(player, session);
            }
        }
    }

    private void onOpen(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        KomutechMenuRouter.bindInventory(top, this);
        restoreBoundStorageItem(player);
        WanXiangGuiStorage.ReadResult loaded = getStorageData(top, player.getName());
        if (loaded == null || loaded.unnamed()) {
            fillEmpty(top);
            openPlayers.put(player, new Session());
            if (loaded != null && loaded.unnamed()) {
                KomutechSupport.send(player, "§c萬象匱未命名，无法加载数据。");
            }
            return;
        }
        LoadedState state = loadState(player, loaded);
        Session session = new Session();
        session.page = state.page;
        session.mode = state.mode;
        session.searchData = state.searchData;
        session.keyword = state.keyword;
        session.loaded = loaded.data();
        session.storageKey = WanXiangGuiStorage.getStorageName(top.getItem(INPUT_SLOT));
        openPlayers.put(player, session);
        fillGui(top, loaded.data(), state.page, state.mode, state.searchData, state.keyword);
    }

    private void restoreBoundStorageItem(Player player) {
        Location location = resolveMachineLocation(player);
        if (location == null) {
            return;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        ItemStack current = top.getItem(INPUT_SLOT);
        if (current != null && !current.getType().isAir()) {
            WanYanYiMachineStorage.save(location, current);
            return;
        }
        ItemStack bound = WanYanYiMachineStorage.load(location);
        if (bound != null) {
            top.setItem(INPUT_SLOT, bound);
        }
    }

    private boolean handleScriptClick(Player player, int slot, Object clickSource) {
        long tick = Bukkit.getCurrentTick();
        Long previous = handledClickTicks.put(player.getUniqueId(), tick);
        if (previous != null && previous == tick) {
            return true;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        Session session = ensureSession(player, top);
        if (session == null) {
            return true;
        }
        if (slot == INPUT_SLOT) {
            return false;
        }
        if (slot >= 45 && slot < 54) {
            handleControl(player, top, session, slot);
            return true;
        }
        if (slot >= 0 && slot < 45) {
            handleWithdraw(player, session, slot, clickSource);
            return true;
        }
        return true;
    }

    private Session ensureSession(Player player, Inventory top) {
        Session session = openPlayers.get(player);
        if (session != null) {
            return session;
        }
        WanXiangGuiStorage.ReadResult loaded = getStorageData(top, player.getName());
        if (loaded == null || loaded.unnamed()) {
            KomutechSupport.send(player, "§c请先在49号槽放入已命名的萬象匱");
            return null;
        }
        LoadedState state = loadState(player, loaded);
        session = new Session();
        session.page = state.page;
        session.mode = state.mode;
        session.searchData = state.searchData;
        session.keyword = state.keyword;
        session.loaded = loaded.data();
        session.storageKey = WanXiangGuiStorage.getStorageName(top.getItem(INPUT_SLOT));
        openPlayers.put(player, session);
        return session;
    }

    private void handleControl(Player player, Inventory top, Session session, int slot) {
        if (slot == LOAD_BUTTON) {
            WanXiangGuiStorage.ReadResult data = getStorageData(top, player.getName());
            if (data == null || data.unnamed()) {
                KomutechSupport.send(player, "§c请在49号槽放入已命名的萬象匱");
                return;
            }
            session.loaded = data.data();
            session.storageKey = WanXiangGuiStorage.getStorageName(top.getItem(INPUT_SLOT));
            session.mode = "normal";
            session.searchData = null;
            session.keyword = "";
            session.page = "1";
            fillGui(top, data.data(), "1", "normal", null, "");
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            player.spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 8, 0.4, 0.3, 0.4, 0);
            int total = countPages(data.data());
            int items = WanXiangGuiStorage.countStoredItems(data.data());
            KomutechSupport.send(player, "§a已加载萬象匱内容 §7(共 " + total + " 页 / " + items + " 种物品)");
            return;
        }
        if (session.loaded == null) {
            KomutechSupport.send(player, "§c请先点击加载按钮");
            return;
        }
        Map<String, Object> data = "search".equals(session.mode) ? session.searchData : session.loaded;
        int cur = parsePage(session.page);
        if (slot == PREV_SLOT && hasPage(data, String.valueOf(cur - 1))) {
            session.page = String.valueOf(cur - 1);
            fillGui(top, session.loaded, session.page, session.mode, session.searchData, session.keyword);
        } else if (slot == NEXT_SLOT && hasPage(data, String.valueOf(cur + 1))) {
            session.page = String.valueOf(cur + 1);
            fillGui(top, session.loaded, session.page, session.mode, session.searchData, session.keyword);
        } else if (slot == SORT_SLOT && "normal".equals(session.mode)) {
            Map<String, Object> sorted = WanXiangGuiStorage.sortAll(session.loaded);
            session.loaded = sorted;
            ItemStack storageItem = top.getItem(INPUT_SLOT);
            WanXiangGuiStorage.writeForItem(storageItem, player.getName(), sorted);
            String curKey = String.valueOf(cur);
            List<Object> page = KomutechJson.asList(KomutechJson.asMap(sorted.get("pages")).get(curKey));
            session.page = page != null && page.subList(0, Math.min(45, page.size())).stream().anyMatch(v -> v != null)
                ? curKey : "1";
            fillGui(top, sorted, session.page, "normal", null, "");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.5f);
            KomutechSupport.send(player, "§a整理完成！");
        } else if (slot == SEARCH_SLOT) {
            handleSearch(player, top, session);
        }
    }

    private void handleSearch(Player player, Inventory top, Session session) {
        ItemStack searchItem = findSearchItem(player);
        if (searchItem == null) {
            KomutechSupport.send(player, "§c你没有搜索道具，请手持§a魔法2的便携式命名牌 §c或已§a命名的命名牌后点击搜索");
            return;
        }
        ItemMeta meta = searchItem.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            KomutechSupport.send(player, "§c搜索道具没有名称");
            return;
        }
        String keyword = ChatColor.stripColor(meta.getDisplayName()).trim();
        if (keyword.isEmpty()) {
            KomutechSupport.send(player, "§c搜索道具名称为空");
            return;
        }
        Map<String, Object> result = WanXiangGuiStorage.search(session.loaded, keyword);
        List<Object> firstPage = KomutechJson.asList(KomutechJson.asMap(result.get("pages")).get("1"));
        if (firstPage == null || firstPage.isEmpty() || firstPage.get(0) == null) {
            KomutechSupport.send(player, "§c没有找到匹配的物品");
            return;
        }
        session.mode = "search";
        session.searchData = result;
        session.keyword = keyword;
        session.page = "1";
        fillGui(top, session.loaded, "1", "search", result, keyword);
        int count = 0;
        for (Object pageObj : KomutechJson.asMap(result.get("pages")).values()) {
            for (Object ser : KomutechJson.asList(pageObj)) {
                if (ser != null) {
                    count++;
                }
            }
        }
        KomutechSupport.send(player, "§a找到 " + count + " 个物品");
    }

    private void handleWithdraw(Player player, Session session, int slot, Object clickSource) {
        if (session.loaded == null) {
            KomutechSupport.send(player, "§c请先点击加载按钮");
            return;
        }
        Map<String, Object> source = "search".equals(session.mode) && session.searchData != null
            ? session.searchData : session.loaded;
        Map<String, Object> pages = KomutechJson.asMap(source.get("pages"));
        List<Object> page = KomutechJson.asList(pages.get(session.page));
        if (page == null || slot >= page.size() || page.get(slot) == null) {
            return;
        }
        ItemStack proto = WanXiangGuiStorage.deserialize(page.get(slot));
        if (proto == null) {
            return;
        }
        Map<String, Object> attr = PlayerAttributeStore.load(player.getName());
        if (attr == null) {
            KomutechSupport.send(player, "§c无法读取修仙属性");
            return;
        }
        boolean shift = isShiftClick(clickSource);
        boolean right = isRightClick(clickSource);
        int give = resolveWithdrawAmount(player, proto, shift, right);
        if (give <= 0) {
            KomutechSupport.send(player, "§c背包已满");
            return;
        }
        int cost = ScrollCombatEngine.calcGenericSpiritCost(attr, WITHDRAW_BASE_PER_ITEM, give, WITHDRAW_GENGU_SCALE);
        if (!ScrollCombatEngine.tryConsumeSpiritPower(player, attr, cost)) {
            return;
        }
        if (!deliverWithdraw(player, proto, give, shift && right)) {
            KomutechSupport.send(player, "§c背包已满");
            return;
        }
        PlayerAttributeStore.save(player.getName(), attr);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.1f);
        player.spawnParticle(Particle.ITEM, player.getLocation().add(0, 1, 0), 6, 0.2, 0.2, 0.2, 0.05, proto);
        PlayerAttributeStore.Lingli ling = PlayerAttributeStore.parseLingli(attr.get("灵力"));
        KomutechSupport.actionBar(player, "§a取出 " + give + " 个 §7| §b消耗灵力 §6" + cost
            + " §7| §f剩余 §6" + String.format("%.0f", ling.current()));
    }

    private static int resolveWithdrawAmount(Player player, ItemStack proto, boolean shift, boolean right) {
        int max = proto.getMaxStackSize();
        if (shift && right) {
            return computeSimilarCapacity(player, proto, max);
        }
        if (shift) {
            return max;
        }
        if (right) {
            return Math.min(8, max);
        }
        return 1;
    }

    private static boolean deliverWithdraw(Player player, ItemStack proto, int give, boolean fillInventory) {
        if (fillInventory) {
            int remaining = give;
            while (remaining > 0) {
                int batch = Math.min(remaining, proto.getMaxStackSize());
                Map<Integer, ItemStack> left = player.getInventory().addItem(cloneAmount(proto, batch));
                if (!left.isEmpty()) {
                    return false;
                }
                remaining -= batch;
            }
            return true;
        }
        KomutechSupport.giveOrDrop(player, cloneAmount(proto, give));
        return true;
    }

    private static int computeSimilarCapacity(Player player, ItemStack proto, int max) {
        PlayerInventory inv = player.getInventory();
        int totalSpace = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) {
                totalSpace += max;
            } else if (stack.isSimilar(proto)) {
                totalSpace += max - stack.getAmount();
            }
        }
        return totalSpace;
    }

    private void scheduleInputSlotSync(Player player) {
        MainThread.run(KomutechSupport.plugin(), () ->
            Bukkit.getScheduler().runTaskLater(KomutechSupport.plugin(), () -> syncInputSlotState(player), 1L)
        );
    }

    private void syncInputSlotState(Player player) {
        if (!player.isOnline()) {
            return;
        }
        InventoryView view = player.getOpenInventory();
        if (!handles(view)) {
            return;
        }
        Inventory top = view.getTopInventory();
        Session session = openPlayers.computeIfAbsent(player, ignored -> new Session());
        ItemStack input = top.getItem(INPUT_SLOT);
        String storageKey = null;
        if (WanXiangGuiStorage.isStorageItem(input)) {
            storageKey = WanXiangGuiStorage.getStorageName(input);
            if (storageKey != null && !WanXiangGuiStorage.isValidStorageName(storageKey)) {
                storageKey = null;
            }
        }
        if (storageKey == null) {
            Location location = resolveMachineLocation(player);
            if (location != null) {
                WanYanYiMachineStorage.clear(location);
            }
            if (session.loaded != null || session.storageKey != null) {
                session.loaded = null;
                session.storageKey = null;
                session.mode = "normal";
                session.searchData = null;
                session.keyword = "";
                session.page = "1";
                fillEmpty(top);
                player.updateInventory();
            } else if (input != null && WanXiangGuiStorage.isStorageItem(input)) {
                KomutechSupport.send(player, "§c萬象匱未命名，无法加载数据。");
            }
            return;
        }
        if (!storageKey.equals(session.storageKey)) {
            session.storageKey = storageKey;
            session.loaded = null;
            session.mode = "normal";
            session.searchData = null;
            session.keyword = "";
            session.page = "1";
            fillEmpty(top);
            Location location = resolveMachineLocation(player);
            if (location != null) {
                WanYanYiMachineStorage.save(location, input);
            }
            player.updateInventory();
        }
    }

    private static ItemStack cloneAmount(ItemStack proto, int amount) {
        ItemStack stack = proto.clone();
        stack.setAmount(Math.min(amount, proto.getMaxStackSize()));
        return stack;
    }

    private static boolean allowInputSlotInteraction(InventoryClickEvent event) {
        if (event.getRawSlot() != INPUT_SLOT) {
            return false;
        }
        if (event.getClickedInventory() == event.getView().getBottomInventory() && event.isShiftClick()) {
            return false;
        }
        ClickType click = event.getClick();
        if (click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND) {
            return true;
        }
        InventoryAction action = event.getAction();
        if (action == InventoryAction.PLACE_ALL
            || action == InventoryAction.PLACE_ONE
            || action == InventoryAction.PLACE_SOME
            || action == InventoryAction.SWAP_WITH_CURSOR) {
            return true;
        }
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            return true;
        }
        ItemStack current = event.getCurrentItem();
        if (current != null && !current.getType().isAir()) {
            return action == InventoryAction.PICKUP_ALL
                || action == InventoryAction.PICKUP_HALF
                || action == InventoryAction.PICKUP_ONE
                || action == InventoryAction.PICKUP_SOME
                || action == InventoryAction.DROP_ALL_SLOT
                || action == InventoryAction.DROP_ONE_SLOT;
        }
        return false;
    }

    private static boolean isShiftClick(Object source) {
        if (source instanceof InventoryClickEvent event) {
            return event.isShiftClick();
        }
        return KomutechSupport.isShiftClick(source);
    }

    private static boolean isRightClick(Object source) {
        if (source instanceof InventoryClickEvent event) {
            ClickType click = event.getClick();
            return click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT;
        }
        return KomutechSupport.isRightClick(source);
    }

    private static boolean isWanYanYiTitle(String title) {
        if (title == null || title.isEmpty()) {
            return false;
        }
        String plain = ChatColor.stripColor(title);
        if (plain.contains("萬衍儀") || plain.contains("万衍仪") || plain.contains("万衍仪典")) {
            return true;
        }
        boolean hasWan = plain.contains("萬") || plain.contains("万");
        boolean hasYan = plain.contains("衍");
        boolean hasYi = plain.contains("儀") || plain.contains("仪") || plain.contains("典");
        return hasWan && hasYan && hasYi;
    }

    private static WanXiangGuiStorage.ReadResult getStorageData(Inventory inv, String playerName) {
        ItemStack item = inv.getItem(INPUT_SLOT);
        return WanXiangGuiStorage.isStorageItem(item) ? WanXiangGuiStorage.readForItem(item, playerName) : null;
    }

    private static ItemStack findSearchItem(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            SlimefunItem sf = SlimefunItem.getByItem(item);
            if (sf != null && SEARCH_ITEM_ID.equals(sf.getId())) {
                return item;
            }
        }
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == org.bukkit.Material.NAME_TAG
                && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                return item;
            }
        }
        return null;
    }

    private static LoadedState loadState(Player player, WanXiangGuiStorage.ReadResult loaded) {
        String storageName = WanXiangGuiStorage.getStorageName(
            player.getOpenInventory().getTopInventory().getItem(INPUT_SLOT)
        );
        if (storageName != null) {
            WanXiangGuiStorage.LoadedView view = WanXiangGuiStorage.loadView(player.getName(), storageName);
            return new LoadedState(view.page(), view.mode(), view.searchData(), view.keyword());
        }
        SavedState saved = savedStates.remove(player.getUniqueId());
        String mode = saved == null ? "normal" : saved.mode;
        String keyword = saved == null ? "" : saved.keyword;
        String page = "1";
        if ("normal".equals(mode) && saved != null && saved.page != null) {
            page = saved.page;
        }
        Map<String, Object> searchData = null;
        if ("search".equals(mode) && keyword != null && !keyword.isEmpty() && loaded != null && !loaded.unnamed()) {
            searchData = WanXiangGuiStorage.search(loaded.data(), keyword);
            List<Object> first = KomutechJson.asList(KomutechJson.asMap(searchData.get("pages")).get("1"));
            if (first == null || first.isEmpty() || first.get(0) == null) {
                mode = "normal";
                keyword = "";
                searchData = null;
            }
            page = "1";
        } else if ("search".equals(mode)) {
            mode = "normal";
            keyword = "";
        }
        return new LoadedState(page, mode, searchData, keyword);
    }

    private static void saveState(Player player, Session session) {
        SavedState saved = new SavedState();
        saved.mode = session.mode == null ? "normal" : session.mode;
        saved.keyword = session.keyword == null ? "" : session.keyword;
        saved.page = session.page == null ? "1" : session.page;
        savedStates.put(player.getUniqueId(), saved);
    }

    private static void persistSession(Player player, Session session, Inventory top) {
        saveState(player, session);
        Location location = resolveMachineLocation(player);
        ItemStack storageItem = top.getItem(INPUT_SLOT);
        if (location != null) {
            WanYanYiMachineStorage.save(location, storageItem);
        }
        if (storageItem == null || !WanXiangGuiStorage.isStorageItem(storageItem)) {
            return;
        }
        String storageName = WanXiangGuiStorage.getStorageName(storageItem);
        if (storageName == null) {
            return;
        }
        Map<String, Object> data = session.loaded;
        if (data == null) {
            data = WanXiangGuiStorage.read(player.getName(), storageName);
        }
        if (data == null) {
            return;
        }
        WanXiangGuiStorage.setMeta(data, session.page, session.mode, session.keyword);
        WanXiangGuiStorage.writeForItem(storageItem, player.getName(), data);
    }

    private static Location resolveMachineLocation(Player player) {
        try {
            Object holder = player.getOpenInventory().getTopInventory().getHolder(false);
            if (holder == null) {
                return null;
            }
            Object location = holder.getClass().getMethod("getLocation").invoke(holder);
            return location instanceof Location blockLocation ? blockLocation : null;
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static void fillEmpty(Inventory inv) {
        ItemStack filler = MenuGuiHelper.item("BLACK_STAINED_GLASS_PANE", " ", null);
        for (int i = 0; i < 45; i++) {
            inv.setItem(i, filler);
        }
        inv.setItem(PREV_SLOT, MenuGuiHelper.item("GRAY_STAINED_GLASS_PANE", "§8", null));
        inv.setItem(NEXT_SLOT, MenuGuiHelper.item("GRAY_STAINED_GLASS_PANE", "§8", null));
        inv.setItem(SEARCH_SLOT, MenuGuiHelper.item("NAME_TAG", "§a搜索", List.of("§7点击使用搜索道具")));
    }

    @SuppressWarnings("unchecked")
    private static void fillGui(Inventory inv, Map<String, Object> data, String page, String mode,
                                Map<String, Object> searchData, String keyword) {
        if (data == null || !data.containsKey("pages")) {
            data = WanXiangGuiStorage.emptyData();
        }
        Map<String, Object> display = "search".equals(mode) && searchData != null ? searchData : data;
        List<Object> pageItems = KomutechJson.asList(KomutechJson.asMap(display.get("pages")).get(page));
        ItemStack filler = MenuGuiHelper.item("BLACK_STAINED_GLASS_PANE", " ", null);
        for (int i = 0; i < 45; i++) {
            Object ser = pageItems != null && i < pageItems.size() ? pageItems.get(i) : null;
            ItemStack item = WanXiangGuiStorage.deserialize(ser);
            inv.setItem(i, item != null ? item : filler);
        }
        int pageNum = parsePage(page);
        int total = countPages(display);
        String prev = String.valueOf(pageNum - 1);
        if (pageNum > 1 && hasPage(display, prev)) {
            inv.setItem(PREV_SLOT, MenuGuiHelper.item("ARROW", "§a上一页 §7(" + pageNum + "/" + total + ")",
                List.of("§7点击切换到第 " + prev + " 页")));
        } else {
            inv.setItem(PREV_SLOT, MenuGuiHelper.item("GRAY_STAINED_GLASS_PANE", "§8", null));
        }
        String next = String.valueOf(pageNum + 1);
        if (hasPage(display, next)) {
            inv.setItem(NEXT_SLOT, MenuGuiHelper.item("ARROW", "§a下一页 §7(" + pageNum + "/" + total + ")",
                List.of("§7点击切换到第 " + next + " 页")));
        } else {
            inv.setItem(NEXT_SLOT, MenuGuiHelper.item("GRAY_STAINED_GLASS_PANE",
                "§8第 " + pageNum + "/" + total + " 页", null));
        }
        if ("normal".equals(mode)) {
            inv.setItem(SEARCH_SLOT, MenuGuiHelper.item("NAME_TAG", "§a搜索", List.of("§7点击使用搜索道具")));
        } else {
            inv.setItem(SEARCH_SLOT, MenuGuiHelper.item("NAME_TAG", "§a搜索 (关键词: " + keyword + ")", List.of("§7点击使用搜索道具")));
        }
    }

    private static boolean hasPage(Map<String, Object> data, String page) {
        return KomutechJson.asMap(data.get("pages")).containsKey(page);
    }

    private static int parsePage(String page) {
        try {
            return Integer.parseInt(page);
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    private static int countPages(Map<String, Object> data) {
        Map<String, Object> pages = KomutechJson.asMap(data.get("pages"));
        int max = 0;
        for (String key : pages.keySet()) {
            try {
                max = Math.max(max, Integer.parseInt(key));
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.max(1, max);
    }

    private static final class Session {
        String page = "1";
        String mode = "normal";
        Map<String, Object> searchData;
        String keyword = "";
        String storageKey;
        Map<String, Object> loaded;
    }

    private static final class SavedState {
        String page = "1";
        String mode = "normal";
        String keyword = "";
    }

    private record LoadedState(String page, String mode, Map<String, Object> searchData, String keyword) {
    }
}
