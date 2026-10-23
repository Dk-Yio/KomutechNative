package tech.komutech.native_scripts.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechAsyncScheduler;
import tech.komutech.native_scripts.support.KomutechJson;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.KomutechPaths;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;
import tech.komutech.native_scripts.support.MainThread;
import tech.komutech.native_scripts.support.MenuGuiHelper;

public final class NaJieScript implements NativeLifecycleScript, KomutechMenuHandler {

    private static final String TITLE = "§b纳戒";
    private static final int SIZE = 54;
    private static final long COOLDOWN_MS = 1000L;

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<Player> openPlayers = new HashSet<>();
    private Plugin plugin;

    @Override
    public void registerLifecycle(Plugin plugin) {
        this.plugin = plugin;
        KomutechMenuRouter.registerHandler(this);
    }

    @Override
    public boolean handles(InventoryView view) {
        return TITLE.equals(view.getTitle());
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        if (ctx.hand() != EquipmentSlot.HAND) {
            return null;
        }
        Player player = ctx.player();
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < COOLDOWN_MS) {
            double remain = Math.ceil((COOLDOWN_MS - (now - last)) / 100.0) / 10.0;
            KomutechSupport.actionBar(player, "§c纳戒冷却中，请等待 §6" + remain + " §c秒");
            return null;
        }
        cooldowns.put(player.getUniqueId(), now);
        if (TITLE.equals(player.getOpenInventory().getTitle())) {
            KomutechSupport.send(player, "§c纳戒已打开，请先关闭当前界面再试！");
            return null;
        }
        final List<String>[] loaded = new List[1];
        KomutechAsyncScheduler.submit(
            () -> loaded[0] = load(player),
            ignored -> MainThread.run(plugin, () -> {
                Inventory inv = deserialize(loaded[0]);
                player.openInventory(inv);
                openPlayers.add(player);
            })
        );
        return null;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!openPlayers.remove(player) || !TITLE.equals(event.getView().getTitle())) {
            return;
        }
        saveAsync(player, event.getInventory());
    }

    private void saveAsync(Player player, Inventory inventory) {
        try {
            List<String> data = new ArrayList<>();
            for (int i = 0; i < inventory.getSize(); i++) {
                ItemStack item = inventory.getItem(i);
                if (item == null || item.getType().isAir()) {
                    data.add(null);
                } else {
                    data.add(Base64.getEncoder().encodeToString(item.serializeAsBytes()));
                }
            }
            final java.nio.file.Path path = file(player);
            final String json = KomutechJson.stringify(data);
            KomutechAsyncScheduler.submit(
                () -> {
                    try {
                        Files.createDirectories(KomutechPaths.naJie());
                        Files.writeString(path, json, StandardCharsets.UTF_8);
                    } catch (Exception ignored) {
                    }
                },
                ignored -> {}
            );
        } catch (Exception ignored) {
        }
    }

    private java.nio.file.Path file(Player player) {
        return KomutechPaths.naJie().resolve("[" + player.getName() + "]纳戒.json");
    }

    private List<String> load(Player player) {
        try {
            java.nio.file.Path path = file(player);
            if (!Files.exists(path)) {
                return null;
            }
            Object parsed = KomutechJson.parse(Files.readString(path, StandardCharsets.UTF_8));
            return KomutechJson.asList(parsed).stream().map(String::valueOf).toList();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Inventory deserialize(List<String> data) {
        Inventory inv = MenuGuiHelper.create(SIZE, TITLE);
        if (data == null) {
            return inv;
        }
        for (int i = 0; i < data.size() && i < SIZE; i++) {
            String encoded = data.get(i);
            if (encoded == null || "null".equals(encoded)) {
                continue;
            }
            try {
                inv.setItem(i, ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded)));
            } catch (Exception ignored) {
            }
        }
        return inv;
    }
}
