const Files = Java.type('java.nio.file.Files');
const Paths = Java.type('java.nio.file.Paths');
const Consumer = Java.type('java.util.function.Consumer');
const UUID = Java.type('java.util.UUID');
const Attribute = Java.type('org.bukkit.attribute.Attribute');
const AttributeModifier = Java.type('org.bukkit.attribute.AttributeModifier');
const HashSet = Java.type('java.util.HashSet');
const EquipmentSlot = Java.type('org.bukkit.inventory.EquipmentSlot');

const ATTRS = ['血量', '攻击力', '防御力', '速度', '灵识'];
const ATTR_META = {
    '血量': { uuid: '10110305-1003-1204-0823-141408280000', names: ['GENERIC_MAX_HEALTH', 'MAX_HEALTH'] },
    '攻击力': { uuid: '10110305-1003-1204-0823-092412040000', names: ['GENERIC_ATTACK_DAMAGE', 'ATTACK_DAMAGE'] },
    '防御力': { uuid: '10110305-1003-1204-0823-042100060000', names: ['GENERIC_ARMOR', 'ARMOR'] },
    '速度': { uuid: '10110305-1003-1204-0823-210505050000', names: ['GENERIC_MOVEMENT_SPEED', 'MOVEMENT_SPEED'] },
    '灵识': {
        uuid: ['10110305-1003-1204-0823-082317040001', '10110305-1003-1204-0823-082317040002'],
        names: ['BLOCK_INTERACTION_RANGE', 'ENTITY_INTERACTION_RANGE']
    }
};
const DATA_PATH = 'plugins/RykenSlimefunCustomizer/addon_configs/Komutech/玩家属性';

function getAttributeInstance(attr) {
    const meta = ATTR_META[attr];
    if (!meta) return null;
    for (let n of meta.names) {
        try { return Attribute.valueOf(n); } catch (e) {}
    }
    return null;
}

function removeAttrModifier(player, attr) {
    const meta = ATTR_META[attr];
    if (!meta) return;
    if (attr === '灵识') {
        meta.names.forEach((n, i) => {
            try {
                const inst = player.getAttribute(Attribute.valueOf(n));
                if (!inst) return;
                const old = inst.getModifier(UUID.fromString(meta.uuid[i]));
                if (old) inst.removeModifier(old);
            } catch (e) {}
        });
        return;
    }
    const inst = player.getAttribute(getAttributeInstance(attr));
    if (!inst) return;
    const old = inst.getModifier(UUID.fromString(meta.uuid));
    if (old) inst.removeModifier(old);
}

function clearAllAttrs(player) {
    ATTRS.forEach(attr => removeAttrModifier(player, attr));
}

const pending = new HashSet();

function onUse(event) {
    const player = event.getPlayer();
    const name = player.getName();

    if (pending.contains(player)) {
        player.sendMessage('§e归元珠灵光未定，请待上一道因果了结……');
        return;
    }

    const file = Paths.get(DATA_PATH, '[' + name + '].json');
    if (!Files.exists(file)) {
        player.sendMessage('§e你已身处凡尘，无需再渡归元之劫。');
        return;
    }

    pending.add(player);
    player.sendMessage('§c§l归元珠颤动，此去修为尽散、灵根重铸，永无回头之路！');
    player.sendMessage('§a若心意已决，请于天地间诵念 §l“归元” §a以证道心，否则则止。');

    getChatInput(player, new (Java.extend(Consumer, {
        accept: function(input) {
            pending.remove(player);
            const cmd = input.replace(/§./g, '').trim();
            if (cmd !== '归元') {
                player.sendMessage('§e道心未坚，归元之劫暂缓。');
                return;
            }
            try {
                clearAllAttrs(player);
                Files.delete(file);
                player.sendMessage('§a§l归元功成！修为已散、灵根已化，你重归凡胎，道途再启。');

                // 消耗道具（主手或副手）
                const hand = event.getHand();
                let item = null;
                if (hand === EquipmentSlot.HAND) {
                    item = player.getInventory().getItemInMainHand();
                } else if (hand === EquipmentSlot.OFF_HAND) {
                    item = player.getInventory().getItemInOffHand();
                }
                if (item && item.getAmount() > 0) {
                    item.setAmount(item.getAmount() - 1);
                }
            } catch (e) {
                player.sendMessage('§c归元失败，天地有变，请联系仙官。');
            }
        }
    })));
}