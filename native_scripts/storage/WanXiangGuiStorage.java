package tech.komutech.native_scripts.storage;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import tech.komutech.native_scripts.support.KomutechAddonConfig;
import tech.komutech.native_scripts.support.KomutechJson;
import tech.komutech.native_scripts.support.KomutechPaths;

public final class WanXiangGuiStorage {

    public static Path dataDir() {
        return KomutechPaths.wanXiangGui();
    }

    public static Path filePath(String playerName, String storageName) {
        return dataDir().resolve(fileName(playerName, storageName));
    }
    public static final String STORAGE_ID = "KOMUTECH_L_ZJ_萬象匱";
    public static final int SLOTS_PER_PAGE = 45;
    public static final int INVENTORY_SIZE = 54;

    private static final NamespacedKey STORAGE_NAME_KEY = new NamespacedKey(
        "komutechnative", "wx_storage_name");

    private static final Collator COLLATOR = Collator.getInstance(Locale.CHINA);

    private WanXiangGuiStorage() {
    }

    private static final String STORAGE_LORE_PREFIX = "§7┃ 存储标识: §f";
    private static final String[] STORAGE_LORE_PREFIXES = {
        STORAGE_LORE_PREFIX,
        "§7┃ 存儲標識: §f",
        "§7| 存储标识: §f",
        "§7| 存儲標識: §f"
    };
    private static final String[] STORAGE_MARKERS = {
        "存储标识:", "存儲標識:", "存储標識:", "存儲标识:"
    };

