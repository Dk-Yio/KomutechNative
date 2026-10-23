package tech.komutech.native_scripts.item;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Map;
import org.bukkit.inventory.ItemStack;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;
import tech.komutech.native_scripts.support.PlayerAttributeStore;

public final class MeritTicketScript implements NativeScript {

    private static final String ITEM_ID = "KOMUTECH_L_DJ_功德券";
    private static final int PER_TICKET = 1000;

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(this::handleUse).orElse(null);
    }

    private Object handleUse(UseEvents.Context ctx) {
        var player = ctx.player();
        TicketSlot ticket = findTicket(player);
        if (ticket == null) {
            KomutechSupport.actionBar(player, "§c请手持功德券使用！");
            return null;
        }
        Map<String, Object> data = PlayerAttributeStore.load(player.getName());
        if (data == null) {
            KomutechSupport.actionBar(player, "§c无法读取玩家属性数据！");
            return null;
        }
        int current = PlayerAttributeStore.getInt(data, "功德", 0);
        boolean consumeAll = player.isSneaking();
        int amount = consumeAll ? ticket.amount() : 1;
        if (amount < 1) {
            KomutechSupport.actionBar(player, "§c没有足够的功德券！");
            return null;
        }
        int add = amount * PER_TICKET;
        data.put("功德", current + add);
        if (!PlayerAttributeStore.save(player.getName(), data)) {
            KomutechSupport.actionBar(player, "§c保存数据失败！");
            return null;
        }
        int consumed = consumeTicket(player, ticket, consumeAll);
        KomutechSupport.actionBar(player, "§a功德 +" + add + " §7(消耗" + consumed + "张) §7| §f当前 " + (current + add));
        KomutechSupport.playBell(player);
        return null;
    }

    private static TicketSlot findTicket(org.bukkit.entity.Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (isTicket(main)) {
            return new TicketSlot("main", main, main.getAmount());
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (isTicket(off)) {
            return new TicketSlot("off", off, off.getAmount());
        }
        return null;
    }

    private static boolean isTicket(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        SlimefunItem sf = SlimefunItem.getByItem(item);
        return sf != null && ITEM_ID.equals(sf.getId());
    }

    private static int consumeTicket(org.bukkit.entity.Player player, TicketSlot ticket, boolean consumeAll) {
        var inv = player.getInventory();
        ItemStack item = "main".equals(ticket.slot()) ? inv.getItemInMainHand() : inv.getItemInOffHand();
        int consumed = consumeAll ? item.getAmount() : 1;
        if (item.getAmount() > consumed) {
            item.setAmount(item.getAmount() - consumed);
        } else if ("main".equals(ticket.slot())) {
            inv.setItemInMainHand(null);
        } else {
            inv.setItemInOffHand(null);
        }
        player.updateInventory();
        return consumed;
    }

    private record TicketSlot(String slot, ItemStack item, int amount) {
    }
}
