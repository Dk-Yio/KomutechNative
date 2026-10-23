package tech.komutech.native_scripts.cultivation;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import tech.komutech.native_scripts.item.XiDianZhuScript;
import tech.komutech.native_scripts.support.CultivationMath;
import tech.komutech.native_scripts.support.DisplayReflectionHelper;
import tech.komutech.native_scripts.support.PlayerAttributeStore;
import tech.komutech.native_scripts.support.ParticleBudget;
import tech.origintech.native_scripts.support.MachineScriptHelper;

public final class JianLingShiSupport {

    public static final String PREFIX = "KOMUTECH_L_X_JLS_";
    public static final String CORE_ID = "KOMUTECH_L_X_鉴灵石核心";
    public static final String GUI_TITLE = "§6鉴灵石核心";
    public static final List<String> TRIGGER_IDS = List.of("KOMUTECH_L_X_鉴灵石框架", "KOMUTECH_L_X_鉴灵石框架板");
    private static final String TRIGGER_FRAME = "KOMUTECH_L_X_鉴灵石框架";
    private static final String TRIGGER_FRAME_PLATE = "KOMUTECH_L_X_鉴灵石框架板";
    public static final List<String> ALLOWED_CLUSTERS = List.of(
        "KOMUTECH_L_DJ_JPLJ", "KOMUTECH_L_DJ_XPLJ", "KOMUTECH_L_DJ_ZPLJ", "KOMUTECH_L_DJ_SPLJ"
    );
    private static final List<String> ALL_ELEMENTS = List.of("金", "木", "水", "火", "土");
    private static final double MIN_QUALITY = 0.01;
    private static final int TOTAL_TICKS = 200;
    private static final int TRAIL_POINTS = 16;
    private static final double TRAIL_INIT_RADIUS = 3.0;
    private static final double TRAIL_RADIUS_AMP = 0.5;
    private static final double TRAIL_HEIGHT_AMP = 1.5;
    private static final double ROTATION_SPEED = 2.5;
    private static final double HEIGHT_OSCILLATION_SPEED = 0.8;
    private static final double RADIUS_OSCILLATION_SPEED = 0.3;
    private static final double CENTER_Y_OFFSET = 4;
    private static final float PARTICLE_BASE_SIZE = 1f;
    private static final float PARTICLE_SIZE_MULTIPLIER = 3f;
    private static final int PARTICLE_BASE_COUNT = 2;
    private static final int PARTICLE_COUNT_MULTIPLIER = 6;
    private static final Map<String, Color> BASE_COLORS = Map.of(
        "金", Color.fromRGB(255, 215, 0),
        "木", Color.fromRGB(0, 255, 0),
        "水", Color.fromRGB(0, 150, 255),
        "火", Color.fromRGB(255, 0, 0),
        "土", Color.fromRGB(139, 69, 19)
    );
    private static final Map<String, Color> MUTATED_COLORS = Map.of(
        "雷", Color.fromRGB(170, 0, 255),
        "风", Color.fromRGB(0, 255, 255),
        "冰", Color.fromRGB(0, 191, 255),
        "光", Color.fromRGB(255, 255, 255),
        "暗", Color.fromRGB(0, 0, 0)
    );
    private static final Map<String, String> MUTATION_MAP = Map.of(
        "雷", "金", "风", "木", "冰", "水", "光", "火", "暗", "土"
    );
    private static final int[][] CLUSTER_OFFSETS = {{-3, 2, -3}, {-3, 2, 3}, {3, 2, -3}, {3, 2, 3}};
    private static final StructureBlock[] STRUCTURE = buildStructure();

    private JianLingShiSupport() {
    }

    public record StructureBlock(int x, int y, int z, String material, String sfId, boolean dynamicCluster) {
    }

    public record ResolvedBlock(int x, int y, int z, String material, String sfId) {
    }

    public record StructureCheck(boolean valid, List<String> errors, List<ResolvedBlock> blocks) {
    }

    public record Rotated(int x, int z) {
    }

    public static Rotated rot(int x, int z, int dir) {
        return switch (dir) {
            case 1 -> new Rotated(-z, x);
            case 2 -> new Rotated(-x, -z);
            case 3 -> new Rotated(z, -x);
            default -> new Rotated(x, z);
        };
    }

