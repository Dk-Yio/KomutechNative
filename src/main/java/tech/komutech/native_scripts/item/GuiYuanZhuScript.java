package tech.komutech.native_scripts.item;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.entity.Player;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.AttributeApplier;
import tech.komutech.native_scripts.support.KomutechChatInput;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;
import tech.komutech.native_scripts.support.PlayerAttributeStore;

public final class GuiYuanZhuScript implements NativeScript {

    private static final Set<String> PENDING = new HashSet<>();

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        Player player = ctx.player();
        String name = player.getName();
        if (PENDING.contains(name)) {
            return null;
        }
        if (!PlayerAttributeStore.exists(name)) {
            KomutechSupport.send(player, "§c你没有玩家属性数据，无法使用归元珠。");
            return null;
        }
        PENDING.add(name);
        AttributeApplier.clearSwitchable(player);
        KomutechSupport.send(player, "§c警告：此操作将永久删除你的玩家属性数据！");
        KomutechSupport.send(player, "§c输入 \"确认重置\" 以继续，输入 cancel 取消:");
        KomutechChatInput.waitFor(player, input -> {
            PENDING.remove(name);
            if ("cancel".equalsIgnoreCase(input.trim())) {
                KomutechSupport.send(player, "§a已取消。");
                return;
            }
            if (!"确认重置".equals(input.replaceAll("§.", "").trim())) {
                KomutechSupport.send(player, "§c输入不匹配，操作取消。");
                return;
            }
            PlayerAttributeStore.delete(name);
            KomutechSupport.send(player, "§a玩家属性已重置，请重新进行灵根鉴定。");
        });
        return null;
    }
}
