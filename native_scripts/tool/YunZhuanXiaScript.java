package tech.komutech.native_scripts.tool;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import tech.komutech.native_scripts.support.KomutechAsyncScheduler;
import tech.komutech.native_scripts.support.MainThread;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.KomutechConfigMerge;
import tech.komutech.native_scripts.support.KomutechJson;
import tech.komutech.native_scripts.support.KomutechPaths;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;
import tech.komutech.native_scripts.support.MenuGuiHelper;
import tech.komutech.native_scripts.support.PlayerAttributeStore;

public final class YunZhuanXiaScript implements NativeLifecycleScript, KomutechMenuHandler {

    private static final String TITLE = "§b云篆匣";
    private static final int SIZE = 9;
    private static final String SCROLL_PREFIX = "KOMUTECH_L_JZ_";
    private static final long COOLDOWN_MS = 1000L;

    private record OpenState(Map<String, Object> data, int wuxing) {
    }

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<Player, OpenState> openPlayers = new HashMap<>();
    private Plugin plugin;
    private Map<String, Object> scrollConfig;
    private long scrollConfigMtime = Long.MIN_VALUE;

    @Override
    public void registerLifecycle(Plugin plugin) {
        this.plugin = plugin;
        KomutechMenuRouter.registerHandler(this);
    }