    public static StructureBlock[] structure() {
        return STRUCTURE;
    }

    public static StructureCheck checkStructure(Location core, int dir) {
        World world = core.getWorld();
        List<String> errors = new ArrayList<>();
        List<ResolvedBlock> blocks = new ArrayList<>();
        for (StructureBlock block : STRUCTURE) {
            Rotated rotated = rot(block.x(), block.z(), dir);
            int x = core.getBlockX() + rotated.x();
            int y = core.getBlockY() + block.y();
            int z = core.getBlockZ() + rotated.z();
            Block worldBlock = world.getBlockAt(x, y, z);
            ResolvedBlock resolved = new ResolvedBlock(x, y, z, block.material(), block.sfId());
            blocks.add(resolved);
            if (block.dynamicCluster()) {
                SlimefunItem sf = MachineScriptHelper.getSfItem(worldBlock.getLocation());
                if (sf == null || !ALLOWED_CLUSTERS.contains(sf.getId())) {
                    errors.add("位置 (" + (x - core.getBlockX()) + "," + (y - core.getBlockY()) + ","
                        + (z - core.getBlockZ()) + ") 需要聚灵簇");
                    return new StructureCheck(false, errors, blocks);
                }
                resolved = new ResolvedBlock(x, y, z, block.material(), sf.getId());
                blocks.set(blocks.size() - 1, resolved);
            } else {
                if (!worldBlock.getType().name().equalsIgnoreCase(block.material())) {
                    errors.add("位置 (" + (x - core.getBlockX()) + "," + (y - core.getBlockY()) + ","
                        + (z - core.getBlockZ()) + ") 需要 " + block.material());
                    return new StructureCheck(false, errors, blocks);
                }
                if (block.sfId() != null) {
                    SlimefunItem sf = MachineScriptHelper.getSfItem(worldBlock.getLocation());
                    if (sf == null || !block.sfId().equals(sf.getId())) {
                        errors.add("位置 (" + (x - core.getBlockX()) + "," + (y - core.getBlockY()) + ","
                            + (z - core.getBlockZ()) + ") 需要 " + block.sfId());
                        return new StructureCheck(false, errors, blocks);
                    }
                }
            }
        }
        return new StructureCheck(errors.isEmpty(), errors, blocks);
    }

    public static void markTriggers(Location core, int dir, String coreKey) {
        StructureCheck check = checkStructure(core, dir);
        for (ResolvedBlock block : check.blocks()) {
            if (block.sfId() != null && TRIGGER_IDS.contains(block.sfId())) {
                MachineScriptHelper.setData(new Location(core.getWorld(), block.x(), block.y(), block.z()),
                    PREFIX + "core", coreKey);
            }
        }
    }

    public static void clearTriggers(Location core, int dir) {
        StructureCheck check = checkStructure(core, dir);
        for (ResolvedBlock block : check.blocks()) {
            if (block.sfId() != null && TRIGGER_IDS.contains(block.sfId())) {
                MachineScriptHelper.setData(new Location(core.getWorld(), block.x(), block.y(), block.z()),
                    PREFIX + "core", null);
            }
        }
    }

    public static void consumeClusters(Location core, Player player) {
        World world = core.getWorld();
        for (int[] offset : CLUSTER_OFFSETS) {
            Block block = world.getBlockAt(core.getBlockX() + offset[0], core.getBlockY() + offset[1], core.getBlockZ() + offset[2]);
            SlimefunItem sf = MachineScriptHelper.getSfItem(block.getLocation());
            if (sf == null || !ALLOWED_CLUSTERS.contains(sf.getId())) {
                continue;
            }
            boolean consume = switch (sf.getId()) {
                case "KOMUTECH_L_DJ_XPLJ" -> true;
                case "KOMUTECH_L_DJ_ZPLJ" -> Math.random() < 0.6;
                case "KOMUTECH_L_DJ_SPLJ" -> Math.random() < 0.3;
                default -> false;
            };
            if (consume) {
                block.setType(Material.AIR);
            }
        }
    }

