package tech.komutech.native_scripts.menu;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.komutech.native_scripts.support.MenuGuiHelper;

public final class GuideMenuScript implements NativeLifecycleScript, KomutechMenuHandler {

    private static final String MAIN_TITLE = "§b§l✨ 口木科技教程 ✨";
    private static final String TITLE_PREFIX = "§b§l✨ ";
    private static final int[] BORDER = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47, 48, 50, 51, 52, 53};
    private static final int[] REWARD_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private static final String[][] CATEGORIES = {
        {"rumen", "§a✦ 入门篇", "AMETHYST_CLUSTER", "10"},
        {"shenwu", "§b✦ 深悟篇", "ENDER_PEARL", "11"},
        {"dacheng", "§6✦ 大成篇", "ENDER_EYE", "12"},
        {"lingzhi", "§2✦ 灵植篇", "BAMBOO", "13"},
        {"lingzhang", "§d✦ 灵杖篇", "BLAZE_ROD", "14"},
        {"juanzhou", "§5✦ 卷轴篇", "BOOK", "15"}
    };

    /** 与旧 JS 一致：权重合计 86，其余 14 落空 */
    private static final Object[][] REWARDS = {
        {"KOMUTECH_L_LZ_YG", 40},
        {"KOMUTECH_L_DJ_XPLS", 40},
        {"KOMUTECH_L_DJ_擇靈珠", 1},
        {"KOMUTECH_L_GJ_印物笺", 5}
    };
    private static final String MYSTERY_NAME = "§e✨ 神秘奖励 ✨";
    private static final double MYSTERY_SPAWN_CHANCE = 50.0;

    private record CategoryTip(String name, String icon, List<String> lore) {
    }

    private static final Map<String, List<CategoryTip>> CATEGORY_TIPS = buildCategoryTips();

    private static Map<String, List<CategoryTip>> buildCategoryTips() {
        Map<String, List<CategoryTip>> tips = new HashMap<>();
        tips.put("rumen", List.of(
            new CategoryTip("§6灵石获取", "AMETHYST_SHARD",
                List.of("§7• 灵脉宝窟/矿井开采灵石原矿获得下品灵石", "§7• 灵矿提取台处理原矿，灵能提炼器转换", "§7• 上品/极品需功德券辅助合成")),
            new CategoryTip("§6矿物获取", "IRON_INGOT",
                List.of("§7• 灵石原矿开采", "§7• 矿石原胚/杂矿概率获得", "§7• 玄铁、寒铁等由原胚在灵能提取器随机产出")),
            new CategoryTip("§6灵脉宝窟", "FURNACE",
                List.of("§7• 右键打开，点击石头开采", "§7• 下界合金镐可一键挖掘", "§7• 产出：灵石原矿、下品灵石、杂矿")),
            new CategoryTip("§6灵脉晶辉宝窟", "DIAMOND",
                List.of("§7• 同上，产出各类宝石璞胚"))
        ));
        tips.put("shenwu", List.of(
            new CategoryTip("§6法则概述", "ENCHANTED_BOOK",
                List.of("§7• 一丝、些许、完整三个品级", "§7• 涵盖金木水火土冰风雷机关炼金声音空间杀戮")),
            new CategoryTip("§6一丝法则", "GOLD_NUGGET",
                List.of("§7• 悟道石+材料+南方法则承载器", "§7• 悟道成功在承载器生成")),
            new CategoryTip("§6些许法则", "GOLD_INGOT",
                List.of("§7• 明悟石按顺序放入1-36个一丝法则")),
            new CategoryTip("§6完整法则", "GOLD_BLOCK",
                List.of("§7• 破妄石按顺序放入1-36个些许法则")),
            new CategoryTip("§6高级法则", "NETHER_STAR",
                List.of("§7• 五行灵曦：15个各完整五行法则合成", "§7• 五行祖炁：1-36个灵曦合成"))
        ));
        tips.put("dacheng", List.of(
            new CategoryTip("§6身外身简介", "PLAYER_HEAD", List.of("§7可绑定分身，自动化关键")),
            new CategoryTip("§6无垢坯", "CLAY_BALL",
                List.of("§7• 上品一芥乾坤合成清灵壤+元灵髓", "§7• 炼枢造生仪合成无垢坯")),
            new CategoryTip("§6蕴灵身", "BLAZE_POWDER",
                List.of("§7• 手持无垢坯右键使用", "§7• 消耗99%生命绑定自身")),
            new CategoryTip("§6进阶", "BEACON", List.of("§7• 蕴灵身可合成循工偶、百巧工等"))
        ));
        tips.put("lingzhi", List.of(
            new CategoryTip("§6种子获取", "WHEAT_SEEDS",
                List.of("§7• 桃、松、玉干、棉花、麻种子在下品一芥乾坤合成")),
            new CategoryTip("§6种植环境", "GRASS_BLOCK",
                List.of("§7• 普通作物需耕地", "§7• 树木需草方块，周围留空", "§7• 玉干草方块即可")),
            new CategoryTip("§6生长收获", "BAMBOO",
                List.of("§7• 成熟后破坏种子格自动掉落", "§7• 稀释灵液可增加玉干产量")),
            new CategoryTip("§6产物用途", "OAK_LOG",
                List.of("§7• 玉干→褚纸、玄铁工具", "§7• 桃/松木→木材", "§7• 棉花→一袋棉花→布", "§7• 麻→布"))
        ));
        tips.put("lingzhang", List.of(
            new CategoryTip("§6灵杖简介", "BLAZE_ROD",
                List.of("§7• 黄阶1、玄阶1.05、地阶1.10、天阶1.15、仙阶1.20倍率", "§7• 品阶差距已大幅压缩，主要靠基础属性拉开")),
            new CategoryTip("§6云篆匣配合", "CHEST",
                List.of("§7• 卷轴需先放入工具「云篆匣」收纳",
                    "§7• 主手持灵杖，§e双击右键§7切换云篆匣中的卷轴",
                    "§7• 灵杖 Lore 会显示当前绑定卷轴")),
            new CategoryTip("§6攻击与释放", "BLAZE_POWDER",
                List.of("§7• §e单击右键§7：灵杖普通攻击",
                    "§7• §eShift+右键§7：释放当前绑定的卷轴技能",
                    "§7• 无需副手再拿卷轴")),
            new CategoryTip("§6灵力补充", "EMERALD",
                List.of("§7• 手持灵石右键补充", "§7• Shift+右键自动补满")),
            new CategoryTip("§6功德值", "EXPERIENCE_BOTTLE",
                List.of("§7• 伤敌增功德，伤友增缺德", "§7• 不再增幅灵杖/卷轴伤害", "§7• 仅可减免突破时雷劫伤害，并会被消耗")),
            new CategoryTip("§6灵杖获取", "CRAFTING_TABLE",
                List.of("§7• 烧火棍：工匠台", "§7• 黄/玄/地/天阶：灵能炼器坊", "§7• 仙阶：归墟（暂无合成配方）"))
        ));
        tips.put("juanzhou", List.of(
            new CategoryTip("§6卷轴简介", "BOOK",
                List.of("§7• 分黄玄地天仙五阶", "§7• 倍率 1 / 1.05 / 1.10 / 1.15 / 1.20", "§7• 各阶卷轴拥有独立技能与熟练度")),
            new CategoryTip("§6存入云篆匣", "CHEST",
                List.of("§7• 卷轴需放入工具「云篆匣」中收纳",
                    "§7• 云篆匣可存放你已获得的全部卷轴",
                    "§7• 未收纳的卷轴无法被灵杖切换")),
            new CategoryTip("§6切换与释放", "BLAZE_ROD",
                List.of("§7• 主手持灵杖，§e双击右键§7切换云篆匣中的卷轴",
                    "§7• §eShift+右键§7释放当前绑定卷轴",
                    "§7• 释放消耗灵力并进入冷却")),
            new CategoryTip("§6熟练度", "EXPERIENCE_BOTTLE",
                List.of("§7• 每100点降低1.5%冷却，上限40%", "§7• 使用卷轴技能可提升熟练度")),
            new CategoryTip("§6功德渡劫", "GOLD_NUGGET",
                List.of("§7• 功德不再增幅卷轴伤害", "§7• 仅可减免突破时雷劫伤害", "§7• 减免时会消耗对应功德")),
            new CategoryTip("§6卷轴获取", "WRITABLE_BOOK",
                List.of("§7• 黄阶：勾豆灰 / 碎玉闪", "§7• 玄阶：九霄环佩鸣 / 寒霜锁", "§7• 地阶：冰火两重天 / 御龙护身决", "§7• 天阶：游龙惊鸿诀 / 星陨劫", "§7• 仙阶：五行必杀（均卷轴撰写台合成）"))
        ));
        return tips;
    }

    private final Map<Player, String> openPlayers = new HashMap<>();
    private Plugin plugin;

    @Override
    public void registerLifecycle(Plugin plugin) {
        this.plugin = plugin;
        KomutechMenuRouter.registerHandler(this);
    }

    @Override
    public boolean handles(InventoryView view) {
        if (view == null) {
            return false;
        }
        // 切页会先 close 再 open，不能依赖 openPlayers；绑定库存 / 标题才是准的
        if (KomutechMenuRouter.isHandlerFor(view.getTopInventory(), this)) {
            return true;
        }
        return isGuideTitle(MenuGuiHelper.legacyTitle(view)) || isGuideTitle(view.getTitle());
    }

    private static boolean isGuideTitle(String title) {
        if (title == null || title.isEmpty()) {
            return false;
        }
        if (MAIN_TITLE.equals(title)) {
            return true;
        }
        String plain = ChatColor.stripColor(title);
        if (plain.contains("口木科技教程")) {
            return true;
        }
        // 分类页：§b§l✨ 入门篇 ✨（去色后不含「口木」）
        if (!(title.startsWith(TITLE_PREFIX) || plain.startsWith("✨ "))) {
            return false;
        }
        return plain.contains("入门篇") || plain.contains("深悟篇") || plain.contains("大成篇")
            || plain.contains("灵植篇") || plain.contains("灵杖篇") || plain.contains("卷轴篇");
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !handles(event.getView())) {
            return;
        }
        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);
        int slot = event.getRawSlot();
        // 仅处理顶部教程箱；点玩家背包也一律拦截
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        String page = openPlayers.getOrDefault(player, resolvePage(event.getView()));
        if ("main".equals(page) || isMainTitle(event.getView())) {
            for (String[] cat : CATEGORIES) {
                if (String.valueOf(slot).equals(cat[3])) {
                    openCategory(player, cat[0], cat[1]);
                    return;
                }
            }
            var clicked = event.getCurrentItem();
            if (clicked != null && clicked.hasItemMeta() && clicked.getItemMeta().hasDisplayName()
                && MYSTERY_NAME.equals(clicked.getItemMeta().getDisplayName())) {
                giveRandomReward(player);
                event.getView().getTopInventory().setItem(slot, null);
                return;
            }
            if (slot == 49) {
                player.closeInventory();
            }
            return;
        }
        if (slot == 49) {
            openMain(player);
        }
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        if (handles(event.getView())) {
            event.setCancelled(true);
            event.setResult(org.bukkit.event.Event.Result.DENY);
        }
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onButtonGroupClick".equals(functionName) && args[0] instanceof Player player) {
            openMain(player);
            return true;
        }
        if ("onUse".equals(functionName) && args.length > 0) {
            try {
                Object event = args[0];
                Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
                openMain(player);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (plugin == null) {
            openPlayers.remove(player);
            return;
        }
        // 切页：close→open 同一 tick，延迟清理避免误删新页状态
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                openPlayers.remove(player);
                return;
            }
            InventoryView open = player.getOpenInventory();
            if (open != null && handles(open)) {
                return;
            }
            openPlayers.remove(player);
        });
    }

    private void openMain(Player player) {
        Inventory inv = MenuGuiHelper.create(54, MAIN_TITLE);
        MenuGuiHelper.applyBorder(inv, BORDER);
        inv.setItem(4, MenuGuiHelper.item("PAINTING", "§a§l口木科技教程说明",
            List.of("§f点击上方分类查看教程", "§f点击下方随机奖励物品", "§f即可获得惊喜！")));
        for (String[] cat : CATEGORIES) {
            inv.setItem(Integer.parseInt(cat[3]), MenuGuiHelper.item(cat[2], cat[1], List.of("§7点击查看")));
        }
        // 旧逻辑：仅 50% 概率在下方空位刷出 1 个神秘箱，点完即消失
        if (ThreadLocalRandom.current().nextDouble() * 100.0 < MYSTERY_SPAWN_CHANCE) {
            int slot = REWARD_SLOTS[ThreadLocalRandom.current().nextInt(REWARD_SLOTS.length)];
            inv.setItem(slot, MenuGuiHelper.item("ENDER_CHEST", MYSTERY_NAME,
                List.of("§7点击随机获得奖励", "§8展示物品不可取出")));
        }
        inv.setItem(49, MenuGuiHelper.item("BARRIER", "§c关闭", List.of("§7关闭菜单")));
        openPlayers.put(player, "main");
        KomutechMenuRouter.bindInventory(inv, this);
        player.openInventory(inv);
    }

    private void openCategory(Player player, String id, String name) {
        Inventory inv = MenuGuiHelper.create(54, TITLE_PREFIX + name.replaceAll("§.", "") + " ✨");
        MenuGuiHelper.applyBorder(inv, BORDER);
        inv.setItem(49, MenuGuiHelper.item("ARROW", "§a返回主菜单", List.of()));
        List<CategoryTip> tips = CATEGORY_TIPS.getOrDefault(id, List.of());
        if (tips.isEmpty()) {
            inv.setItem(13, MenuGuiHelper.item("BOOK", name, List.of("§7教程内容请参考游戏内说明")));
        } else {
            inv.setItem(4, MenuGuiHelper.item("PAPER", "§6" + name + "说明",
                List.of("§7点击条目查看详情", "§e共" + tips.size() + "个知识点")));
            int slot = 10;
            for (CategoryTip tip : tips) {
                if (slot > 43) {
                    break;
                }
                inv.setItem(slot, MenuGuiHelper.item(tip.icon(), tip.name(), tip.lore()));
                slot++;
                if ((slot - 9) % 9 == 0) {
                    slot += 2;
                }
            }
        }
        openPlayers.put(player, id);
        KomutechMenuRouter.bindInventory(inv, this);
        player.openInventory(inv);
    }

    private void giveRandomReward(Player player) {
        double rand = ThreadLocalRandom.current().nextDouble() * 100.0;
        double cum = 0;
        for (Object[] reward : REWARDS) {
            cum += ((Number) reward[1]).doubleValue();
            if (rand >= cum) {
                continue;
            }
            String id = String.valueOf(reward[0]);
            SlimefunItem sf = SlimefunItem.getById(id);
            if (sf != null) {
                var item = sf.getItem().clone();
                KomutechSupport.giveOrDrop(player, item);
                String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                    ? item.getItemMeta().getDisplayName() : id;
                KomutechSupport.send(player, "§a获得奖励: " + name);
            } else {
                KomutechSupport.send(player, "§c奖励物品无效，请联系管理");
            }
            return;
        }
        KomutechSupport.send(player, "§f🎉倒霉🥚，你成功避开了奖励。");
    }

    private static boolean isMainTitle(InventoryView view) {
        return MAIN_TITLE.equals(MenuGuiHelper.legacyTitle(view)) || MAIN_TITLE.equals(view.getTitle())
            || ChatColor.stripColor(view.getTitle()).contains("口木科技教程");
    }

    private static String resolvePage(InventoryView view) {
        if (isMainTitle(view)) {
            return "main";
        }
        String plain = ChatColor.stripColor(MenuGuiHelper.legacyTitle(view));
        if (plain.contains("入门")) {
            return "rumen";
        }
        if (plain.contains("深悟")) {
            return "shenwu";
        }
        if (plain.contains("大成")) {
            return "dacheng";
        }
        if (plain.contains("灵植")) {
            return "lingzhi";
        }
        if (plain.contains("灵杖")) {
            return "lingzhang";
        }
        if (plain.contains("卷轴")) {
            return "juanzhou";
        }
        return "main";
    }
}