    public static String getStorageName(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        String pdcName = meta.getPersistentDataContainer().get(STORAGE_NAME_KEY, PersistentDataType.STRING);
        if (isValidStorageName(pdcName)) {
            return pdcName;
        }
        List<String> lore = meta.getLore();
        if (lore == null) {
            return null;
        }
        for (String line : lore) {
            for (String prefix : STORAGE_LORE_PREFIXES) {
                if (line.startsWith(prefix)) {
                    String name = line.substring(prefix.length()).trim();
                    if (isValidStorageName(name)) {
                        return name;
                    }
                }
            }
            String name = extractStorageName(ChatColor.stripColor(line));
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    public static void setStorageName(ItemStack item, String name) {
        ItemMeta meta = item.getItemMeta();
        List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
        lore.removeIf(line -> {
            if (line.startsWith(STORAGE_LORE_PREFIX)) {
                return true;
            }
            return extractStorageName(ChatColor.stripColor(line)) != null;
        });
        lore.add(STORAGE_LORE_PREFIX + name);
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(STORAGE_NAME_KEY, PersistentDataType.STRING, name);
        item.setItemMeta(meta);
    }

    private static String extractStorageName(String plain) {
        if (plain == null) {
            return null;
        }
        plain = plain.trim().replaceFirst("^[|┃│\\s]+", "");
        for (String marker : STORAGE_MARKERS) {
            int markerIndex = plain.indexOf(marker);
            if (markerIndex >= 0) {
                String name = plain.substring(markerIndex + marker.length()).trim();
                if (isValidStorageName(name)) {
                    return name;
                }
            }
        }
        return null;
    }

    public static String fileName(String playerName, String storageName) {
        if (storageName == null) {
            return null;
        }
        return "[" + playerName + "]" + storageName.replaceAll("[\\\\/:*?\"<>|]", "_") + ".json";
    }

    public static String resolveStorageName(String playerName, String storageName) {
        if (storageName == null) {
            return null;
        }
        if (storageFileExists(playerName, storageName)) {
            return storageName;
        }
        for (String listed : listStorages(playerName)) {
            if (storageNameEquals(storageName, listed)) {
                return listed;
            }
        }
        return storageName;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(String playerName, String storageName) {
        storageName = resolveStorageName(playerName, storageName);
        Path path = filePath(playerName, storageName);
        if (!Files.exists(path)) {
            return emptyData();
        }
        try {
            Map<String, Object> data = KomutechJson.asMap(KomutechJson.parse(Files.readString(path, StandardCharsets.UTF_8)));
            if (!data.containsKey("meta")) {
                data.put("meta", Map.of("page", "1", "mode", "normal", "kw", ""));
            }
            if (!data.containsKey("pages")) {
                data.put("pages", Map.of("1", emptyPage()));
            }
            normalizePages(data);
            return data;
        } catch (IOException | RuntimeException exception) {
            return emptyData();
        }
    }

    public static void write(String playerName, String storageName, Map<String, Object> data) {
        try {
            storageName = resolveStorageName(playerName, storageName);
            Files.createDirectories(dataDir());
            Files.writeString(filePath(playerName, storageName), KomutechJson.stringify(data), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static boolean storageNameEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right) || normalizeStorageKey(left).equals(normalizeStorageKey(right));
    }

    private static String normalizeStorageKey(String name) {
        StringBuilder normalized = new StringBuilder(name.trim().length());
        for (char ch : name.trim().toCharArray()) {
            normalized.append(switch (ch) {
                case '无', '無' -> '无';
                case '双', '雙' -> '双';
                default -> ch;
            });
        }
        return normalized.toString();
    }

    public static Map<String, Object> emptyData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pages", Map.of("1", emptyPage()));
        data.put("meta", Map.of("page", "1", "mode", "normal", "kw", ""));
        return data;
    }

    @SuppressWarnings("unchecked")
    private static void normalizePages(Map<String, Object> data) {
        Map<String, Object> pages = KomutechJson.asMap(data.get("pages"));
        for (String key : new ArrayList<>(pages.keySet())) {
            List<Object> page = KomutechJson.asList(pages.get(key));
            if (page.size() > SLOTS_PER_PAGE) {
                page = new ArrayList<>(page.subList(0, SLOTS_PER_PAGE));
            }
            while (page.size() < SLOTS_PER_PAGE) {
                page.add(null);
            }
            pages.put(key, page);
        }
        data.put("pages", pages);
    }

    public static String normalizedEntry(Object entry) {
        if (entry == null) {
            return null;
        }
        if (entry instanceof String str) {
            return str;
        }
        return KomutechJson.stringify(entry);
    }

    public static List<Object> emptyPage() {
        List<Object> page = new ArrayList<>();
        for (int i = 0; i < SLOTS_PER_PAGE; i++) {
            page.add(null);
        }
        return page;
    }

    @SuppressWarnings("unchecked")
    private static void setPageEntry(Map<String, Object> data, String pageKey, int slotIndex, Object value) {
        Map<String, Object> pages = KomutechJson.asMap(data.get("pages"));
        List<Object> page = KomutechJson.asList(pages.get(pageKey));
        if (!pages.containsKey(pageKey)) {
            page = emptyPage();
        }
        while (page.size() <= slotIndex) {
            page.add(null);
        }
        page.set(slotIndex, value);
        pages.put(pageKey, page);
        data.put("pages", pages);
    }

    public static String serialize(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        SlimefunItem sf = SlimefunItem.getByItem(item);
        if (sf != null) {
            return KomutechJson.stringify(Map.of("type", "slimefun", "id", sf.getId()));
        }
        return KomutechJson.stringify(Map.of("type", "vanilla", "material", item.getType().name()));
    }

    public static ItemStack deserialize(Object raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> data = KomutechJson.asMap(raw instanceof String str ? KomutechJson.parse(str) : raw);
        String type = KomutechJson.getString(data, "type", "");
        if ("slimefun".equals(type)) {
            SlimefunItem sf = SlimefunItem.getById(KomutechJson.getString(data, "id", ""));
            return sf == null ? null : sf.getItem().clone();
        }
        Material material = Material.matchMaterial(KomutechJson.getString(data, "material", "AIR"));
        return material == null || material.isAir() ? null : new ItemStack(material, 1);
    }

    public static ReadResult readForItem(ItemStack item, String playerName) {
        String name = getStorageName(item);
        if (name == null) {
            Map<String, Object> data = emptyData();
            return new ReadResult(data, true);
        }
        return new ReadResult(read(playerName, name), false);
    }

    public static void writeForItem(ItemStack item, String playerName, Map<String, Object> data) {
        String name = getStorageName(item);
        if (name != null) {
            write(playerName, name, data);
        }
    }

    public static boolean isStorageItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        SlimefunItem sf = SlimefunItem.getByItem(item);
        if (sf != null && STORAGE_ID.equals(sf.getId())) {
            return true;
        }
        if (!item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) {
            return false;
        }
        String plain = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        return plain.contains("萬象匱") || plain.contains("萬象匣")
            || plain.contains("万象匣") || plain.contains("万象匱");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> search(Map<String, Object> data, String keyword) {
        String kw = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT);
        Map<String, Object> pages = KomutechJson.asMap(data.get("pages"));
        record Entry(String serialized, String name) {
        }
        List<Entry> matched = new ArrayList<>();
        for (Object pageObj : pages.values()) {
            for (Object ser : KomutechJson.asList(pageObj)) {
                if (ser == null) {
                    continue;
                }
                ItemStack item = deserialize(ser);
                if (item == null) {
                    continue;
                }
                if (searchText(item).contains(kw)) {
                    String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                        ? item.getItemMeta().getDisplayName().replaceAll("§.", "")
                        : item.getType().name();
                    matched.add(new Entry(String.valueOf(ser), name));
                }
            }
        }
        matched.sort(Comparator.comparing(Entry::name, COLLATOR));
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> newPages = new LinkedHashMap<>();
        int idx = 0;
        for (Entry entry : matched) {
            int pageNum = idx / 45 + 1;
            List<Object> page = (List<Object>) newPages.computeIfAbsent(String.valueOf(pageNum), k -> emptyPage());
            page.set(idx % 45, entry.serialized());
            idx++;
        }
        result.put("pages", newPages.isEmpty() ? Map.of("1", emptyPage()) : newPages);
        return result;
    }

    private static String searchText(ItemStack item) {
        List<String> parts = new ArrayList<>();
        parts.add(item.getType().name().toLowerCase(Locale.ROOT));
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            parts.add(item.getItemMeta().getDisplayName().replaceAll("§.", "").toLowerCase(Locale.ROOT));
        }
        SlimefunItem sf = SlimefunItem.getByItem(item);
        if (sf != null) {
            parts.add(sf.getId().toLowerCase(Locale.ROOT));
        }
        return String.join(" ", parts);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> sortAll(Map<String, Object> data) {
        Map<String, Object> pages = KomutechJson.asMap(data.get("pages"));
        record Entry(String serialized, String name) {
        }
        List<Entry> items = new ArrayList<>();
        for (Object pageObj : pages.values()) {
            for (Object ser : KomutechJson.asList(pageObj)) {
                if (ser == null) {
                    continue;
                }
                ItemStack item = deserialize(ser);
                if (item == null) {
                    continue;
                }
                String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                    ? item.getItemMeta().getDisplayName().replaceAll("§.", "")
                    : item.getType().name();
                items.add(new Entry(String.valueOf(ser), name));
            }
        }
        items.sort(Comparator.comparing(Entry::name, COLLATOR));
        Map<String, Object> newPages = new LinkedHashMap<>();
        int idx = 0;
        for (Entry entry : items) {
            int pageNum = idx / 45 + 1;
            List<Object> page = (List<Object>) newPages.computeIfAbsent(String.valueOf(pageNum), k -> emptyPage());
            page.set(idx % 45, entry.serialized());
            idx++;
        }
        if (newPages.isEmpty()) {
            newPages.put("1", emptyPage());
        }
        data.put("pages", newPages);
        data.put("meta", Map.of("page", "1", "mode", "normal", "kw", ""));
        return data;
    }

    public record ReadResult(Map<String, Object> data, boolean unnamed) {
    }

    public record SlotRef(String page, int slot) {
    }

    public record StoreResult(boolean success, String reason, String page, int slot) {
        public static StoreResult fail(String reason) {
            return new StoreResult(false, reason, null, -1);
        }

        public static StoreResult ok(String page, int slot) {
            return new StoreResult(true, null, page, slot);
        }
    }

    public static List<String> defaultBlacklist() {
        return List.of(STORAGE_ID, "KOMUTECH_L_ZJ_萬衍儀", "KOMUTECH_L_ZJ_無");
    }

    public static List<String> blacklist() {
        List<String> configured = KomutechAddonConfig.getStringList("L_ZJ_WXG_BAN");
        return configured.isEmpty() ? defaultBlacklist() : configured;
    }

    public static int storageLimit() {
        return KomutechAddonConfig.getInt("L_ZJ_WXG_CCmax", 5);
    }

    public static String clearPassword() {
        return KomutechAddonConfig.getString("L_ZJ_WXG_MM", "0108");
    }

    public static boolean isValidStorageName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.matches(".*[\\\\/:*?\"<>|].*")) {
            return false;
        }
        return !"无效".equals(trimmed) && !"未命名".equals(trimmed) && !"无".equals(trimmed);
    }