    public static Map<String, Object> rollAttributes(Map<String, Object> directed) {
        Map<String, Object> data;
        if (directed != null && PlayerAttributeStore.getBoolean(directed, "定向", false)) {
            data = PlayerAttributeStore.deepCopy(directed);
            data.remove("定向");
            completeLinggenFields(data);
        } else {
            data = new LinkedHashMap<>();
            int count;
            double rand = Math.random();
            if (rand < 0.2) {
                count = 1;
            } else if (rand < 0.4) {
                count = 2;
            } else if (rand < 0.6) {
                count = 3;
            } else if (rand < 0.8) {
                count = 4;
            } else {
                count = 5;
            }
            List<String> picked = new ArrayList<>();
            while (picked.size() < count) {
                String element = ALL_ELEMENTS.get((int) (Math.random() * ALL_ELEMENTS.size()));
                if (!picked.contains(element)) {
                    picked.add(element);
                }
            }
            picked.sort(java.util.Comparator.comparingInt(ALL_ELEMENTS::indexOf));
            double total = Math.round((0.1 + Math.random() * 0.9) * 100.0) / 100.0;
            List<Double> qualityList = genQuality(picked, total);
            Map<String, Object> qualities = new LinkedHashMap<>();
            for (int i = 0; i < picked.size(); i++) {
                qualities.put(picked.get(i), qualityList.get(i));
            }
            data.put("灵根", String.join("、", picked));
            data.put("灵根品质", qualities);
            data.put("总品质", total);
            data.put("灵根属性", CultivationMath.computeLinggenAttr(picked, total));
        }
        data.put("修为", "『引气入体』");
        data.put("灵气", "0/100");
        data.put("灵力", "100/100+0");
        data.put("功德", 0);
        data.put("煞气", 0);
        data.put("悟性", Math.round((1 + Math.random() * 4) * 100.0) / 100.0);
        data.put("根骨", (int) (Math.floor(Math.random() * 10) + 1));
        data.put("血量", (int) (Math.floor(Math.random() * 10) + 1));
        data.put("攻击力", (int) (Math.floor(Math.random() * 10) + 1));
        data.put("防御力", (int) (Math.floor(Math.random() * 10) + 1));
        data.put("速度", (int) (Math.floor(Math.random() * 10) + 1));
        data.put("灵识", 0);
        data.put("灵气获取", 0);
        data.put("灵气获取_实际", 100.0);
        data.put("属性点", 3);
        XiDianZhuScript.captureBase(data);
        return data;
    }

    public static void completeLinggenFields(Map<String, Object> data) {
        List<String> elements = CultivationMath.splitLinggen(PlayerAttributeStore.getString(data, "灵根", ""));
        if (elements.isEmpty()) {
            return;
        }
        double total = PlayerAttributeStore.getDouble(data, "总品质", 0.01);
        if (!data.containsKey("灵根品质")) {
            Map<String, Object> qualities = new LinkedHashMap<>();
            List<Double> qualityList = genQuality(elements, total);
            for (int i = 0; i < elements.size(); i++) {
                qualities.put(elements.get(i), qualityList.get(i));
            }
            data.put("灵根品质", qualities);
        }
        String attr = PlayerAttributeStore.getString(data, "灵根属性", "");
        if (attr.isBlank() || "未知".equals(attr)) {
            data.put("灵根属性", CultivationMath.computeLinggenAttr(elements, total));
        }
    }

    public static boolean completeLinggenFieldsIfNeeded(Map<String, Object> data) {
        if (!data.containsKey("灵根")) {
            return false;
        }
        boolean needed = !data.containsKey("灵根品质")
            || !data.containsKey("灵根属性")
            || "未知".equals(PlayerAttributeStore.getString(data, "灵根属性", ""));
        if (!needed) {
            return false;
        }
        completeLinggenFields(data);
        return true;
    }

    public static List<String> elementsFromData(Map<String, Object> data) {
        String linggen = PlayerAttributeStore.getString(data, "灵根", "");
        if (linggen.isBlank()) {
            return List.of();
        }
        return List.of(linggen.split("、"));
    }

