package tech.komutech.native_scripts.cultivation;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.AttributeApplier;
import tech.komutech.native_scripts.support.AttributePointLimits;
import tech.komutech.native_scripts.support.CultivationMath;
import tech.komutech.native_scripts.support.DisplayReflectionHelper;
import tech.komutech.native_scripts.support.KomutechJson;
import tech.komutech.native_scripts.support.KomutechPaths;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.MenuGuiHelper;
import tech.komutech.native_scripts.support.PlayerAttributeStore;
import tech.origintech.native_scripts.support.MachineScriptHelper;

public final class PuTuanScript implements NativeLifecycleScript, KomutechMenuHandler {

    private static final String GUI_TITLE = "§d修炼蒲团";
    private static final String STATE_KEY = "meditation_state";
    private static final String PLAYER_UUID_KEY = "meditation_player_uuid";
    static final String HOLO_UUID_KEY = "meditation_holo_uuid";
    private static final String PRAYER_MAT_KEY = "prayer_mat_id";
    private static final String PROJ_UUID_KEY = "meditation_proj_uuid";
    private static final int BREAK_DURATION_TICKS = 15 * 20;
    /** 监控周期：1 秒（20 tick），位置检测与粒子同频；灵气结算每 3 次监控执行一次 */
    private static final long MONITOR_INTERVAL_TICKS = 20L;
    private static final int SPIRIT_SETTLE_EVERY_N_TICKS = 3;
    private static volatile Map<String, Object> cachedPuTuanConfig = Map.of();
    private static volatile long cachedPuTuanConfigAt = 0L;
    private static final long PU_TUAN_CONFIG_CACHE_MS = 60_000L;

    /** 配置缺失时的内置速度，避免空蒲等高级台回退成默认 1 比云榻还慢 */
    private static final Map<String, Double> DEFAULT_BASE_GAIN = Map.of(
        "KOMUTECH_L_X_蒲团", 1.0,
        "KOMUTECH_L_X_玉坐", 3.0,
        "KOMUTECH_L_X_莲台", 9.0,
        "KOMUTECH_L_X_云榻", 16.0,
        "KOMUTECH_L_X_空蒲", 32.0
    );
    private static final Map<String, Long> DEFAULT_MIN_SPIRIT = Map.of(
        "KOMUTECH_L_X_莲台", 1000L,
        "KOMUTECH_L_X_云榻", 10_000L,
        "KOMUTECH_L_X_空蒲", 1_000_000L
    );

    private static final Map<Long, Integer> TRIBULATION_THRESHOLDS = Map.of(
        100_000L, 9,
        1_000_000L, 16,
        10_000_000L, 21,
        100_000_000L, 36,
        1_000_000_000L, 49,
        10_000_000_000L, 81,
        100_000_000_000L, 99
    );

    private final Map<Player, Location> openPlayers = new HashMap<>();
    private final Map<UUID, Location> playerActiveMat = new HashMap<>();
    private final Map<UUID, Map<String, Object>> sessionData = new HashMap<>();
    private final Set<Location> monitorLocs = new HashSet<>();
    private final Map<String, Integer> breakTasks = new HashMap<>();
    private final Map<String, Integer> tribulationTasks = new HashMap<>();
    private final Map<UUID, Integer> monitorSpiritTicks = new HashMap<>();
    private BukkitTask monitorTask;
    private Plugin plugin;

    @Override
    public void registerLifecycle(Plugin plugin) {
        this.plugin = plugin;
        KomutechMenuRouter.registerHandler(this);
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                handlePlayerQuit(event.getPlayer());
            }

