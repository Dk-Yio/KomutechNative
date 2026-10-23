package tech.komutech.native_scripts.machine;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.MachineMenuGuard;
import tech.komutech.native_scripts.support.ParticleBudget;
import tech.origintech.native_scripts.support.MachineScriptHelper;

public final class UltimateSynthProcessorScript implements NativeScript, NativeLifecycleScript, KomutechMenuHandler {

    private static final int MAIN_PHASE_TICKS = 340;
    private static final int SHRINK_STEPS = 10;
    private static final int TOTAL_TICKS = MAIN_PHASE_TICKS + SHRINK_STEPS * 2 + 20;

    private static final Set<String> SYNTHESIZING = new HashSet<>();

    @Override
    public void registerLifecycle(Plugin plugin) {
        KomutechMenuRouter.registerHandler(this);
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onInventoryOpen(InventoryOpenEvent event) {
                if (!(event.getPlayer() instanceof Player player)) {
                    return;
                }
                if (handles(event.getView())) {
                    KomutechMenuRouter.bindInventory(event.getView().getTopInventory(), UltimateSynthProcessorScript.this);
                }
            }
        }, plugin);
    }

    @Override
    public boolean handles(InventoryView view) {
        return MachineMenuGuard.isUltimateSynthProcessorMenu(view);
    }

    /** ChestMenu 脚本返回值：false=取消点击（禁止拿走装饰），null=允许交互 */
    private static final Boolean BLOCK_CLICK = Boolean.FALSE;

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onOpen".equals(functionName) && args[0] instanceof Player player) {
            bindIfProcessorMenu(player);
            return null;
        }
        if ("onClick".equals(functionName) && args.length >= 4 && args[0] instanceof Player player) {
            int slot = (Integer) args[1];
            Object clickAction = args[3];
            Inventory top = player.getOpenInventory().getTopInventory();
            if (!handles(player.getOpenInventory())) {
                return null;
            }
            bindIfProcessorMenu(player);
            if (UltimateSynthSupport.isProcessorMaterialSlot(slot)) {
                return null;
            }
            if (slot == UltimateSynthSupport.WORK_SLOT
                && !KomutechSupport.isRightClick(clickAction)
                && !KomutechSupport.isShiftClick(clickAction)) {
                handleClick(player);
            }
            return BLOCK_CLICK;
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
        bindIfProcessorMenu(player);
        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        MachineMenuGuard.protectTopInventory(event, UltimateSynthSupport::isProcessorMaterialSlot);
        if (rawSlot >= 0 && rawSlot < topSize && event.isCancelled()
            && rawSlot == UltimateSynthSupport.WORK_SLOT
            && !KomutechSupport.isRightClick(event)
            && !KomutechSupport.isShiftClick(event)) {
            handleClick(player);
        }
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        if (!handles(event.getView())) {
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            bindIfProcessorMenu(player);
        }
        MachineMenuGuard.protectTopInventoryDrag(event, UltimateSynthSupport::isProcessorMaterialSlot);
    }

    private void bindIfProcessorMenu(Player player) {
        InventoryView view = player.getOpenInventory();
        if (!handles(view)) {
            return;
        }
        KomutechMenuRouter.bindInventory(view.getTopInventory(), this);
    }

    private Boolean handleClick(Player player) {
        Location procLoc = UltimateSynthSupport.getTargetProcessor(player);
        if (procLoc == null) {
            KomutechSupport.send(player, "§c请正对处理器机器！");
            return Boolean.TRUE;
        }
        Location coreLoc = procLoc.clone().add(0, -1, 0);
        if (!UltimateSynthSupport.CORE_ID.equals(getSfId(coreLoc))) {
            KomutechSupport.send(player, "§c核心机器无效！");
            return Boolean.TRUE;
        }
        if (UltimateSynthSupport.parseInt(MachineScriptHelper.getData(coreLoc, UltimateSynthSupport.DATA_STATE), 0) != 1) {
            KomutechSupport.send(player, "§c核心未激活！");
            return Boolean.TRUE;
        }
        UltimateSynthSupport.StructureCheck check = UltimateSynthSupport.checkCraftStructure(coreLoc);
        if (!check.valid()) {
            MachineScriptHelper.setData(coreLoc, UltimateSynthSupport.DATA_STATE, "0");
            KomutechSupport.send(player, "§c结构损坏，核心已关闭！");
            for (int i = 0; i < Math.min(5, check.errors().size()); i++) {
                KomutechSupport.send(player, "§7 - " + check.errors().get(i));
            }
            return Boolean.TRUE;
        }
        Object menu = MachineScriptHelper.getMenu(procLoc);
        if (!UltimateSynthSupport.preCheck(player, procLoc, menu)) {
            return Boolean.TRUE;
        }
        String key = locationKey(procLoc);
        if (SYNTHESIZING.contains(key)) {
            KomutechSupport.send(player, "§c机器正在合成中，请稍后再试！");
            return Boolean.TRUE;
        }
        player.closeInventory();
        startEffect(player, coreLoc, procLoc, key);
        return Boolean.TRUE;
    }

    private void startEffect(Player player, Location coreLoc, Location procLoc, String key) {
        SYNTHESIZING.add(key);
        World world = coreLoc.getWorld();
        if (world == null) {
            SYNTHESIZING.remove(key);
            return;
        }
        long originalTime = world.getTime();
        boolean originalStorm = world.hasStorm();
        boolean originalThunder = world.isThundering();
        world.setTime(18000);
        world.setStorm(true);
        world.setThundering(true);

        Location procCenter = procLoc.clone().add(0.5, 0.5, 0.5);
        List<Location> lingLocations = UltimateSynthSupport.lingLocations(coreLoc);
        List<Location> beaconLocations = UltimateSynthSupport.beaconLocations(coreLoc);
        Set<Integer> lightningTicks = new HashSet<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 9; i++) {
            lightningTicks.add(random.nextInt(MAIN_PHASE_TICKS));
        }

        Plugin plugin = KomutechSupport.plugin();
        if (plugin == null) {
            finish(player, coreLoc, procLoc, key, world, originalTime, originalStorm, originalThunder);
            return;
        }

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (tick < MAIN_PHASE_TICKS) {
                    if (tick % 5 == 0) {
                        spawnLingTrails(world, lingLocations, procCenter, random);
                    }
                    spawnMainTrails(world, procCenter, tick / 20.0);
                    if (lightningTicks.contains(tick) && !beaconLocations.isEmpty()) {
                        Location beacon = beaconLocations.get(random.nextInt(beaconLocations.size()));
                        world.strikeLightningEffect(beacon);
                        world.playSound(beacon, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1f);
                    }
                } else if (tick < MAIN_PHASE_TICKS + SHRINK_STEPS * 2) {
                    int step = (tick - MAIN_PHASE_TICKS) / 2;
                    if ((tick - MAIN_PHASE_TICKS) % 2 == 0) {
                        spawnShrinkTrails(world, procCenter, step, (MAIN_PHASE_TICKS + step) / 20.0);
                    }
                } else if (tick >= TOTAL_TICKS) {
                    spawnFinishBurst(world, coreLoc, random);
                    Location finalLoc = coreLoc.clone().add(0, 9, 0);
                    world.strikeLightningEffect(finalLoc);
                    world.playSound(finalLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 1f);
                    cancel();
                    finish(player, coreLoc, procLoc, key, world, originalTime, originalStorm, originalThunder);
                    return;
                }
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private static void spawnLingTrails(World world, List<Location> lingLocations, Location procCenter,
                                        ThreadLocalRandom random) {
        for (Location lingLoc : lingLocations) {
            Vector direction = procCenter.toVector().subtract(lingLoc.toVector());
            if (direction.lengthSquared() > 0) {
                direction.normalize();
            }
            int count = random.nextInt(3) + 1;
            ParticleBudget.spawnEndRod(world, lingLoc,
                direction.getX(), direction.getY(), direction.getZ(), 0.6, count);
        }
    }

    private static void spawnMainTrails(World world, Location procCenter, double time) {
        int points = ParticleBudget.scaleTrailPoints(16);
        for (int trail = 0; trail < 3; trail++) {
            for (int point = 0; point < points; point++) {
                Location pos = UltimateSynthSupport.trailPosition(procCenter, trail, time + point * 0.05);
                Color color = UltimateSynthSupport.trailColor(trail, point);
                ParticleBudget.spawnDust(world, pos, new DustOptions(color, 2.4f), 1);
            }
        }
    }

    private static void spawnShrinkTrails(World world, Location procCenter, int step, double time) {
        double progress = step / 10.0;
        double currentRadius = 6.0 * (1 - progress);
        int points = ParticleBudget.scaleTrailPoints(16);
        for (int trail = 0; trail < 3; trail++) {
            for (int point = 0; point < points; point++) {
                double t = time + point * 0.05;
                double baseAngle = t * 0.5 + trail * 2.0;
                double height = Math.sin(t * 0.8 + trail) * 2.0 * (1 - progress);
                Location loc = new Location(world,
                    procCenter.getX() + currentRadius * Math.cos(baseAngle),
                    procCenter.getY() + 2.0 + height,
                    procCenter.getZ() + currentRadius * Math.sin(baseAngle));
                Color color = UltimateSynthSupport.trailColor(trail, point);
                ParticleBudget.spawnDust(world, loc, new DustOptions(color, 2.4f), 1);
            }
        }
    }

    private static void spawnFinishBurst(World world, Location coreLoc, ThreadLocalRandom random) {
        for (int i = 0; i < 30; i++) {
            Location loc = coreLoc.clone().add(
                (random.nextDouble() - 0.5) * 2,
                random.nextDouble() * 4 + 2.5,
                (random.nextDouble() - 0.5) * 2
            );
            Color color = UltimateSynthSupport.trailColor(random.nextInt(3), i);
            ParticleBudget.spawnDust(world, loc, new DustOptions(color, 2.4f), 1);
        }
        world.playSound(coreLoc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2f, 1f);
    }

    private void finish(Player player, Location coreLoc, Location procLoc, String key, World world,
                        long originalTime, boolean originalStorm, boolean originalThunder) {
        world.setTime(originalTime);
        world.setStorm(originalStorm);
        world.setThundering(originalThunder);
        if (player.isOnline()) {
            UltimateSynthSupport.finalCraft(player, procLoc, MachineScriptHelper.getMenu(procLoc));
        }
        SYNTHESIZING.remove(key);
    }

    private static String getSfId(Location location) {
        return MachineScriptHelper.getSfItem(location) == null
            ? null : MachineScriptHelper.getSfItem(location).getId();
    }

    private static String locationKey(Location location) {
        return location.getWorld().getName() + "," + location.getBlockX() + ","
            + location.getBlockY() + "," + location.getBlockZ();
    }
}
