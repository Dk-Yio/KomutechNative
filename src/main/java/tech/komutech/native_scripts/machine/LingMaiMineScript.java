package tech.komutech.native_scripts.machine;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.MachineMenuGuard;

public final class LingMaiMineScript implements NativeScript, NativeLifecycleScript, KomutechMenuHandler {

    private static final int WORK_SLOT = 40;
    private static final int PICKAXE_SLOT = 53;
    private static final int[] OUTPUT_SLOTS = {46, 47, 48, 49, 50, 51, 52};
    private static final int COOLDOWN_MS = 500;
    private static final int DURABILITY_PER = 1;
    private static final int PICKAXE_DURABILITY = 2040;
    private static final int STACK_LIMIT = 64;

    private final int chance;
    private final String[] outputIds;
    private final Map<String, SlimefunItem> rewardCache = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public LingMaiMineScript(int chance, String... outputIds) {
        this.chance = chance;
        this.outputIds = outputIds;
    }

    private SlimefunItem rewardItem(String id) {
        return rewardCache.computeIfAbsent(id, SlimefunItem::getById);
    }

    @Override
    public void registerLifecycle(Plugin plugin) {
        KomutechMenuRouter.registerHandler(this);
    }

    @Override
    public boolean handles(InventoryView view) {
        if (MachineMenuGuard.isOwnMachine(view, "KOMUTECH_L_JQ_灵脉宝窟")
            || MachineMenuGuard.isOwnMachine(view, "KOMUTECH_L_JQ_灵脉晶辉宝窟")) {
            return true;
        }
        return MachineMenuGuard.titleContains(view, "灵脉宝窟", "灵脉晶辉宝窟", "靈脈寶窟", "靈脈晶輝寶窟");
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onClose".equals(functionName) && args[0] instanceof Player player) {
            cooldowns.remove(player.getUniqueId());
            return null;
        }
        if ("onOpen".equals(functionName) && args[0] instanceof Player player) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (handles(player.getOpenInventory())) {
                KomutechMenuRouter.bindInventory(top, this);
            }
            return null;
        }
        if ("onClick".equals(functionName) && args.length >= 4 && args[0] instanceof Player player) {
            int slot = (Integer) args[1];
            if (isAllowedSlot(slot)) {
                return null;
            }
            handleClick(player, slot, args[3]);
            return Boolean.FALSE;
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
        int rawSlot = event.getRawSlot();
        MachineMenuGuard.protectTopInventory(event, this::isAllowedSlot);
        if (event.isCancelled() && !isAllowedSlot(rawSlot)) {
            handleClick(player, rawSlot, event);
        }
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        if (handles(event.getView())) {
            MachineMenuGuard.protectTopInventoryDrag(event, this::isAllowedSlot);
        }
    }

    private boolean isAllowedSlot(int slot) {
        if (slot == PICKAXE_SLOT) {
            return true;
        }
        return MachineMenuGuard.isSlotIn(slot, OUTPUT_SLOTS);
    }

    private void handleClick(Player player, int slot, Object clickAction) {
        if (!checkCooldown(player)) {
            return;
        }
        if (slot == WORK_SLOT) {
            if (KomutechSupport.isRightClick(clickAction) && !KomutechSupport.isShiftClick(clickAction)) {
                autoMine(player);
                return;
            }
            if (KomutechSupport.isShiftClick(clickAction) && !KomutechSupport.isRightClick(clickAction)) {
                refill(player);
                return;
            }
        }
        if (slot >= 0 && slot <= 35) {
            mine(player, slot);
        }
    }

    private boolean checkCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            KomutechSupport.send(player, "&c操作太频繁，请稍后再试");
            return false;
        }
        cooldowns.put(player.getUniqueId(), now);
        return true;
    }

    private void refill(Player player) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack it = inv.getItem(slot);
            if (it == null || isGlass(it)) {
                inv.setItem(slot, stoneItem());
            }
        }
    }

    private void mine(Player player, int slot) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        ItemStack stack = inv.getItem(slot);
        if (stack == null || stack.getType() != Material.STONE) {
            return;
        }
        if (stack.getAmount() > 1) {
            stack.setAmount(stack.getAmount() - 1);
        } else {
            inv.setItem(slot, glassItem());
        }
        tryReward(player, inv);
    }

    private void autoMine(Player player) {
        try {
            Inventory inv = player.getOpenInventory().getTopInventory();
            ItemStack pick = inv.getItem(PICKAXE_SLOT);
            if (pick == null || pick.getType() != Material.NETHERITE_PICKAXE) {
                KomutechSupport.send(player, "§c需要下界合金镐！");
                return;
            }
            ItemMeta meta = pick.getItemMeta();
            if (meta == null) {
                return;
            }
            int curDmg = meta instanceof Damageable damageable ? damageable.getDamage() : 0;
            int remain = PICKAXE_DURABILITY - curDmg;
            if (remain < DURABILITY_PER) {
                inv.setItem(PICKAXE_SLOT, null);
                KomutechSupport.send(player, "§c工具耐久已耗尽！");
                return;
            }

            int mined = 0;
            boolean full = false;
            for (int slot = 0; slot <= 35; slot++) {
                if (full) {
                    break;
                }
                ItemStack stack = inv.getItem(slot);
                if (stack == null || stack.getType() != Material.STONE) {
                    continue;
                }
                int amount = stack.getAmount();
                int minedHere = 0;
                while (amount > 0) {
                    if ((mined + minedHere) * DURABILITY_PER >= remain) {
                        break;
                    }
                    minedHere++;
                    amount--;
                    if (ThreadLocalRandom.current().nextInt(100) < chance) {
                        ItemStack reward = randomReward();
                        if (reward != null && !placeReward(inv, reward)) {
                            full = true;
                            minedHere--;
                            amount++;
                            break;
                        }
                    }
                    if (full) {
                        break;
                    }
                }
                if (amount > 0) {
                    stack.setAmount(amount);
                } else {
                    inv.setItem(slot, glassItem());
                }
                mined += minedHere;
            }

            if (mined > 0 && meta instanceof Damageable damageable) {
                int newDmg = curDmg + mined * DURABILITY_PER;
                if (newDmg >= PICKAXE_DURABILITY) {
                    inv.setItem(PICKAXE_SLOT, null);
                } else {
                    damageable.setDamage(newDmg);
                    pick.setItemMeta(meta);
                }
            }
            if (full) {
                KomutechSupport.send(player, "§c输出槽位已满");
            }
        } catch (RuntimeException exception) {
            KomutechSupport.send(player, "§c自动挖掘出错！");
        }
    }

    private void tryReward(Player player, Inventory inv) {
        if (ThreadLocalRandom.current().nextInt(100) < chance) {
            ItemStack reward = randomReward();
            if (reward != null && !placeReward(inv, reward)) {
                KomutechSupport.send(player, "&c输出槽位已满");
            }
        }
    }

    private ItemStack randomReward() {
        if (outputIds.length == 0) {
            return null;
        }
        String id = outputIds[ThreadLocalRandom.current().nextInt(outputIds.length)];
        SlimefunItem sf = rewardItem(id);
        return sf == null ? null : sf.getItem().clone();
    }

    private boolean placeReward(Inventory inv, ItemStack reward) {
        String rid = getSfId(reward);
        for (int slot : OUTPUT_SLOTS) {
            ItemStack existing = inv.getItem(slot);
            if (existing == null || existing.getType().isAir()) {
                inv.setItem(slot, reward);
                return true;
            }
            if (existing.getType() == reward.getType()) {
                String eid = getSfId(existing);
                if ((eid != null && eid.equals(rid)) || (eid == null && rid == null)) {
                    if (existing.getAmount() < STACK_LIMIT) {
                        existing.setAmount(existing.getAmount() + 1);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String getSfId(ItemStack item) {
        SlimefunItem sf = SlimefunItem.getByItem(item);
        return sf == null ? null : sf.getId();
    }

    private static ItemStack stoneItem() {
        ItemStack stack = new ItemStack(Material.STONE, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName("§f点击挖掘");
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack glassItem() {
        ItemStack stack = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(" ");
        stack.setItemMeta(meta);
        return stack;
    }

    private static boolean isGlass(ItemStack item) {
        return item != null
            && item.getType() == Material.LIGHT_GRAY_STAINED_GLASS_PANE
            && item.hasItemMeta()
            && " ".equals(item.getItemMeta().getDisplayName());
    }
}
