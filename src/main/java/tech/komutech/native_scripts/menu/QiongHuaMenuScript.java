package tech.komutech.native_scripts.menu;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.ChatColor;
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
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.MenuGuiHelper;
import tech.origintech.native_scripts.NativeLifecycleScript;

public final class QiongHuaMenuScript implements NativeLifecycleScript, KomutechMenuHandler {

    private static final String MAIN_TITLE = "§6§l✨ 琼华阁 ✨";
    private static final String TITLE_PREFIX = "§6§l✨ ";
    private static final String TITLE_SUFFIX = " ✨";
    private static final int[] BORDER = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47, 48, 50, 51, 52, 53};

    private static final Map<Integer, String> MAIN_SLOTS = Map.of(
        10, "jichucailiao", 11, "lingzhizhongzi", 12, "leisi"
    );

    private static final Map<String, String> CATEGORY_NAMES = Map.of(
        "jichucailiao", "§6✦ 基础材料",
        "lingzhizhongzi", "§2✦ 灵植种子",
        "leisi", "§8✦ 儡肆"
    );

    private static final Map<String, List<ShopEntry>> SHOPS = Map.of(
        "jichucailiao", List.of(
            entry("KOMUTECH_L_JCWP_琼华阁通行柬1", "KOMUTECH_L_DJ_ZPLS", 64, "KOMUTECH_L_DJ_功德券", 4),
            entry("KOMUTECH_L_JCWP_SC", "KOMUTECH_L_DJ_XPLS", 16),
            entry("KOMUTECH_L_JCWP_SYM", "KOMUTECH_L_DJ_XPLS", 4),
            entry("KOMUTECH_L_GJ_KCB", "KOMUTECH_L_DJ_XPLS", 8)
        ),
        "lingzhizhongzi", List.of(
            entry("KOMUTECH_L_LZZZ_TSSM", "KOMUTECH_L_DJ_XPLS", 16),
            entry("KOMUTECH_L_LZZZ_SSSM", "KOMUTECH_L_DJ_XPLS", 16),
            entry("KOMUTECH_L_LZZZ_YGZZ", "KOMUTECH_L_DJ_XPLS", 16),
            entry("KOMUTECH_L_LZZZ_MHZZ", "KOMUTECH_L_DJ_XPLS", 16),
            entry("KOMUTECH_L_LZZZ_MZZ", "KOMUTECH_L_DJ_XPLS", 16)
        ),
        "leisi", List.of(
            entry("KOMUTECH_L_JCWP_儡肆通行柬", "KOMUTECH_L_DJ_SPLS", 64, "KOMUTECH_L_DJ_功德券", 16),
            entry("KOMUTECH_L_SW_KLN", "KOMUTECH_L_DJ_SPLS", 64, "KOMUTECH_L_KW_TXY", 4)
        )
    );

    /** main / categoryId */
    private final Map<Player, String> openPlayers = new ConcurrentHashMap<>();
    private final Map<Player, Map<Integer, ShopEntry>> playerSlotMaps = new ConcurrentHashMap<>();
    private Plugin plugin;

    @Override
    public void registerLifecycle(Plugin plugin) {
        this.plugin = plugin;
        KomutechMenuRouter.registerHandler(this);
    }

    @Override
    public boolean handles(InventoryView view) {
        if (view == null) {
            return false;
        }
        // 只用绑定库存 / 标题识别，禁止用 openPlayers：关闭后若仍靠 openPlayers 判定，
        // 会把玩家普通背包点击全部取消，造成「背包锁定」。
        if (KomutechMenuRouter.isHandlerFor(view.getTopInventory(), this)) {
            return true;
        }
        return isQiongHuaTitle(MenuGuiHelper.legacyTitle(view))
            || isQiongHuaTitle(view.getTitle());
    }

    private static boolean isQiongHuaTitle(String title) {
        if (title == null || title.isEmpty()) {
            return false;
        }
        if (MAIN_TITLE.equals(title)) {
            return true;
        }
        String plain = ChatColor.stripColor(title);
        if (plain.contains("琼华阁")) {
            return true;
        }
        // 分类页：标题为 §6§l✨ <id或中文名> ✨（旧版误要求含「琼华」，导致无法拦截）
        boolean styled = title.startsWith(TITLE_PREFIX) || plain.startsWith("✨ ");
        if (!styled) {
            // Paper 部分版本 getTitle 可能只剩纯文本 id
            return SHOPS.containsKey(plain.trim())
                || plain.contains("基础材料")
                || plain.contains("灵植种子")
                || plain.contains("儡肆");
        }
        if (SHOPS.keySet().stream().anyMatch(id -> title.contains(id) || plain.contains(id))) {
            return true;
        }
        return plain.contains("基础材料") || plain.contains("灵植种子") || plain.contains("儡肆");
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onButtonGroupClick".equals(functionName) && args[0] instanceof Player player) {
            openMain(player);
            return true;
        }
        if ("onUse".equals(functionName) && args.length > 0) {
            try {
                Player player = (Player) args[0].getClass().getMethod("getPlayer").invoke(args[0]);
                openMain(player);
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
        if (!handles(event.getView())) {
            return;
        }
        event.setCancelled(true);

        // 点玩家背包：只拦截，不处理购买
        if (event.getClickedInventory() != null
            && event.getClickedInventory().equals(event.getView().getBottomInventory())) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }

        String page = openPlayers.getOrDefault(player, resolvePageFromTitle(event.getView()));
        if ("main".equals(page) || isMainTitle(event.getView())) {
            String cat = MAIN_SLOTS.get(slot);
            if (cat != null) {
                openCategory(player, cat);
            } else if (slot == 49) {
                player.closeInventory();
            }
            return;
        }

        if (slot == 49) {
            openMain(player);
            return;
        }
        Map<Integer, ShopEntry> slotMap = playerSlotMaps.get(player);
        ShopEntry entry = slotMap == null ? null : slotMap.get(slot);
        if (entry != null) {
            purchase(player, entry, event.isShiftClick() ? 64 : 1);
        }
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        if (handles(event.getView())) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        // 切页会先 close 再 open：延迟清理；是否仍在琼华阁只用标题/绑定判断，避免 openPlayers 自锁
        if (plugin == null) {
            clearPlayer(player);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                clearPlayer(player);
                return;
            }
            InventoryView open = player.getOpenInventory();
            if (open != null && isOpenQiongHuaView(open)) {
                return;
            }
            clearPlayer(player);
        });
    }

    /** 关闭回调用：不依赖 openPlayers，防止清理逻辑自我卡死 */
    private boolean isOpenQiongHuaView(InventoryView view) {
        if (KomutechMenuRouter.isHandlerFor(view.getTopInventory(), this)) {
            return true;
        }
        return isQiongHuaTitle(MenuGuiHelper.legacyTitle(view))
            || isQiongHuaTitle(view.getTitle());
    }

    private void clearPlayer(Player player) {
        openPlayers.remove(player);
        playerSlotMaps.remove(player);
    }

    private void openMain(Player player) {
        Inventory inv = MenuGuiHelper.create(54, MAIN_TITLE);
        MenuGuiHelper.applyBorder(inv, BORDER);
        inv.setItem(4, MenuGuiHelper.item("PAINTING", "§6§l琼华阁", List.of("§f点击上方分类查看可兑换物品", "§f每个物品标有兑换所需灵石")));
        inv.setItem(10, MenuGuiHelper.item("EMERALD", "§6✦ 基础材料", List.of("§7基础材料", "", "§a点击查看")));
        inv.setItem(11, MenuGuiHelper.item("WHEAT_SEEDS", "§2✦ 灵植种子", List.of("§7各类灵植种子", "", "§a点击查看")));
        inv.setItem(12, MenuGuiHelper.item("SKULL_BANNER_PATTERN", "§8✦ 儡肆", List.of("§7傀儡交易", "", "§a点击查看")));
        inv.setItem(49, MenuGuiHelper.item("BARRIER", "§c关闭", List.of("§7关闭菜单")));
        player.openInventory(inv);
        // 必须在 openInventory 之后绑定：切页 close 会先 unbind
        openPlayers.put(player, "main");
        playerSlotMaps.put(player, Map.of());
        KomutechMenuRouter.bindInventory(inv, this);
    }

    private void openCategory(Player player, String categoryId) {
        List<ShopEntry> items = SHOPS.get(categoryId);
        if (items == null) {
            return;
        }
        String display = CATEGORY_NAMES.getOrDefault(categoryId, categoryId);
        String title = TITLE_PREFIX + display + TITLE_SUFFIX;
        Inventory inv = MenuGuiHelper.create(54, title);
        MenuGuiHelper.applyBorder(inv, BORDER);
        Map<Integer, ShopEntry> slotMap = new HashMap<>();
        int slot = 10;
        for (ShopEntry entry : items) {
            if (slot > 43) {
                break;
            }
            SlimefunItem sf = SlimefunItem.getById(entry.productId());
            if (sf != null) {
                ItemStack stack = sf.getItem().clone();
                ItemMeta meta = stack.getItemMeta();
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add("§f---§a§l点击购买§f---");
                lore.add("§a•需：" + entry.priceText());
                meta.setLore(lore);
                stack.setItemMeta(meta);
                inv.setItem(slot, stack);
                slotMap.put(slot, entry);
            }
            slot++;
            if ((slot - 9) % 9 == 0) {
                slot += 2;
            }
        }
        inv.setItem(49, MenuGuiHelper.item("ARROW", "§a返回", List.of("§7返回主菜单")));
        player.openInventory(inv);
        openPlayers.put(player, categoryId);
        playerSlotMaps.put(player, slotMap);
        KomutechMenuRouter.bindInventory(inv, this);
    }

    private void purchase(Player player, ShopEntry entry, int multiplier) {
        if (!hasEnough(player, entry, multiplier)) {
            KomutechSupport.send(player, "§c材料不足！");
            return;
        }
        SlimefunItem sf = SlimefunItem.getById(entry.productId());
        if (sf == null) {
            return;
        }
        removeItems(player, entry, multiplier);
        ItemStack product = sf.getItem().clone();
        product.setAmount(multiplier);
        KomutechSupport.giveOrDrop(player, product);
        KomutechSupport.send(player, "§a购买成功！");
    }

    private boolean hasEnough(Player player, ShopEntry entry, int multiplier) {
        for (PricePart part : entry.prices()) {
            int needed = part.amount() * multiplier;
            int total = 0;
            for (ItemStack stack : player.getInventory().getContents()) {
                if (stack == null || stack.getType() == Material.AIR) {
                    continue;
                }
                SlimefunItem sf = SlimefunItem.getByItem(stack);
                if (sf != null && part.id().equals(sf.getId())) {
                    total += stack.getAmount();
                }
            }
            if (total < needed) {
                return false;
            }
        }
        return true;
    }

    private void removeItems(Player player, ShopEntry entry, int multiplier) {
        for (PricePart part : entry.prices()) {
            int remain = part.amount() * multiplier;
            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length && remain > 0; i++) {
                ItemStack stack = contents[i];
                if (stack == null || stack.getType() == Material.AIR) {
                    continue;
                }
                SlimefunItem sf = SlimefunItem.getByItem(stack);
                if (sf == null || !part.id().equals(sf.getId())) {
                    continue;
                }
                int take = Math.min(stack.getAmount(), remain);
                stack.setAmount(stack.getAmount() - take);
                if (stack.getAmount() <= 0) {
                    player.getInventory().setItem(i, null);
                }
                remain -= take;
            }
        }
    }

    private static boolean isMainTitle(InventoryView view) {
        String legacy = MenuGuiHelper.legacyTitle(view);
        String plain = ChatColor.stripColor(legacy);
        return MAIN_TITLE.equals(legacy) || plain.contains("琼华阁");
    }

    private String resolvePageFromTitle(InventoryView view) {
        if (isMainTitle(view)) {
            return "main";
        }
        String title = MenuGuiHelper.legacyTitle(view);
        String plain = ChatColor.stripColor(title);
        for (Map.Entry<String, String> e : CATEGORY_NAMES.entrySet()) {
            String namePlain = ChatColor.stripColor(e.getValue());
            if (plain.contains(namePlain) || title.contains(e.getKey()) || plain.contains(e.getKey())) {
                return e.getKey();
            }
        }
        return "main";
    }

    private static ShopEntry entry(String productId, String priceId, int amount, String priceId2, int amount2) {
        return new ShopEntry(productId, List.of(new PricePart(priceId, amount), new PricePart(priceId2, amount2)));
    }

    private static ShopEntry entry(String productId, String priceId, int amount) {
        return new ShopEntry(productId, List.of(new PricePart(priceId, amount)));
    }

    private record PricePart(String id, int amount) {
    }

    private record ShopEntry(String productId, List<PricePart> prices) {
        String priceText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < prices.size(); i++) {
                if (i > 0) {
                    sb.append(" + ");
                }
                PricePart part = prices.get(i);
                sb.append(part.amount()).append(" ").append(resolveItemName(part.id()))
                    .append(" §7(").append(part.id()).append(")");
            }
            return sb.toString();
        }
    }

    private static String resolveItemName(String itemId) {
        SlimefunItem sf = SlimefunItem.getById(itemId);
        if (sf == null) {
            return itemId;
        }
        ItemStack stack = sf.getItem();
        if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
            return stack.getItemMeta().getDisplayName();
        }
        return itemId;
    }
}
