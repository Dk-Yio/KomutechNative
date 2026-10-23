package tech.komutech.native_scripts.menu;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechChatInput;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.MenuGuiHelper;

public final class ConvenienceRecipeMenuScript implements NativeLifecycleScript, KomutechMenuHandler {

    private static final String MAIN_TITLE = "§6口木科技便捷配方";
    private static final String SUB_PREFIX = "§6便捷配方 - ";
    private static final int ITEMS_PER_PAGE = 28;
    private static final int[] BORDER = {0, 1, 2, 3, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47, 48, 50, 51, 52};
    private static final int[] OUT_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final NamespacedKey MACHINE_KEY = new NamespacedKey("komutech_l_bxpf", "machine");
    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fff\\u3400-\\u4dbf]+");

    private record Category(String id, String name, Material icon, List<String> machines, List<ManualEntry> manual) {
    }

    private record ManualEntry(String id, String machine) {
    }

    private record RecipeEntry(String out, String machine) {
    }

    private record MenuState(String catId, int page) {
    }

    private static final List<Category> CATEGORIES = List.of(
        new Category("jcwp", "§a基础物品", Material.CRAFTING_TABLE, List.of("KOMUTECH_L_JQ_下品一芥乾坤"), List.of(
            new ManualEntry("KOMUTECH_L_LZZZ_YGZZ", "KOMUTECH_L_JQ_下品一芥乾坤"),
            new ManualEntry("KOMUTECH_L_DJ_XPLJ", "KOMUTECH_L_JQ_下品一芥乾坤"),
            new ManualEntry("KOMUTECH_L_DJ_ZPLJ", "KOMUTECH_L_JQ_中品一芥乾坤")
        )),
        new Category("fz", "§b法则", Material.ENCHANTED_BOOK, List.of("KOMUTECH_L_PF_法则合成演示"), List.of()),
        new Category("lz", "§d灵杖", Material.BLAZE_ROD, List.of("KOMUTECH_L_JQ_LNLQF"), List.of()),
        new Category("jz", "§5卷轴", Material.BOOK, List.of("KOMUTECH_L_JQ_JZZXT"), List.of()),
        new Category("fl", "§e符箓", Material.PAPER, List.of("KOMUTECH_L_JQ_FLHZT"), List.of()),
        new Category("zj", "§5終極", Material.NETHER_STAR, List.of("KOMUTECH_L_PF_终极合成演示"), List.of())
    );

    private final Map<String, List<RecipeEntry>> cache = new HashMap<>();
    private final Map<Player, MenuState> openPlayers = new HashMap<>();
    private boolean cacheReady;
    private Plugin plugin;

    private void ensureCache() {
        if (cacheReady) {
            return;
        }
        for (Category category : CATEGORIES) {
            List<RecipeEntry> entries = new ArrayList<>();
            if (category.machines() != null) {
                for (String machineId : category.machines()) {
                    for (String outId : parseOutputs(machineId)) {
                        entries.add(new RecipeEntry(outId, machineId));
                    }
                }
            }
            if (category.manual() != null) {
                for (ManualEntry manual : category.manual()) {
                    entries.add(new RecipeEntry(manual.id(), manual.machine()));
                }
            }
            cache.put(category.id(), entries);
        }
        cacheReady = true;
    }

    @Override
    public void registerLifecycle(Plugin plugin) {
        this.plugin = plugin;
        KomutechMenuRouter.registerHandler(this);
    }

    @Override
    public boolean handles(InventoryView view) {
        String title = view.getTitle();
        return MAIN_TITLE.equals(title) || title.startsWith(SUB_PREFIX);
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onButtonGroupClick".equals(functionName) && args[0] instanceof Player player) {
            openMain(player);
            return true;
        }
        if ("onUse".equals(functionName)) {
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
        String title = event.getView().getTitle();
        if (!MAIN_TITLE.equals(title) && !title.startsWith(SUB_PREFIX)) {
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        int slot = event.getSlot();
        if (MAIN_TITLE.equals(title)) {
            if (slot == 53) {
                search(player);
                return;
            }
            if (slot == 49) {
                player.closeInventory();
                return;
            }
            for (int i = 0; i < CATEGORIES.size(); i++) {
                if (slot == 10 + i) {
                    openCategory(player, CATEGORIES.get(i).id(), 1);
                    return;
                }
            }
            return;
        }
        MenuState state = openPlayers.get(player);
        if (slot == 53) {
            search(player);
        } else if (slot == 49) {
            openMain(player);
        } else if (slot == 48 && state != null && state.page() > 1) {
            openCategory(player, state.catId(), state.page() - 1);
        } else if (slot == 50 && state != null) {
            int total = totalPages(state.catId());
            if (state.page() < total) {
                openCategory(player, state.catId(), state.page() + 1);
            }
        } else if (clicked.hasItemMeta()) {
            String machine = clicked.getItemMeta().getPersistentDataContainer().get(MACHINE_KEY, PersistentDataType.STRING);
            if (machine != null) {
                openGuide(player, machine);
            }
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            openPlayers.remove(player);
        }
    }

    private void openMain(Player player) {
        ensureCache();
        Inventory inv = MenuGuiHelper.create(54, MAIN_TITLE);
        MenuGuiHelper.applyBorder(inv, BORDER);
        inv.setItem(4, MenuGuiHelper.item(Material.PAINTING, "§6口木科技便捷配方", List.of("§7点击分类查看产物")));
        for (int i = 0; i < CATEGORIES.size(); i++) {
            Category cat = CATEGORIES.get(i);
            inv.setItem(10 + i, MenuGuiHelper.item(cat.icon(), cat.name(), List.of("§7点击查看产物")));
        }
        inv.setItem(53, MenuGuiHelper.item(Material.COMPASS, "§e🔍 搜索", List.of("§7点击后在聊天栏输入物品名")));
        inv.setItem(49, MenuGuiHelper.item(Material.BARRIER, "§c关闭", List.of()));
        player.openInventory(inv);
        openPlayers.put(player, new MenuState(null, 0));
    }

    private void openCategory(Player player, String catId, int page) {
        ensureCache();
        List<RecipeEntry> entries = cache.getOrDefault(catId, List.of());
        if (entries.isEmpty()) {
            return;
        }
        Category cat = CATEGORIES.stream().filter(c -> c.id().equals(catId)).findFirst().orElse(null);
        if (cat == null) {
            return;
        }
        int total = Math.max(1, (int) Math.ceil(entries.size() / (double) ITEMS_PER_PAGE));
        page = Math.max(1, Math.min(page, total));
        int start = (page - 1) * ITEMS_PER_PAGE;
        Inventory inv = MenuGuiHelper.create(54, SUB_PREFIX + cat.name());
        MenuGuiHelper.applyBorder(inv, BORDER);
        inv.setItem(4, MenuGuiHelper.item(Material.PAPER, "§6" + cat.name() + " - 产物列表",
            List.of("§7第 " + page + "/" + total + " 页")));
        for (int i = 0; i < ITEMS_PER_PAGE && start + i < entries.size(); i++) {
            RecipeEntry entry = entries.get(start + i);
            SlimefunItem sf = SlimefunItem.getById(entry.out());
            if (sf == null) {
                continue;
            }
            ItemStack display = sf.getItem().clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            SlimefunItem machine = SlimefunItem.getById(entry.machine());
            String machineName = machine != null && machine.getItem().getItemMeta().hasDisplayName()
                ? machine.getItem().getItemMeta().getDisplayName() : entry.machine();
            lore.add("§7产出机器：" + machineName);
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(MACHINE_KEY, PersistentDataType.STRING, entry.machine());
            display.setItemMeta(meta);
            inv.setItem(OUT_SLOTS[i], display);
        }
        if (page > 1) {
            inv.setItem(48, MenuGuiHelper.item(Material.ARROW, "§a上一页", List.of()));
        }
        inv.setItem(49, MenuGuiHelper.item(Material.ARROW, "§a返回", List.of("§7返回主菜单")));
        if (page < total) {
            inv.setItem(50, MenuGuiHelper.item(Material.ARROW, "§a下一页", List.of()));
        }
        inv.setItem(53, MenuGuiHelper.item(Material.COMPASS, "§e🔍 搜索", List.of()));
        player.openInventory(inv);
        openPlayers.put(player, new MenuState(catId, page));
    }

    private void search(Player player) {
        ensureCache();
        KomutechSupport.send(player, "§e请在聊天栏输入要搜索的物品名称（输入 cancel 取消）:");
        KomutechChatInput.waitFor(player, input -> {
            if (input == null || "cancel".equalsIgnoreCase(input)) {
                KomutechSupport.send(player, "§c已取消搜索。");
                return;
            }
            String kw = extractChinese(input);
            if (kw.isBlank()) {
                KomutechSupport.send(player, "§c请输入有效的中文关键词。");
                return;
            }
            for (Category cat : CATEGORIES) {
                for (RecipeEntry entry : cache.getOrDefault(cat.id(), List.of())) {
                    SlimefunItem sf = SlimefunItem.getById(entry.out());
                    if (sf == null) {
                        continue;
                    }
                    String name = sf.getItem().getItemMeta().hasDisplayName()
                        ? sf.getItem().getItemMeta().getDisplayName() : entry.out();
                    if (extractChinese(name).contains(kw) || entry.out().toLowerCase().contains(kw.toLowerCase())) {
                        KomutechSupport.send(player, "§a找到匹配项：" + name + "，位于分类：" + cat.name());
                        openCategory(player, cat.id(), 1);
                        return;
                    }
                }
            }
            KomutechSupport.send(player, "§c未找到包含 \"" + kw + "\" 的物品。");
        });
    }

    private void openGuide(Player player, String machineId) {
        SlimefunItem machine = SlimefunItem.getById(machineId);
        if (machine == null) {
            return;
        }
        PlayerProfile.find(player).ifPresent(profile -> SlimefunGuide.displayItem(profile, machine, true));
    }

    private int totalPages(String catId) {
        int size = cache.getOrDefault(catId, List.of()).size();
        return Math.max(1, (int) Math.ceil(size / (double) ITEMS_PER_PAGE));
    }

    private static Set<String> parseOutputs(String machineId) {
        Set<String> outs = new HashSet<>();
        SlimefunItem machine = SlimefunItem.getById(machineId);
        if (machine == null) {
            return outs;
        }
        try {
            Object recipes = machine.getClass().getMethod("getMachineRecipes").invoke(machine);
            if (recipes instanceof Iterable<?> iterable) {
                for (Object recipe : iterable) {
                    Object output = recipe.getClass().getMethod("getOutput").invoke(recipe);
                    if (output instanceof ItemStack[] stacks) {
                        for (ItemStack out : stacks) {
                            SlimefunItem sf = SlimefunItem.getByItem(out);
                            if (sf != null) {
                                outs.add(sf.getId());
                            }
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return outs;
    }

    private static String extractChinese(String input) {
        if (input == null) {
            return "";
        }
        var matcher = CHINESE.matcher(input);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            builder.append(matcher.group());
        }
        return builder.toString();
    }
}