    public static int countPlayerStorages(String playerName) {
        return listStorages(playerName).size();
    }

    public static boolean storageFileExists(String playerName, String storageName) {
        return Files.exists(filePath(playerName, storageName));
    }

    public static List<String> listStorages(String playerName) {
        List<String> names = new ArrayList<>();
        try {
            Path dir = dataDir();
            if (!Files.isDirectory(dir)) {
                return names;
            }
            String prefix = "[" + playerName + "]";
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".json"))
                    .map(name -> name.substring(prefix.length(), name.length() - 5))
                    .forEach(names::add);
            }
        } catch (IOException ignored) {
        }
        return names;
    }

    public static List<String> listAllPlayers() {
        TreeSet<String> players = new TreeSet<>();
        try {
            Path dir = dataDir();
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("[") && name.contains("]") && name.endsWith(".json"))
                    .map(name -> name.substring(1, name.indexOf(']')))
                    .forEach(players::add);
            }
        } catch (IOException ignored) {
        }
        return new ArrayList<>(players);
    }

    public static boolean renameStorage(String playerName, String oldName, String newName) {
        if (!isValidStorageName(newName) || storageFileExists(playerName, newName)) {
            return false;
        }
        Path source = filePath(playerName, oldName);
        Path target = filePath(playerName, newName);
        try {
            Files.move(source, target);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    public static void deleteStorage(String playerName, String storageName) {
        try {
            Files.deleteIfExists(filePath(playerName, storageName));
        } catch (IOException ignored) {
        }
    }

    public static void deletePlayerStorages(String playerName) {
        for (String storage : listStorages(playerName)) {
            deleteStorage(playerName, storage);
        }
    }

    public static void deleteAllStorages() {
        try {
            Path dir = dataDir();
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
            }
        } catch (IOException ignored) {
        }
    }

    public static boolean isBlacklisted(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        SlimefunItem sf = SlimefunItem.getByItem(item);
        if (sf == null) {
            return false;
        }
        return blacklist().contains(sf.getId());
    }

    @SuppressWarnings("unchecked")
    public static boolean exists(Map<String, Object> data, ItemStack item) {
        String serialized = serialize(item);
        if (serialized == null) {
            return false;
        }
        for (Object pageObj : KomutechJson.asMap(data.get("pages")).values()) {
            for (Object entry : KomutechJson.asList(pageObj)) {
                if (entry != null && serialized.equals(normalizedEntry(entry))) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static SlotRef findEmptySlot(Map<String, Object> data) {
        Map<String, Object> pages = KomutechJson.asMap(data.get("pages"));
        List<Integer> pageNumbers = new ArrayList<>();
        for (String key : pages.keySet()) {
            try {
                pageNumbers.add(Integer.parseInt(key));
            } catch (NumberFormatException ignored) {
            }
        }
        pageNumbers.sort(Integer::compareTo);
        for (int pageNum : pageNumbers) {
            List<Object> page = KomutechJson.asList(pages.get(String.valueOf(pageNum)));
            for (int i = 0; i < Math.min(45, page.size()); i++) {
                if (page.get(i) == null) {
                    return new SlotRef(String.valueOf(pageNum), i);
                }
            }
        }
        String newPage = String.valueOf(pageNumbers.isEmpty() ? 1 : pageNumbers.get(pageNumbers.size() - 1) + 1);
        pages.put(newPage, emptyPage());
        data.put("pages", pages);
        return new SlotRef(newPage, 0);
    }

    @SuppressWarnings("unchecked")
    public static StoreResult store(Map<String, Object> data, ItemStack item) {
        if (isBlacklisted(item)) {
            return StoreResult.fail("禁止存入");
        }
        if (exists(data, item)) {
            return StoreResult.fail("物品已存在");
        }
        String serialized = serialize(item);
        if (serialized == null) {
            return StoreResult.fail("无法序列化");
        }
        SlotRef slot = findEmptySlot(data);
        setPageEntry(data, slot.page(), slot.slot(), serialized);
        return StoreResult.ok(slot.page(), slot.slot());
    }

    @SuppressWarnings("unchecked")
    public static void removeSerialized(Map<String, Object> data, Object serialized) {
        if (serialized == null) {
            return;
        }
        String target = normalizedEntry(serialized);
        Map<String, Object> pages = KomutechJson.asMap(data.get("pages"));
        for (String pageKey : new ArrayList<>(pages.keySet())) {
            List<Object> page = KomutechJson.asList(pages.get(pageKey));
            for (int i = 0; i < page.size(); i++) {
                Object entry = page.get(i);
                if (entry != null && target.equals(normalizedEntry(entry))) {
                    page.set(i, null);
                    pages.put(pageKey, page);
                    data.put("pages", pages);
                    return;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static String metaPage(Map<String, Object> data) {
        return KomutechJson.getString(KomutechJson.asMap(data.get("meta")), "page", "1");
    }

    @SuppressWarnings("unchecked")
    public static String metaMode(Map<String, Object> data) {
        return KomutechJson.getString(KomutechJson.asMap(data.get("meta")), "mode", "normal");
    }

    @SuppressWarnings("unchecked")
    public static String metaKeyword(Map<String, Object> data) {
        return KomutechJson.getString(KomutechJson.asMap(data.get("meta")), "kw", "");
    }

    @SuppressWarnings("unchecked")
    public static void setMeta(Map<String, Object> data, String page, String mode, String keyword) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("page", page == null ? "1" : page);
        meta.put("mode", mode == null ? "normal" : mode);
        meta.put("kw", keyword == null ? "" : keyword);
        data.put("meta", meta);
    }

    @SuppressWarnings("unchecked")
    public static int countStoredItems(Map<String, Object> data) {
        int count = 0;
        for (Object pageObj : KomutechJson.asMap(data.get("pages")).values()) {
            for (Object entry : KomutechJson.asList(pageObj)) {
                if (entry != null) {
                    count++;
                }
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    public static LoadedView loadView(String playerName, String storageName) {
        Map<String, Object> data = read(playerName, storageName);
        String page = metaPage(data);
        String mode = metaMode(data);
        String keyword = metaKeyword(data);
        Map<String, Object> searchData = null;
        if ("search".equals(mode) && keyword != null && !keyword.isEmpty()) {
            searchData = search(data, keyword);
            List<Object> first = KomutechJson.asList(KomutechJson.asMap(searchData.get("pages")).get("1"));
            if (first == null || first.isEmpty() || first.get(0) == null) {
                mode = "normal";
                keyword = "";
                searchData = null;
                page = "1";
                setMeta(data, page, mode, keyword);
                write(playerName, storageName, data);
            }
        }
        return new LoadedView(data, page, mode, searchData, keyword);
    }

    public record LoadedView(Map<String, Object> data, String page, String mode,
                             Map<String, Object> searchData, String keyword) {
    }
}