            @EventHandler
            public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
                // 崩溃后区块重载时清掉无主「修炼中」全息/投影
                sweepChunkOrphans(event.getChunk());
            }

            @EventHandler
            public void onPluginDisable(org.bukkit.event.server.PluginDisableEvent event) {
                if (event.getPlugin() != plugin) {
                    return;
                }
                // 正常关服：先落盘状态为空闲并清实体，降低下次崩溃残留概率
                for (Location loc : new HashSet<>(monitorLocs)) {
                    forceRelease(loc);
                }
                monitorLocs.clear();
                playerActiveMat.clear();
                sessionData.clear();
                if (monitorTask != null) {
                    monitorTask.cancel();
                    monitorTask = null;
                }
            }
        }, plugin);
        // 启动后扫一遍已加载区块（崩溃重启时 chunk load 可能已错过）
        Bukkit.getScheduler().runTaskLater(plugin, this::sweepAllLoadedOrphans, 40L);
        Bukkit.getScheduler().runTaskLater(plugin, this::sweepAllLoadedOrphans, 20L * 15);
    }

    /** 清理无主蒲团 Display；若机器仍标记为修炼中则强制释放。 */
    private void sweepChunkOrphans(org.bukkit.Chunk chunk) {
        PuTuanDisplayHelper.sweepChunk(chunk, (display, key) -> {
            Location matLoc = PuTuanDisplayHelper.parseLocationKey(key);
            if (matLoc == null) {
                return true;
            }
            return shouldPurgeTaggedDisplay(matLoc, display.getUniqueId());
        });
    }

    private void sweepAllLoadedOrphans() {
        int removed = PuTuanDisplayHelper.sweepLoadedWorlds((display, key) -> {
            Location matLoc = PuTuanDisplayHelper.parseLocationKey(key);
            if (matLoc == null) {
                return true;
            }
            return shouldPurgeTaggedDisplay(matLoc, display.getUniqueId());
        });
        if (removed > 0 && plugin != null) {
            plugin.getLogger().info("[蒲团] 已清理崩溃/残留修炼显示实体: " + removed);
        }
    }

    /**
     * @return true 表示该 Display 应删除（无主、状态空闲却叠层、UUID 不匹配、占用者离线等）
     */
    private boolean shouldPurgeTaggedDisplay(Location matLoc, UUID displayId) {
        int state = getState(matLoc);
        String holoId = MachineScriptHelper.getData(matLoc, HOLO_UUID_KEY);
        String projId = MachineScriptHelper.getData(matLoc, PROJ_UUID_KEY);
        String displayIdStr = displayId.toString();
        boolean linked = displayIdStr.equals(holoId) || displayIdStr.equals(projId);

        if (state == 0) {
            // 空闲：保留当前登记的空闲全息/投影，只删未登记孤儿
            if (!linked) {
                return true;
            }
            // 登记 UUID 仍指向「修炼中/突破中」脏字样 → 删掉，由后续打开菜单时刷新
            Entity entity = matLoc.getWorld() == null ? null : matLoc.getWorld().getEntity(displayId);
            if (entity instanceof org.bukkit.entity.TextDisplay textDisplay) {
                String plain = plainText(textDisplay);
                if (plain.contains("修炼中") || plain.contains("突破中")) {
                    MachineScriptHelper.removeData(matLoc, HOLO_UUID_KEY);
                    return true;
                }
            }
            return false;
        }

        String occupantId = MachineScriptHelper.getData(matLoc, PLAYER_UUID_KEY);
        if (occupantId == null) {
            forceRelease(matLoc);
            return true;
        }
        Player occupant;
        try {
            occupant = Bukkit.getPlayer(UUID.fromString(occupantId));
        } catch (IllegalArgumentException e) {
            forceRelease(matLoc);
            return true;
        }
        if (occupant == null || !occupant.isOnline()) {
            forceRelease(matLoc);
            return true;
        }

        // 有在线占用者：只删「不是当前记录 UUID」的孤儿叠层
        if (!linked) {
            return true;
        }
        // 记录 UUID 匹配但仍不在蒲团上 → 释放整场
        if (!isOnMat(occupant, matLoc)) {
            forceRelease(matLoc);
            return true;
        }
        return false;
    }

    private static String plainText(org.bukkit.entity.TextDisplay display) {
        try {
            Object text = display.getText();
            return text == null ? "" : text.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void handlePlayerQuit(Player player) {
        openPlayers.remove(player);
        sessionData.remove(player.getUniqueId());
        monitorSpiritTicks.remove(player.getUniqueId());
        Location loc = playerActiveMat.remove(player.getUniqueId());
        if (loc != null) {
            endSession(loc, player, null);
            removeMonitorLoc(loc);
        }
    }

    @Override
    public boolean handles(InventoryView view) {
        return GUI_TITLE.equals(view.getTitle());
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onUse".equals(functionName)) {
            try {
                Object event = args[0];
                Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
                Object blockOpt = event.getClass().getMethod("getClickedBlock").invoke(event);
                if (blockOpt == null) {
                    return null;
                }
                Object block = blockOpt.getClass().getMethod("get").invoke(blockOpt);
                Location loc = ((org.bukkit.block.Block) block).getLocation();
                openMenu(player, loc);
            } catch (ReflectiveOperationException ignored) {
            }
        } else if ("onBreak".equals(functionName)) {
            try {
                Object event = args[0];
                Location loc = ((org.bukkit.block.Block) event.getClass().getMethod("getBlock").invoke(event)).getLocation();
                cancelBreakTasks(loc);
                cleanup(loc, null);
                setState(loc, 0);
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
        if (!GUI_TITLE.equals(event.getView().getTitle())) {
            return;
        }
        event.setCancelled(true);
        Location loc = openPlayers.get(player);
        if (loc == null) {
            player.closeInventory();
            return;
        }
        int slot = event.getSlot();
        int state = getState(loc);
        if (slot == 3) {
            if (state == 0) {
                startMeditation(player, loc);
            } else if (state == 1) {
                stopMeditation(player, loc);
            } else {
                KomutechSupport.send(player, "§c请先停止突破。");
            }
            player.closeInventory();
        } else if (slot == 5) {
            if (state == 0) {
                startBreakthrough(player, loc);
            } else if (state == 2) {
                cancelBreakTasks(loc);
                stopBreakthrough(player, loc);
            } else {
                KomutechSupport.send(player, "§c请先停止修炼。");
            }
            player.closeInventory();
        } else if (slot == 8) {
            cancelBreakTasks(loc);
            cleanup(loc, player);
            setState(loc, 0);
            removeMonitorLoc(loc);
            player.closeInventory();
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            openPlayers.remove(player);
        }
    }

    private void openMenu(Player player, Location loc) {
        reconcileStaleSession(loc);
        if (!checkMatSpiritRequirement(player, loc)) {
            return;
        }
        Inventory inv = MenuGuiHelper.create(9, GUI_TITLE);
        int state = getState(loc);
        String status = state == 0 ? "§7○ 空闲" : (state == 1 ? "§a● 修炼中" : "§d● 突破中");
        inv.setItem(4, MenuGuiHelper.item(Material.BOOK, status, List.of("§7当前状态")));
        inv.setItem(3, MenuGuiHelper.item(Material.LIME_DYE, state == 1 ? "§e停止修炼" : "§a开始修炼",
            List.of("§7点击开始/停止修炼")));
        inv.setItem(5, MenuGuiHelper.item(Material.REDSTONE, state == 2 ? "§e停止突破" : "§c开始突破",
            List.of("§7灵气满时可尝试突破")));
        inv.setItem(8, MenuGuiHelper.item(Material.BARRIER, "§c强制停止", List.of()));
        openPlayers.put(player, loc);
        player.openInventory(inv);
    }

    private void startMeditation(Player player, Location loc) {
        if (!checkMatSpiritRequirement(player, loc)) {
            return;
        }
        Map<String, Object> data = PlayerAttributeStore.load(player.getName());
        if (data == null) {
            KomutechSupport.send(player, "§c无法读取玩家数据");
            return;
        }
        if (!tryBeginSession(player, loc, 1, data)) {
            return;
        }
        KomutechSupport.send(player, "§a你开始修炼了。");
    }

    private void stopMeditation(Player player, Location loc) {
        cleanup(loc, player);
        setState(loc, 0);
        removeMonitorLoc(loc);
        KomutechSupport.send(player, "§e你停止了修炼。");
    }

    private void startBreakthrough(Player player, Location loc) {
        if (!checkMatSpiritRequirement(player, loc)) {
            return;
        }
        Map<String, Object> data = PlayerAttributeStore.load(player.getName());
        if (data == null) {
            KomutechSupport.send(player, "§c无法读取玩家数据");
            return;
        }
        PlayerAttributeStore.Lingli lingli = PlayerAttributeStore.parseLingli(data.get("灵气"));
        if (lingli.isUnlimitedMax()) {
            KomutechSupport.send(player, "§c仙人境界灵气无上限，无法通过蒲团突破");
            return;
        }
        if (lingli.current() < lingli.max()) {
            KomutechSupport.send(player, "§c灵气未满，无法突破");
            return;
        }
        if (!tryBeginSession(player, loc, 2, data)) {
            return;
        }
        KomutechSupport.send(player, "§a你开始突破了！");
        startBreakSequence(loc, player, data);
    }

    private void startBreakSequence(Location loc, Player player, Map<String, Object> data) {
        cancelBreakTasks(loc);
        Map<String, Object> root = PlayerAttributeStore.getMap(data, "根基");
        int solid = PlayerAttributeStore.getInt(root, "稳固值", 0);
        int drug = PlayerAttributeStore.getInt(root, "破镜药力", 0);
        int rate = Math.min(25 + solid + drug, 100);
        boolean success = ThreadLocalRandom.current().nextInt(100) < rate;
        KomutechSupport.send(player, "§6突破中... 成功率: §e" + rate + "%");
        String key = locationKey(loc);
        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int tick = 0;

            @Override
            public void run() {
                tick++;
                if (tick >= BREAK_DURATION_TICKS / 20) {
                    Integer id = breakTasks.remove(key);
                    if (id != null) {
                        Bukkit.getScheduler().cancelTask(id);
                    }
                    finishBreakthrough(loc, player, data, success);
                }
            }
        }, 0L, 20L).getTaskId();
        breakTasks.put(key, taskId);
    }

    private void stopBreakthrough(Player player, Location loc) {
        cleanup(loc, player);
        setState(loc, 0);
        removeMonitorLoc(loc);
        KomutechSupport.send(player, "§e你停止了突破。");
    }

    private void finishBreakthrough(Location loc, Player player, Map<String, Object> data, boolean success) {
        if (!success) {
            failBreakthrough(loc, player, data);
            return;
        }
        PlayerAttributeStore.Lingli lingli = PlayerAttributeStore.parseLingli(data.get("灵气"));
        double nmax = lingli.max() * 10;
        if (nmax > 100_000_000_000_000L) {
            nmax = 100_000_000_000_000L;
        }
        int tribCount = tribulationCount(nmax);
        if (tribCount > 0) {
            KomutechSupport.send(player, "§c§l雷劫降临！需承受 " + tribCount + " 道天雷！");
            startTribulation(loc, player, data, nmax, tribCount, 1, lingli.current());
        } else {
            applyBreakSuccess(loc, player, data, nmax);
        }
    }

    private void failBreakthrough(Location loc, Player player, Map<String, Object> data) {
        PlayerAttributeStore.Lingli lingli = PlayerAttributeStore.parseLingli(data.get("灵气"));
        Map<String, Object> root = PlayerAttributeStore.getMap(data, "根基");
        double baseReduce = 0.15 + ThreadLocalRandom.current().nextDouble(0.15);
        double reduction = PlayerAttributeStore.getInt(root, "保阶药力", 0) / 100.0;
        double reduce = Math.max(0, baseReduce - reduction);
        double reduced = lingli.current() * (1 - reduce);
        data.put("灵气", PlayerAttributeStore.formatSpiritAmount(reduced, lingli.max()));
        double ovLimit = Math.floor((PlayerAttributeStore.getDouble(data, "根骨", 1) / 10.0) * lingli.max() * 100) / 100.0;
        root.put("溢出的灵气", String.format("0.00/%.2f", ovLimit));
        root.put("稳固值", 0);
        root.put("破镜药力", 0);
        root.put("保阶药力", 0);
        data.put("根基", root);
        data.put("突破失败", true);
        PlayerAttributeStore.save(player.getName(), data);
        PuTuanParticles.breakthroughFailure(player);
        KomutechSupport.send(player, "§c突破失败... 灵气损失了 " + (int) (reduce * 100) + "%");
        cleanup(loc, player);
        setState(loc, 0);
        removeMonitorLoc(loc);
    }

    private void startTribulation(Location loc, Player player, Map<String, Object> data, double nmax,
                                  int total, int current, double curSpirit) {
        if (!player.isOnline() || player.isDead()) {
            KomutechSupport.send(player, "§c渡劫失败...");
            failTribulation(loc, player, data);
            return;
        }
        if (current > total) {
            KomutechSupport.send(player, "§a§l雷劫渡过！突破成功！");
            applyBreakSuccess(loc, player, data, nmax);
            return;
        }
        KomutechSupport.send(player, "§e第 " + current + "/" + total + " 道雷劫");
        PuTuanParticles.tribulationStrike(player);
        int meritBefore = PlayerAttributeStore.getInt(data, "功德", 0);
        double finalDamage = calculateTribulationDamage(player, data, current);
        int meritAfter = PlayerAttributeStore.getInt(data, "功德", 0);
        int meritSpent = meritBefore - meritAfter;
        player.damage(finalDamage);
        KomutechSupport.send(player, "§c受到了 " + (int) finalDamage + " 点雷劫伤害");
        if (meritSpent > 0) {
            KomutechSupport.send(player, "§a功德护体：消耗 §6" + meritSpent + " §a点功德，减免雷劫伤害");
        }
        if (player.isDead()) {
            KomutechSupport.send(player, "§c你未能承受雷劫...");
            failTribulation(loc, player, data);
            return;
        }
        PlayerAttributeStore.save(player.getName(), data);
        String tribKey = locationKey(loc) + "_trib";
        int taskId = Bukkit.getScheduler().runTaskLater(plugin, () ->
            startTribulation(loc, player, data, nmax, total, current + 1, curSpirit), 60L).getTaskId();
        tribulationTasks.put(tribKey, taskId);
    }

    private double calculateTribulationDamage(Player player, Map<String, Object> data, int strike) {
        double baseDamage = PlayerAttributeStore.getInt(data, "血量", 0) * 3.0;
        double realmMult = CultivationMath.getRealmCoefficient(data);
        var elements = CultivationMath.splitLinggen(PlayerAttributeStore.getString(data, "灵根", ""));
        double quality = PlayerAttributeStore.getDouble(data, "总品质", 0.5);
        double linggenMult = CultivationMath.getLinggenFinalCoefficient(elements, quality, "灵力");
        double shaQiMult = 1 + PlayerAttributeStore.getInt(data, "煞气", 0) / 10000.0;
        double genguReduce = Math.min(PlayerAttributeStore.getInt(data, "根骨", 0) * 0.03, 0.8);
        double growth = Math.pow(1.05, strike - 1);
        double damage = baseDamage * realmMult * linggenMult * shaQiMult * (1 - genguReduce) * growth;
        double defenseReduce = PlayerAttributeStore.getDouble(data, "防御力_实际", 0) * PlayerAttributeStore.getInt(data, "根骨", 0);
        damage = Math.max(1, Math.floor(damage - defenseReduce));
        int meritReduce = (int) Math.floor(PlayerAttributeStore.getInt(data, "功德", 0) / 100.0);
        int reduced = (int) Math.min(damage, meritReduce);
        data.put("功德", PlayerAttributeStore.getInt(data, "功德", 0) - reduced * 100);
        return Math.max(1, Math.floor(damage - reduced));
    }

    private void failTribulation(Location loc, Player player, Map<String, Object> data) {
        failBreakthrough(loc, player, data);
    }

    private void applyBreakSuccess(Location loc, Player player, Map<String, Object> data, double nmax) {
        double ncur = nmax / 10 + 1;
        if (ncur > nmax) {
            ncur = nmax;
        }
        data.put("灵气", PlayerAttributeStore.formatSpiritAmount(ncur, nmax));
        data.put("修为", CultivationMath.getCultivationInfo((long) Math.floor(ncur)).stage());
        CultivationMath.ensureLingliFormat(data);
        Map<String, Object> root = PlayerAttributeStore.getMap(data, "根基");
        double ovLimit = Math.floor((PlayerAttributeStore.getDouble(data, "根骨", 1) / 10.0) * nmax * 100) / 100.0;
        data.put("突破失败", false);
        boolean hasDrug = PlayerAttributeStore.getInt(root, "破镜药力", 0) > 0
            || PlayerAttributeStore.getInt(root, "保阶药力", 0) > 0;
        if (hasDrug) {
            data.put("属性点", PlayerAttributeStore.getInt(data, "属性点", 0) + ThreadLocalRandom.current().nextInt(4, 8));
        } else {
            data.put("属性点", PlayerAttributeStore.getInt(data, "属性点", 0) + 6);
        }
        root.put("稳固值", 0);
        root.put("溢出的灵气", String.format("0.00/%.2f", ovLimit));
        root.put("破镜药力", 0);
        root.put("保阶药力", 0);
        data.put("根基", root);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        PuTuanParticles.breakthroughSuccess(player);
        KomutechSupport.send(player, "§a§l恭喜！突破成功！修为提升至 "
            + PlayerAttributeStore.getString(data, "修为", "") + "，获得属性点奖励！");
        // 境界系数变化后重新截断并应用战斗属性，避免实际加成 silently 超过加点上限
        AttributeApplier.applyPlayerAttributes(player, data, false);
        PlayerAttributeStore.save(player.getName(), data);
        cleanup(loc, player);
        setState(loc, 0);
        removeMonitorLoc(loc);
    }

    private void ensureMonitor() {
        if (monitorTask != null) {
            return;
        }
        monitorTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (monitorLocs.isEmpty()) {
                if (monitorTask != null) {
                    monitorTask.cancel();
                    monitorTask = null;
                }
                return;
            }
            monitorLocs.removeIf(this::processMonitorTick);
        }, 0L, MONITOR_INTERVAL_TICKS);
    }

    private boolean processMonitorTick(Location loc) {
        int state = getState(loc);
        if (state != 1 && state != 2) {
            return true;
        }
        String playerId = MachineScriptHelper.getData(loc, PLAYER_UUID_KEY);
        if (playerId == null) {
            setState(loc, 0);
            return true;
        }
        Player player;
        try {
            player = Bukkit.getPlayer(UUID.fromString(playerId));
        } catch (IllegalArgumentException e) {
            endSession(loc, null, "§e修炼中断。");
            return true;
        }
        if (player == null || !player.isOnline()) {
            endSession(loc, null, null);
            return true;
        }
        Location bound = playerActiveMat.get(player.getUniqueId());
        if (bound == null || !sameBlock(bound, loc)) {
            endSession(loc, player, "§e你已离开修炼台，修炼中断。");
            return true;
        }
        if (!isOnMat(player, loc)) {
            endSession(loc, player, "§e你已离开修炼台，修炼中断。");
            return true;
        }
        maintainSit(player, loc);
        if (state == 1) {
            PuTuanParticles.meditation(player);
            int tick = monitorSpiritTicks.merge(player.getUniqueId(), 1, Integer::sum);
            if (tick % SPIRIT_SETTLE_EVERY_N_TICKS != 0) {
                return false;
            }
            Map<String, Object> data = sessionData.get(player.getUniqueId());
            if (data == null) {
                data = PlayerAttributeStore.load(player.getName());
                if (data != null) {
                    sessionData.put(player.getUniqueId(), data);
                }
            }
            if (data != null) {
                boolean full = addSpirit(player, loc, data);
                if (full) {
                    KomutechSupport.send(player, "§6灵气与溢出的灵气均已满，修炼自动结束。");
                    endSession(loc, player, null);
                    return true;
                }
            }
        } else if (state == 2) {
            PuTuanParticles.breakthroughProgress(player);
        }
        return false;
    }

    private void endSession(Location loc, Player player, String message) {
        cancelBreakTasks(loc);
        cleanup(loc, player);
        setState(loc, 0);
        PuTuanDisplayHelper.updateHologram(loc, statusText(0));
        if (player != null && message != null) {
            KomutechSupport.send(player, message);
        }
    }

    private boolean addSpirit(Player player, Location loc, Map<String, Object> data) {
        Map<String, Object> latest = PlayerAttributeStore.load(player.getName());
        if (latest != null) {
            data = latest;
            sessionData.put(player.getUniqueId(), data);
        }
        String oldStage = PlayerAttributeStore.getString(data, "修为", "");
        PlayerAttributeStore.Lingli lingli = PlayerAttributeStore.parseLingli(data.get("灵气"));
        double wuxing = PlayerAttributeStore.getDouble(data, "悟性", 1);
        double quality = PlayerAttributeStore.getDouble(data, "总品质", 0.5);
        String attr = PlayerAttributeStore.getString(data, "灵根属性", "");
        double coeff = attrCoeff(attr) * realmCoeff(data);
        double baseGain = loadBaseGain(loc);
        int spiritGainPoints = PlayerAttributeStore.getInt(data, "灵气获取", 0);
        double efficiency = AttributePointLimits.spiritGainPercent(data, spiritGainPoints) / 100.0;
        // 恢复旧版加法：低修为 realmCoeff=0 时仍能拿到蒲团基础速度；乘效率仍生效
        // 旧 JS: max(0.01, wuxing * quality * coeff + baseGain)
        double gain = Math.max(0.01,
            (wuxing * quality * coeff + Math.max(0.01, baseGain)) * Math.max(0.01, efficiency));
        Map<String, Object> root = PlayerAttributeStore.getMap(data, "根基");
        if (!data.containsKey("根基")) {
            data.put("根基", root);
        }
        double cur = lingli.current();
        double max = lingli.max();
        double ovCur = parseOverflowCurrent(root);
        double ovLimit;
        if (lingli.isUnlimitedMax() || !Double.isFinite(max) || max <= 0) {
            ovLimit = Double.POSITIVE_INFINITY;
        } else {
            ovLimit = Math.floor((PlayerAttributeStore.getDouble(data, "根骨", 1) / 10.0) * max * 100) / 100.0;
        }
        if (!lingli.isUnlimitedMax() && cur < max) {
            cur = Math.min(cur + gain, max);
            data.put("灵气", PlayerAttributeStore.formatSpiritAmount(cur, max));
        } else if (lingli.isUnlimitedMax()) {
            cur = cur + gain;
            data.put("灵气", PlayerAttributeStore.formatSpiritAmount(cur, max));
        }
        if (!lingli.isUnlimitedMax() && cur >= max) {
            double extra = cur + gain - max;
            ovCur = Math.min(ovCur + extra, ovLimit);
            root.put("稳固值", (int) Math.floor(ovCur / (max * 0.01)));
            root.put("溢出的灵气", String.format("%.2f/%.2f", ovCur, ovLimit));
            data.put("根基", root);
        }
        long stageValue = (long) Math.floor(Math.min(cur, Long.MAX_VALUE));
        var info = CultivationMath.getCultivationInfo(stageValue);
        data.put("修为", info.stage());
        if (!oldStage.isBlank() && !oldStage.equals(info.stage()) && !PlayerAttributeStore.getBoolean(data, "突破失败", false)) {
            data.put("属性点", PlayerAttributeStore.getInt(data, "属性点", 0) + ThreadLocalRandom.current().nextInt(2, 4));
            KomutechSupport.send(player, "§a恭喜！修为突破小阶段，获得属性点奖励！");
            AttributeApplier.applyPlayerAttributes(player, data, false);
        }
        CultivationMath.ensureLingliFormat(data);
        PlayerAttributeStore.save(player.getName(), data);
        PlayerAttributeStore.Lingli updated = PlayerAttributeStore.parseLingli(data.get("灵气"));
        if (updated.isUnlimitedMax()) {
            KomutechSupport.actionBar(player, "§b灵气: §6" + PlayerAttributeStore.formatAmount(updated.current()) + " §7/ §6無");
            return false;
        }
        if (updated.current() >= updated.max()) {
            KomutechSupport.actionBar(player, "§b溢出的灵气: §6" + String.format("%.2f", ovCur) + " §7/ §6" + String.format("%.2f", ovLimit));
        } else {
            KomutechSupport.actionBar(player, "§b灵气: §6" + String.format("%.2f", updated.current()) + " §7/ §6" + trim(updated.max()));
        }
        return ovCur >= ovLimit && updated.current() >= updated.max();
    }

    private static double parseOverflowCurrent(Map<String, Object> root) {
        String overflow = PlayerAttributeStore.getString(root, "溢出的灵气", "0/0");
        var match = Pattern.compile("^(\\d+(?:\\.\\d+)?)").matcher(overflow.trim());
        if (!match.find()) {
            return 0;
        }
        try {
            return Double.parseDouble(match.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static Map<String, Object> puTuanConfig() {
        long now = System.currentTimeMillis();
        if (now - cachedPuTuanConfigAt < PU_TUAN_CONFIG_CACHE_MS && cachedPuTuanConfig != null) {
            return cachedPuTuanConfig;
        }
        try {
            if (!Files.exists(KomutechPaths.puTuanConfig())) {
                cachedPuTuanConfig = Map.of();
            } else {
                cachedPuTuanConfig = KomutechJson.asMap(KomutechJson.parse(
                    Files.readString(KomutechPaths.puTuanConfig(), StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {
            cachedPuTuanConfig = Map.of();
        }
        cachedPuTuanConfigAt = now;
        return cachedPuTuanConfig;
    }

    private static String matIdAt(Location loc) {
        String id = MachineScriptHelper.getData(loc, PRAYER_MAT_KEY);
        if (id != null) {
            return id;
        }
        SlimefunItem sf = MachineScriptHelper.getSfItem(loc);
        return sf == null ? "KOMUTECH_L_X_蒲团" : sf.getId();
    }

    private static long loadMinSpirit(String matId) {
        try {
            Map<String, Object> itemCfg = KomutechJson.asMap(puTuanConfig().get(matId));
            if (!itemCfg.isEmpty()) {
                return (long) KomutechJson.getDouble(itemCfg, "最低灵气",
                    KomutechJson.getDouble(itemCfg, "minSpirit", DEFAULT_MIN_SPIRIT.getOrDefault(matId, 0L)));
            }
        } catch (Exception ignored) {
            // fall through to defaults
        }
        return DEFAULT_MIN_SPIRIT.getOrDefault(matId, 0L);
    }

    private static boolean checkMatSpiritRequirement(Player player, Location loc) {
        long required = loadMinSpirit(matIdAt(loc));
        if (required <= 0L) {
            return true;
        }
        Map<String, Object> data = PlayerAttributeStore.load(player.getName());
        if (data == null) {
            KomutechSupport.send(player, "§c无法读取玩家数据");
            return false;
        }
        if (CultivationMath.spiritValue(data) < required) {
            CultivationMath.CultivationInfo info = CultivationMath.getCultivationInfo(required);
            KomutechSupport.send(player, "§c修为不足！至少需要 §6" + info.stage() + " §c方可使用此修炼台");
            return false;
        }
        return true;
    }

    private static double loadBaseGain(Location loc) {
        String matId = matIdAt(loc);
        double fallback = DEFAULT_BASE_GAIN.getOrDefault(matId, 1.0);
        try {
            Map<String, Object> cfg = puTuanConfig();
            if (cfg.isEmpty()) {
                return fallback;
            }
            Map<String, Object> itemCfg = KomutechJson.asMap(cfg.get(matId));
            if (itemCfg.isEmpty()) {
                return fallback;
            }
            double configured = KomutechJson.getDouble(itemCfg, "基础修炼速度",
                KomutechJson.getDouble(itemCfg, "baseGain", fallback));
            return configured > 0 ? configured : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void rememberPrayerMat(Location loc) {
        SlimefunItem sf = MachineScriptHelper.getSfItem(loc);
        MachineScriptHelper.setData(loc, PRAYER_MAT_KEY, sf == null ? "KOMUTECH_L_X_蒲团" : sf.getId());
    }

    private boolean tryBeginSession(Player player, Location loc, int state, Map<String, Object> data) {
        reconcileStaleSession(loc);
        UUID uuid = player.getUniqueId();
        Location existing = playerActiveMat.get(uuid);
        if (existing != null && !sameBlock(existing, loc)) {
            KomutechSupport.send(player, "§c你已在其他修炼台上修炼，请先停止。");
            return false;
        }
        int currentState = getState(loc);
        if (currentState != 0 && currentState != state) {
            KomutechSupport.send(player, "§c该修炼台正在使用中。");
            return false;
        }
        String occupant = MachineScriptHelper.getData(loc, PLAYER_UUID_KEY);
        if (occupant != null && !occupant.equals(uuid.toString())) {
            KomutechSupport.send(player, "§c该修炼台已被其他玩家占用。");
            return false;
        }
        beginSession(player, loc, state, data);
        return true;
    }

    /**
     * 回收方块持久数据与内存不一致的过期占用（离线玩家、重载后残留、仅写了一半的状态等）。
     */
    private void reconcileStaleSession(Location loc) {
        String occupantId = MachineScriptHelper.getData(loc, PLAYER_UUID_KEY);
        int state = getState(loc);

        if (state == 0) {
            if (occupantId != null || containsMonitorLoc(loc)) {
                forceRelease(loc);
            }
            return;
        }

        if (occupantId == null) {
            setState(loc, 0);
            PuTuanDisplayHelper.updateHologram(loc, statusText(0));
            removeMonitorLoc(loc);
            return;
        }

        Player occupant;
        try {
            occupant = Bukkit.getPlayer(UUID.fromString(occupantId));
        } catch (IllegalArgumentException e) {
            forceRelease(loc);
            return;
        }

        if (occupant == null || !occupant.isOnline()) {
            forceRelease(loc);
            return;
        }

        Location bound = playerActiveMat.get(occupant.getUniqueId());
        if (bound != null && sameBlock(bound, loc)) {
            if (!isOnMat(occupant, loc)) {
                endSession(loc, occupant, null);
            } else {
                addMonitorLoc(loc);
                ensureMonitor();
            }
            return;
        }

        if (isOnMat(occupant, loc)) {
            playerActiveMat.put(occupant.getUniqueId(), loc);
            Map<String, Object> loaded = PlayerAttributeStore.load(occupant.getName());
            if (loaded != null) {
                sessionData.put(occupant.getUniqueId(), loaded);
            }
            addMonitorLoc(loc);
            ensureMonitor();
            return;
        }

        forceRelease(loc);
    }

    private void forceRelease(Location loc) {
        cancelBreakTasks(loc);
        cleanup(loc, null);
        setState(loc, 0);
        removeMonitorLoc(loc);
        // cleanup 已按 PDC 清实体；空闲态不再刷「修炼中」，只保留干净空闲全息
        PuTuanDisplayHelper.updateHologram(loc, statusText(0));
    }

    /** 供管理指令：强制清理脚下/指定坐标的蒲团残留。 */
    public int adminCleanupAt(Location loc) {
        forceRelease(loc);
        int extra = PuTuanDisplayHelper.removeTaggedAt(loc);
        PuTuanSitHelper.cleanupAt(loc);
        return extra;
    }

    public int adminCleanupNearby(Location center, double radius) {
        int mats = 0;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (var display : PuTuanDisplayHelper.findTaggedNear(center, radius)) {
            String key = display.getPersistentDataContainer()
                .get(PuTuanDisplayHelper.tagKey(), org.bukkit.persistence.PersistentDataType.STRING);
            if (key == null || key.isEmpty()) {
                if (!display.isDead()) {
                    display.remove();
                }
                continue;
            }
            if (!seen.add(key)) {
                continue;
            }
            Location mat = PuTuanDisplayHelper.parseLocationKey(key);
            if (mat != null) {
                forceRelease(mat);
                mats++;
            } else if (!display.isDead()) {
                display.remove();
            }
        }
        return mats;
    }

    public int adminSweepAll() {
        final int[] removed = {0};
        removed[0] += PuTuanDisplayHelper.sweepLoadedWorlds((display, key) -> {
            Location matLoc = PuTuanDisplayHelper.parseLocationKey(key);
            if (matLoc == null) {
                return true;
            }
            return shouldPurgeTaggedDisplay(matLoc, display.getUniqueId());
        });
        if (removed[0] > 0 && plugin != null) {
            plugin.getLogger().info("[蒲团] 管理指令清理残留修炼显示实体: " + removed[0]);
        }
        return removed[0];
    }

    private void beginSession(Player player, Location loc, int state, Map<String, Object> data) {
        rememberPrayerMat(loc);
        setState(loc, state);
        MachineScriptHelper.setData(loc, PLAYER_UUID_KEY, player.getUniqueId().toString());
        playerActiveMat.put(player.getUniqueId(), loc);
        if (data != null) {
            sessionData.put(player.getUniqueId(), data);
        } else {
            Map<String, Object> loaded = PlayerAttributeStore.load(player.getName());
            if (loaded != null) {
                sessionData.put(player.getUniqueId(), loaded);
            }
        }
        maintainSit(player, loc);
        // 先刷全息再刷投影：全息清理只动 TextDisplay，不会误删珊瑚扇
        PuTuanDisplayHelper.updateHologram(loc, statusText(state));
        spawnProjection(loc);
        addMonitorLoc(loc);
        ensureMonitor();
    }

    private static void maintainSit(Player player, Location loc) {
        PuTuanSitHelper.maintain(player, loc);
    }

    private static String statusText(int state) {
        return switch (state) {
            case 1 -> "§a✦ 修炼中 ✦";
            case 2 -> "§d✦ 突破中 ✦";
            default -> "§7○ 空闲 ○";
        };
    }

    private void spawnProjection(Location loc) {
        String projId = MachineScriptHelper.getData(loc, PROJ_UUID_KEY);
        Entity existing = projId == null ? null : PuTuanDisplayHelper.findEntity(loc, projId);
        if (existing != null && !existing.isDead()) {
            return;
        }
        // UUID 失效时只清旧投影 BlockDisplay，保留「修炼中」全息
        PuTuanDisplayHelper.removeTaggedProjectionsAt(loc, null);
        BlockDisplay projection = DisplayReflectionHelper.spawnBlockDisplay(
            loc.clone().add(-0.5, 1.0, -0.5),
            DisplayReflectionHelper.parseBlockData(loadProjMaterial(loc))
        );
        PuTuanDisplayHelper.tag(projection, loc);
        MachineScriptHelper.setData(loc, PROJ_UUID_KEY, projection.getUniqueId().toString());
    }

    private void cleanup(Location loc, Player player) {
        cancelBreakTasks(loc);
        String storedPlayerId = MachineScriptHelper.getData(loc, PLAYER_UUID_KEY);
        // UUID + 附近 PDC 双清，避免崩溃后 UUID 丢失留下永久「修炼中」
        PuTuanDisplayHelper.removeStoredOrNearby(loc, PROJ_UUID_KEY);
        PuTuanDisplayHelper.removeStoredOrNearby(loc, HOLO_UUID_KEY);
        PuTuanDisplayHelper.removeTaggedAt(loc);
        MachineScriptHelper.removeData(loc, PLAYER_UUID_KEY);
        MachineScriptHelper.removeData(loc, PROJ_UUID_KEY);
        MachineScriptHelper.removeData(loc, HOLO_UUID_KEY);
        MachineScriptHelper.removeData(loc, PRAYER_MAT_KEY);
        if (player != null) {
            playerActiveMat.remove(player.getUniqueId());
            sessionData.remove(player.getUniqueId());
            monitorSpiritTicks.remove(player.getUniqueId());
        } else if (storedPlayerId != null) {
            try {
                UUID stored = UUID.fromString(storedPlayerId);
                playerActiveMat.remove(stored);
                sessionData.remove(stored);
                monitorSpiritTicks.remove(stored);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (player != null) {
            PuTuanSitHelper.release(player);
        }
        PuTuanSitHelper.cleanupAt(loc);
    }

    private void cancelBreakTasks(Location loc) {
        String key = locationKey(loc);
        Integer breakTask = breakTasks.remove(key);
        if (breakTask != null) {
            Bukkit.getScheduler().cancelTask(breakTask);
        }
        Integer tribTask = tribulationTasks.remove(key + "_trib");
        if (tribTask != null) {
            Bukkit.getScheduler().cancelTask(tribTask);
        }
    }

    private static String loadProjMaterial(Location loc) {
        try {
            Map<String, Object> cfg = puTuanConfig();
            if (cfg.isEmpty()) {
                return "minecraft:horn_coral_fan";
            }
            String id = MachineScriptHelper.getData(loc, PRAYER_MAT_KEY);
            if (id == null) {
                SlimefunItem sf = MachineScriptHelper.getSfItem(loc);
                id = sf == null ? "KOMUTECH_L_X_蒲团" : sf.getId();
            }
            Map<String, Object> itemCfg = KomutechJson.asMap(cfg.get(id));
            String material = KomutechJson.getString(itemCfg, "投影材质", null);
            if (material == null) {
                material = KomutechJson.getString(itemCfg, "projMaterial", "minecraft:horn_coral_fan");
            }
            return material;
        } catch (Exception ignored) {
            return "minecraft:horn_coral_fan";
        }
    }

    private static int tribulationCount(double nmax) {
        int count = 0;
        for (Map.Entry<Long, Integer> entry : TRIBULATION_THRESHOLDS.entrySet()) {
            if (nmax >= entry.getKey()) {
                count = entry.getValue();
            }
        }
        return count;
    }

    private static boolean isOnMat(Player player, Location matLoc) {
        return PuTuanSitHelper.isOnMat(player, matLoc);
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld().equals(b.getWorld())
            && a.getBlockX() == b.getBlockX()
            && a.getBlockY() == b.getBlockY()
            && a.getBlockZ() == b.getBlockZ();
    }

    private boolean containsMonitorLoc(Location loc) {
        for (Location stored : monitorLocs) {
            if (sameBlock(stored, loc)) {
                return true;
            }
        }
        return false;
    }

    private void addMonitorLoc(Location loc) {
        removeMonitorLoc(loc);
        monitorLocs.add(loc);
    }

    private void removeMonitorLoc(Location loc) {
        monitorLocs.removeIf(stored -> sameBlock(stored, loc));
    }

    private static String locationKey(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private static int getState(Location loc) {
        try {
            String raw = MachineScriptHelper.getData(loc, STATE_KEY);
            return raw == null ? 0 : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void setState(Location loc, int state) {
        MachineScriptHelper.setData(loc, STATE_KEY, String.valueOf(state));
    }

    private static double realmCoeff(Map<String, Object> data) {
        return CultivationMath.getRealmCoefficient(data);
    }

    private static double attrCoeff(String attr) {
        return switch (attr) {
            case "杂灵根" -> 0.1;
            case "四灵根" -> 0.3;
            case "三灵根" -> 0.5;
            case "双灵根" -> 0.8;
            case "单灵根" -> 1.0;
            case "天灵根" -> 1.2;
            case "变异灵根" -> 1.3;
            default -> 1.0;
        };
    }

    private static String trim(double value) {
        if (Double.isInfinite(value)) {
            return "無";
        }
        if (Math.floor(value) == value) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