    public static List<Double> qualitiesFromData(Map<String, Object> data) {
        List<String> elements = elementsFromData(data);
        if (elements.isEmpty()) {
            return List.of();
        }
        Object raw = data.get("灵根品质");
        if (!(raw instanceof Map<?, ?> qualities)) {
            return elements.stream().map(element -> MIN_QUALITY).toList();
        }
        List<Double> values = new ArrayList<>();
        for (String element : elements) {
            Object value = qualities.get(element);
            values.add(value instanceof Number number ? number.doubleValue() : MIN_QUALITY);
        }
        return values;
    }

    public static void startEffect(Plugin plugin, Location coreLoc, List<String> activeElements,
                                   List<Double> qualityValues, Runnable onComplete) {
        World world = coreLoc.getWorld();
        if (world == null) {
            onComplete.run();
            return;
        }
        Map<String, Double> qualityMap = new LinkedHashMap<>();
        for (int i = 0; i < activeElements.size(); i++) {
            qualityMap.put(activeElements.get(i), qualityValues.get(i));
        }
        List<ElementTrail> trails = new ArrayList<>();
        for (int idx = 0; idx < ALL_ELEMENTS.size(); idx++) {
            String element = ALL_ELEMENTS.get(idx);
            String mutatedElement = findMutatedElement(activeElements, element);
            boolean active = activeElements.contains(element);
            double quality = active
                ? qualityMap.getOrDefault(element, 0.0)
                : (mutatedElement != null ? qualityMap.getOrDefault(mutatedElement, 0.0) : 0.0);
            float size = PARTICLE_BASE_SIZE + PARTICLE_SIZE_MULTIPLIER * (float) quality;
            int count = Math.max(1, (int) Math.floor(PARTICLE_BASE_COUNT + PARTICLE_COUNT_MULTIPLIER * quality + 0.5));
            double angleOffset = idx * 2 * Math.PI / ALL_ELEMENTS.size();
            Color baseColor = BASE_COLORS.get(element);
            Color mutatedColor = mutatedElement != null ? MUTATED_COLORS.get(mutatedElement) : null;
            trails.add(new ElementTrail(active || mutatedElement != null, size, count, angleOffset, baseColor,
                mutatedColor, quality));
        }
        world.playSound(coreLoc.clone().add(0, 4, 0), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
        Location center = coreLoc.clone().add(0.5, 0.5, 0.5);
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (tick > TOTAL_TICKS) {
                    world.playSound(coreLoc.clone().add(0, 4, 0), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1f);
                    cancel();
                    onComplete.run();
                    return;
                }
                if (!ParticleBudget.canRunHeavyEffect(world)) {
                    tick++;
                    return;
                }
                double progress = tick / (double) TOTAL_TICKS;
                double time = tick / 20.0;
                for (ElementTrail trail : trails) {
                    double radius = TRAIL_INIT_RADIUS * (1 - progress);
                    float size = trail.size();
                    if (trail.effectiveQuality() == 0) {
                        radius *= (1 - progress);
                    }
                    if (radius < 0.05) {
                        continue;
                    }
                    double height = CENTER_Y_OFFSET
                        + Math.sin(time * HEIGHT_OSCILLATION_SPEED + trail.angleOffset()) * TRAIL_HEIGHT_AMP * (1 - progress);
                    Color color = trail.baseColor();
                    if (trail.mutatedColor() != null) {
                        double colorProgress = progress > 0.1 ? Math.min(1, (progress - 0.1) / 0.15) : 0;
                        color = interpolateColor(trail.baseColor(), trail.mutatedColor(), colorProgress);
                    }
                    int trailPoints = ParticleBudget.scaleTrailPoints(TRAIL_POINTS);
                    for (int point = 0; point < trailPoints; point++) {
                        double offset = point * 0.05;
                        double trailTime = time + offset;
                        double currentRadius = radius
                            + Math.sin(trailTime * RADIUS_OSCILLATION_SPEED) * TRAIL_RADIUS_AMP * (1 - progress);
                        double currentHeight = height
                            + Math.sin(trailTime * HEIGHT_OSCILLATION_SPEED) * TRAIL_HEIGHT_AMP * (1 - progress) * 0.5;
                        double angle = trailTime * ROTATION_SPEED + trail.angleOffset();
                        Location particle = new Location(world,
                            center.getX() + currentRadius * Math.cos(angle),
                            coreLoc.getY() + currentHeight,
                            center.getZ() + currentRadius * Math.sin(angle));
                        int count = trail.count();
                        if (trail.effectiveQuality() == 0) {
                            count = Math.max(1, (int) Math.floor(count * (1 - progress)));
                        }
                        DustOptions dust = new DustOptions(color, size);
                        ParticleBudget.spawnDust(world, particle, dust, count);
                    }
                }
                int endRodCount = 3 + (int) Math.floor(Math.random() * 4);
                for (int i = 0; i < endRodCount; i++) {
                    Location particle = center.clone().add(
                        (Math.random() - 0.5) * 8,
                        Math.random() * 5,
                        (Math.random() - 0.5) * 8
                    );
                    ParticleBudget.spawnEndRod(world, particle,
                        (Math.random() - 0.5) * 0.2, 0.1 + Math.random() * 0.4, (Math.random() - 0.5) * 0.2, 0.5, 1);
                }
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private static String findMutatedElement(List<String> activeElements, String baseElement) {
        for (String active : activeElements) {
            if (baseElement.equals(MUTATION_MAP.get(active))) {
                return active;
            }
        }
        return null;
    }

    private static List<Double> genQuality(List<String> elements, double total) {
        double min = MIN_QUALITY * elements.size();
        if (total < min) {
            total = min;
        }
        double remaining = total - min;
        double[] weights = new double[elements.size()];
        double sum = 0;
        for (int i = 0; i < elements.size(); i++) {
            weights[i] = Math.random();
            sum += weights[i];
        }
        List<Double> qualities = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            double quality = MIN_QUALITY + (weights[i] / sum) * remaining;
            qualities.add(Math.round(quality * 100.0) / 100.0);
        }
        return qualities;
    }

    private static Color interpolateColor(Color from, Color to, double t) {
        return Color.fromRGB(
            (int) Math.floor(from.getRed() + (to.getRed() - from.getRed()) * t),
            (int) Math.floor(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
            (int) Math.floor(from.getBlue() + (to.getBlue() - from.getBlue()) * t)
        );
    }

    private record ElementTrail(
        boolean active,
        float size,
        int count,
        double angleOffset,
        Color baseColor,
        Color mutatedColor,
        double effectiveQuality
    ) {
    }
    public static void showHologram(Plugin plugin, Location coreLoc, Player player, String linggenStr) {
        World world = coreLoc.getWorld();
        if (world == null) {
            return;
        }
        Location playerLoc = player.getLocation();
        double dx = playerLoc.getX() - coreLoc.getX();
        double dz = playerLoc.getZ() - coreLoc.getZ();
        double ox = 0;
        double oz = 0;
        if (Math.abs(dx) >= Math.abs(dz)) {
            ox = dx >= 0 ? 3 : -3;
        } else {
            oz = dz >= 0 ? 3 : -3;
        }
        Location pos = coreLoc.clone().add(ox, 4, oz);
        TextDisplay display = DisplayReflectionHelper.spawnTextDisplay(pos, "§f§l你的灵根为：", 2f);
        String prefix = "§f§l你的灵根为：";
        String suffix = "§6§l" + linggenStr;
        new BukkitRunnable() {
            int index = 0;

            @Override
            public void run() {
                if (display.isDead()) {
                    cancel();
                    return;
                }
                if (index >= suffix.length()) {
                    cancel();
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (!display.isDead()) {
                            display.remove();
                        }
                    }, 200L);
                    return;
                }
                display.setText(prefix + suffix.substring(0, index + 1));
                index++;
            }
        }.runTaskTimer(plugin, 0L, 3L);
    }

