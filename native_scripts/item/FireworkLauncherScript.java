package tech.komutech.native_scripts.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;

public final class FireworkLauncherScript implements NativeScript {

    private static final long COOLDOWN_MS = 3000L;
    private static final Map<UUID, Long> COOLDOWNS = new ConcurrentHashMap<>();
    private static final FireworkEffect.Type[] SHAPES = {
        FireworkEffect.Type.BALL, FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.STAR,
        FireworkEffect.Type.BURST, FireworkEffect.Type.CREEPER
    };
    private static final Color[] COLORS = {
        Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW, Color.PURPLE, Color.ORANGE,
        Color.fromRGB(255, 192, 203), Color.AQUA, Color.LIME, Color.FUCHSIA, Color.WHITE
    };

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onUse".equals(functionName)) {
            return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
        }
        if ("onDisable".equals(functionName)) {
            COOLDOWNS.clear();
        }
        return null;
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        ItemStack item = ctx.item();
        String type = detectType(item);
        if (type == null) {
            KomutechSupport.send(player, "§c请使用正确的烟花发射器！");
            return null;
        }
        long now = System.currentTimeMillis();
        Long last = COOLDOWNS.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            double remain = (COOLDOWN_MS - (now - last)) / 1000.0;
            KomutechSupport.send(player, "§e请等待 " + String.format("%.1f", remain) + " 秒后再发射烟花！");
            return null;
        }
        COOLDOWNS.put(player.getUniqueId(), now);
        int count = switch (type) {
            case "压缩节日烟花" -> ThreadLocalRandom.current().nextInt(24, 33);
            case "节日烟花" -> 1;
            default -> 1;
        };
        launch(player, count);
        if (!"烟花发射器".equals(type)) {
            UseEvents.consumeOne(ctx);
            player.updateInventory();
        }
        KomutechSupport.send(player, "🎆 §a烟花发射成功！");
        return null;
    }

    private static String detectType(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return null;
        }
        String lore = String.join(" ", meta.getLore());
        if (lore.contains("压缩节日烟花")) {
            return "压缩节日烟花";
        }
        if (lore.contains("烟花发射器")) {
            return "烟花发射器";
        }
        if (lore.contains("节日烟花")) {
            return "节日烟花";
        }
        return null;
    }

    private static void launch(Player player, int count) {
        Location base = player.getLocation().clone().add(0, 1.6, 0);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            double distance = random.nextDouble(32 * 0.3, 32);
            double angle = random.nextDouble(Math.PI * 2);
            Location spawn = base.clone().add(Math.cos(angle) * distance, 5 + random.nextDouble(1.6, 1.8), Math.sin(angle) * distance);
            Firework firework = player.getWorld().spawn(spawn, Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            List<Color> colors = new ArrayList<>();
            int colorCount = random.nextInt(1, 6);
            for (int c = 0; c < colorCount; c++) {
                colors.add(COLORS[random.nextInt(COLORS.length)]);
            }
            meta.addEffect(FireworkEffect.builder()
                .with(SHAPES[random.nextInt(SHAPES.length)])
                .withColor(colors)
                .flicker(random.nextBoolean())
                .trail(random.nextBoolean())
                .build());
            meta.setPower(1);
            firework.setFireworkMeta(meta);
            firework.setVelocity(new Vector(0, random.nextDouble(0.20, 0.22), 0));
        }
    }
}
