package tech.komutech.native_scripts.item;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.AttributeApplier;
import tech.komutech.native_scripts.support.CultivationMath;
import tech.komutech.native_scripts.cultivation.JianLingShiSupport;
import tech.komutech.native_scripts.support.KomutechChatInput;
import tech.komutech.native_scripts.support.KomutechPaths;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;
import tech.komutech.native_scripts.support.PlayerAttributeStore;

public final class ZeLingZhuScript implements NativeScript {

    private static final String ITEM_ID = "KOMUTECH_L_DJ_擇靈珠";
    private static final Pattern SPLIT = Pattern.compile("[、，\\s]+");

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        ItemStack item = ctx.item();
        SlimefunItem sf = SlimefunItem.getByItem(item);
        if (sf == null || !ITEM_ID.equals(sf.getId())) {
            KomutechSupport.send(player, "§c请手持擇靈珠使用！");
            return null;
        }
        if (player.isSneaking()) {
            KomutechSupport.send(player, "§a请输入定向灵根（用顿号或空格分隔），输入 cancel 取消：");
            KomutechChatInput.waitFor(player, input -> {
                if (!player.isOnline()) {
                    return;
                }
                if ("cancel".equalsIgnoreCase(input)) {
                    KomutechSupport.send(player, "§c已取消");
                    return;
                }
                List<String> elements = parseElements(input);
                if (elements.isEmpty()) {
                    KomutechSupport.send(player, "§c无有效元素");
                    return;
                }
                ItemStack hand = player.getInventory().getItemInMainHand();
                ItemMeta meta = hand.getItemMeta();
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.removeIf(line -> line.contains("定向灵根:"));
                lore.add("§7定向灵根: §e" + String.join("、", elements));
                meta.setLore(lore);
                hand.setItemMeta(meta);
                KomutechSupport.send(player, "§a定向灵根已记录到物品上！");
            });
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore()) {
            KomutechSupport.send(player, "§c该物品尚未设置定向灵根，请蹲下右键设置！");
            return null;
        }
        List<String> target = null;
        for (String line : meta.getLore()) {
            if (line.contains("定向灵根:")) {
                String raw = line.split(":")[1].trim().replaceAll("§.", "");
                target = List.of(raw.split("、"));
                break;
            }
        }
        if (target == null || target.isEmpty()) {
            KomutechSupport.send(player, "§c未检测到有效的定向灵根，请蹲下右键设置！");
            return null;
        }
        if (Files.exists(KomutechPaths.playerAttributes().resolve("[" + player.getName() + "].json"))) {
            KomutechSupport.send(player, "§c你已经鉴灵了，无法使用擇靈珠");
            return null;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        hand.setAmount(hand.getAmount() - 1);
        if (hand.getAmount() <= 0) {
            player.getInventory().setItemInMainHand(null);
        }
        double quality = Math.round((0.80 + Math.random() * 0.19) * 100.0) / 100.0;
        var directed = new java.util.HashMap<String, Object>();
        directed.put("定向", true);
        directed.put("灵根", String.join("、", target));
        directed.put("总品质", quality);
        Map<String, Object> data = JianLingShiSupport.rollAttributes(directed);
        if (PlayerAttributeStore.save(player.getName(), data)) {
            AttributeApplier.applyStoredAttributes(player);
            KomutechSupport.send(player, "§a定向成功！灵根：" + String.join("、", target)
                + "，总品质：" + String.format("%.2f", quality)
                + "，灵根属性：" + PlayerAttributeStore.getString(data, "灵根属性", "未知"));
        } else {
            KomutechSupport.send(player, "§c定向失败，请联系管理员");
        }
        return null;
    }

    private static List<String> parseElements(String input) {
        List<String> raw = SPLIT.matcher(input).find()
            ? List.of(SPLIT.split(input))
            : input.codePoints().mapToObj(cp -> String.valueOf((char) cp)).toList();
        List<String> valid = new ArrayList<>();
        for (String element : raw) {
            if (element.isBlank()) {
                continue;
            }
            if (CultivationMath.VALID_LINGGEN.contains(element)) {
                valid.add(element);
            }
        }
        return valid;
    }
}