    public static String locationKey(Location location) {
        return location.getWorld().getName() + "," + location.getBlockX() + ","
            + location.getBlockY() + "," + location.getBlockZ();
    }

    private static StructureBlock[] buildStructure() {
        List<StructureBlock> blocks = new ArrayList<>();
        blocks.add(new StructureBlock(0, 0, 0, "SHROOMLIGHT", CORE_ID, false));
        for (int[] offset : CLUSTER_OFFSETS) {
            blocks.add(new StructureBlock(offset[0], offset[1], offset[2], "AIR", null, true));
        }
        for (int y = 3; y <= 5; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0) {
                        blocks.add(new StructureBlock(0, y, 0, "BLUE_STAINED_GLASS", null, false));
                    } else if (Math.abs(x) == 1 && Math.abs(z) == 1) {
                        blocks.add(new StructureBlock(x, y, z, "LIGHT_BLUE_STAINED_GLASS_PANE", TRIGGER_FRAME_PLATE, false));
                    } else {
                        blocks.add(new StructureBlock(x, y, z, "LIGHT_BLUE_STAINED_GLASS", TRIGGER_FRAME, false));
                    }
                }
            }
        }
        addPlatformRow(blocks, -3, new String[] {
            "ANDESITE_WALL", "POLISHED_ANDESITE_SLAB", "POLISHED_ANDESITE_STAIRS", "POLISHED_ANDESITE_STAIRS",
            "POLISHED_ANDESITE_STAIRS", "POLISHED_ANDESITE_SLAB", "ANDESITE_WALL"
        });
        addPlatformRow(blocks, -2, new String[] {
            "POLISHED_ANDESITE_SLAB", "DIORITE", "ANDESITE", "POLISHED_ANDESITE", "ANDESITE", "DIORITE", "POLISHED_ANDESITE_SLAB"
        });
        addPlatformRow(blocks, -1, new String[] {
            "POLISHED_ANDESITE_STAIRS", "ANDESITE", "ANDESITE_SLAB", "POLISHED_ANDESITE_STAIRS", "ANDESITE_SLAB", "ANDESITE",
            "POLISHED_ANDESITE_STAIRS"
        });
        addPlatformRow(blocks, 0, new String[] {
            "POLISHED_ANDESITE_STAIRS", "POLISHED_ANDESITE", "POLISHED_ANDESITE_STAIRS", null, "POLISHED_ANDESITE_STAIRS",
            "POLISHED_ANDESITE", "POLISHED_ANDESITE_STAIRS"
        });
        addPlatformRow(blocks, 1, new String[] {
            "POLISHED_ANDESITE_STAIRS", "ANDESITE", "ANDESITE_SLAB", "POLISHED_ANDESITE_STAIRS", "ANDESITE_SLAB", "ANDESITE",
            "POLISHED_ANDESITE_STAIRS"
        });
        addPlatformRow(blocks, 2, new String[] {
            "POLISHED_ANDESITE_SLAB", "DIORITE", "ANDESITE", "POLISHED_ANDESITE", "ANDESITE", "DIORITE", "POLISHED_ANDESITE_SLAB"
        });
        addPlatformRow(blocks, 3, new String[] {
            "ANDESITE_WALL", "POLISHED_ANDESITE_SLAB", "POLISHED_ANDESITE_STAIRS", "POLISHED_ANDESITE_STAIRS",
            "POLISHED_ANDESITE_STAIRS", "POLISHED_ANDESITE_SLAB", "ANDESITE_WALL"
        });
        blocks.add(new StructureBlock(-3, 1, -3, "CHISELED_TUFF", null, false));
        blocks.add(new StructureBlock(-3, 1, 3, "CHISELED_TUFF", null, false));
        blocks.add(new StructureBlock(3, 1, -3, "CHISELED_TUFF", null, false));
        blocks.add(new StructureBlock(3, 1, 3, "CHISELED_TUFF", null, false));
        addLanternRing(blocks, 2);
        addLanternRing(blocks, 6);
        blocks.add(new StructureBlock(0, 7, 0, "GRINDSTONE", null, false));
        blocks.add(new StructureBlock(0, 8, 0, "END_ROD", null, false));
        return blocks.toArray(StructureBlock[]::new);
    }

    private static void addPlatformRow(List<StructureBlock> blocks, int z, String[] materials) {
        int[] xs = {-3, -2, -1, 0, 1, 2, 3};
        for (int i = 0; i < xs.length; i++) {
            if (materials[i] == null) {
                continue;
            }
            blocks.add(new StructureBlock(xs[i], 0, z, materials[i], null, false));
        }
    }

    private static void addLanternRing(List<StructureBlock> blocks, int y) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) {
                    blocks.add(new StructureBlock(0, y, 0, "SEA_LANTERN", null, false));
                } else {
                    blocks.add(new StructureBlock(x, y, z, "POLISHED_ANDESITE_STAIRS", null, false));
                }
            }
        }
    }
}