    @Override
    public boolean handles(InventoryView view) {
        return MenuGuiHelper.titleStartsWith(view, TITLE);
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < COOLDOWN_MS) {
            double remain = Math.ceil((COOLDOWN_MS - (now - last)) / 100.0) / 10.0;
            KomutechSupport.actionBar(player, "§c云篆匣冷却中，请等待 §6" + remain + " §c秒");
            return null;
        }
        if (MenuGuiHelper.titleStartsWith(player.getOpenInventory(), TITLE)) {
            KomutechSupport.send(player, "§c云篆匣已打开，请先关闭当前界面再试！");
            return null;
        }
        cooldowns.put(player.getUniqueId(), now);
        final String playerName = player.getName();
        final Map<String, Object>[] loaded = new Map[1];
        KomutechAsyncScheduler.submit(
            () -> loaded[0] = load(playerName),
            ignored -> MainThread.run(plugin, () -> openInventory(player, loaded[0]))
        );
        return null;
    }

    private void openInventory(Player player, Map<String, Object> data) {
        int wuxing = unlockedSlots(player);
        Inventory inv = MenuGuiHelper.create(SIZE, TITLE);
        List<Object> scrolls = ensureScrollList(data);
        Map<String, Object> proficiencies = KomutechJson.asMap(data.get("熟练度记录"));
        for (int i = 0; i < SIZE; i++) {
            if (i < wuxing) {
                Object rawId = i < scrolls.size() ? scrolls.get(i) : null;
                if (rawId != null) {
                    String id = String.valueOf(rawId);
                    int prof = KomutechJson.getInt(proficiencies, skillName(id), 0);
                    ItemStack display = makeDisplayItem(id, prof);
                    if (display != null) {
                        inv.setItem(i, display);
                    }
                }
            } else {
                inv.setItem(i, MenuGuiHelper.item(Material.BARRIER, "§c未解锁", List.of("§7需要更高的悟性")));
            }
        }
        openPlayers.put(player, new OpenState(data, wuxing));
        player.openInventory(inv);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!MenuGuiHelper.titleStartsWith(event.getView(), TITLE)) {
            return;
        }
        event.setCancelled(true);
        OpenState state = openPlayers.get(player);
        if (state == null) {
            return;
        }
        var view = event.getView();
        var top = view.getTopInventory();
        var clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        int slot = event.getRawSlot();
        Map<String, Object> data = state.data();
        int wuxing = state.wuxing();
        List<Object> scrolls = ensureScrollList(data);
        String playerName = player.getName();

        if (clicked.equals(top) && slot >= 0 && slot < wuxing) {
            String id = slot < scrolls.size() && scrolls.get(slot) != null ? String.valueOf(scrolls.get(slot)) : null;
            if (id == null) {
                return;
            }
            SlimefunItem sf = SlimefunItem.getById(id);
            if (sf == null) {
                return;
            }
            var leftover = player.getInventory().addItem(sf.getItem().clone());
            if (!leftover.isEmpty()) {
                KomutechSupport.send(player, "§c背包已满，无法取出");
                return;
            }
            scrolls.set(slot, null);
            data.put("卷轴数据", scrolls);
            saveAsync(playerName, data);
            top.setItem(slot, null);
            return;
        }

        if (clicked.equals(player.getInventory())) {
            ItemStack current = event.getCurrentItem();
            if (current == null || current.getType().isAir()) {
                return;
            }
            SlimefunItem sf = SlimefunItem.getByItem(current);
            if (sf == null || !sf.getId().toUpperCase(java.util.Locale.ROOT).startsWith(SCROLL_PREFIX)) {
                KomutechSupport.send(player, "§c只能存入卷轴物品");
                return;
            }
            String id = sf.getId();
            for (int i = 0; i < wuxing; i++) {
                if (id.equals(String.valueOf(scrolls.get(i)))) {
                    KomutechSupport.send(player, "§c此卷轴已经存入了");
                    return;
                }
            }
            int emptySlot = -1;
            for (int i = 0; i < wuxing; i++) {
                if (scrolls.get(i) == null) {
                    emptySlot = i;
                    break;
                }
            }
            if (emptySlot == -1) {
                KomutechSupport.send(player, "§c可用云篆匣已满（当前悟性：" + wuxing + "）");
                return;
            }
            if (current.getAmount() > 1) {
                current.setAmount(current.getAmount() - 1);
            } else {
                player.getInventory().setItem(event.getSlot(), null);
            }
            scrolls.set(emptySlot, id);
            Map<String, Object> proficiencies = new HashMap<>(KomutechJson.asMap(data.get("熟练度记录")));
            String skill = skillName(id);
            proficiencies.putIfAbsent(skill, 0);
            int prof = KomutechJson.getInt(proficiencies, skill, 0);
            data.put("卷轴数据", scrolls);
            data.put("熟练度记录", proficiencies);
            saveAsync(playerName, data);
            ItemStack display = makeDisplayItem(id, prof);
            if (display != null) {
                top.setItem(emptySlot, display);
            }
        }
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
            && openPlayers.containsKey(player)
            && MenuGuiHelper.titleStartsWith(event.getView(), TITLE)) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!MenuGuiHelper.titleStartsWith(event.getView(), TITLE)) {
            return;
        }
        if (player.getOpenInventory().getTopInventory() == event.getInventory()) {
            return;
        }
        openPlayers.remove(player);
    }

    private int unlockedSlots(Player player) {
        Map<String, Object> attr = PlayerAttributeStore.load(player.getName());
        double wuxing = attr == null ? 1 : PlayerAttributeStore.getDouble(attr, "悟性", 1);
        return Math.max(1, Math.min(SIZE, (int) Math.floor(wuxing)));
    }

    private static List<Object> ensureScrollList(Map<String, Object> data) {
        List<Object> scrolls = new ArrayList<>(KomutechJson.asList(data.computeIfAbsent("卷轴数据", k -> emptyScrolls())));
        while (scrolls.size() < SIZE) {
            scrolls.add(null);
        }
        return scrolls;
    }

    private static List<Object> emptyScrolls() {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            list.add(null);
        }
        return list;
    }

    private Map<String, Object> load(String name) {
        try {
            var path = KomutechPaths.yunZhuanXia().resolve("[" + name + "]云篆匣.json");
            if (!Files.exists(path)) {
                Map<String, Object> data = new HashMap<>();
                data.put("卷轴数据", emptyScrolls());
                data.put("熟练度记录", new HashMap<>());
                return data;
            }
            Map<String, Object> data = KomutechJson.asMap(KomutechJson.parse(Files.readString(path, StandardCharsets.UTF_8)));
            ensureScrollList(data);
            return data;
        } catch (Exception ignored) {
            Map<String, Object> data = new HashMap<>();
            data.put("卷轴数据", emptyScrolls());
            data.put("熟练度记录", new HashMap<>());
            return data;
        }
    }

    private void saveAsync(String name, Map<String, Object> data) {
        KomutechAsyncScheduler.submit(
            () -> save(name, data),
            ignored -> {}
        );
    }

    private void save(String name, Map<String, Object> data) {
        try {
            Files.createDirectories(KomutechPaths.yunZhuanXia());
            Files.writeString(
                KomutechPaths.yunZhuanXia().resolve("[" + name + "]云篆匣.json"),
                KomutechJson.stringify(data),
                StandardCharsets.UTF_8
            );
        } catch (Exception ignored) {
        }
    }

    private ItemStack makeDisplayItem(String id, int prof) {
        SlimefunItem sf = SlimefunItem.getById(id);
        if (sf == null) {
            return null;
        }
        ItemStack item = sf.getItem().clone();
        ItemMeta meta = item.getItemMeta();
        List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
        lore.removeIf(line -> line.startsWith("§7熟练度："));
        int max = getMaxProficiency(skillName(id));
        lore.add("§7熟练度：§f" + prof + " §7/ §f" + max);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private int getMaxProficiency(String skillName) {
        ensureScrollConfig();
        if (scrollConfig == null || skillName == null || skillName.isBlank()) {
            return 0;
        }
        String key = ScrollCombatEngine.normalizeSkillId(skillName);
        Map<String, Object> section = KomutechJson.asMap(scrollConfig.get(key));
        if (section.isEmpty()) {
            scrollConfig = null;
            scrollConfigMtime = Long.MIN_VALUE;
            ensureScrollConfig();
            section = KomutechJson.asMap(scrollConfig.get(key));
        }
        return KomutechJson.getInt(section, "熟练度上限", 0);
    }

    private void ensureScrollConfig() {
        var path = KomutechPaths.scrollConfig();
        long mtime = -1L;
        try {
            if (Files.exists(path)) {
                mtime = Files.getLastModifiedTime(path).toMillis();
            }
        } catch (Exception ignored) {
        }
        if (scrollConfig != null && mtime == scrollConfigMtime) {
            return;
        }
        scrollConfig = KomutechConfigMerge.loadMerged(path, "addon_configs/Komutech/卷轴属性.json");
        try {
            if (Files.exists(path)) {
                mtime = Files.getLastModifiedTime(path).toMillis();
            }
        } catch (Exception ignored) {
        }
        scrollConfigMtime = mtime;
    }

    private static String skillName(String fullId) {
        return ScrollCombatEngine.normalizeSkillId(fullId);
    }
}
