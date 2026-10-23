package tech.komutech.native_scripts.machine;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Set;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import tech.komutech.native_scripts.support.KomutechMenuHandler;
import tech.komutech.native_scripts.support.KomutechMenuRouter;
import tech.komutech.native_scripts.support.MachineMenuGuard;
import tech.origintech.native_scripts.NativeLifecycleScript;
import tech.origintech.native_scripts.support.MachineScriptHelper;

/**
 * 为未单独实现菜单脚本的口木方块机器锁定装饰槽，仅允许 input/output 槽交互。
 */
public final class KomutechBlockMachineMenuGuard implements NativeLifecycleScript, KomutechMenuHandler {

    private static final Set<String> SCRIPTED_MACHINE_IDS = Set.of(
        "KOMUTECH_L_ZJ_萬衍儀",
        "KOMUTECH_L_ZJ_終極合成台",
        "KOMUTECH_L_ZJ_終極合成台核心",
        "KOMUTECH_L_JQ_灵脉宝窟",
        "KOMUTECH_L_JQ_灵脉晶辉宝窟",
        "KOMUTECH_L_WD_WXZZY"
    );

    @Override
    public void registerLifecycle(Plugin plugin) {
        KomutechMenuRouter.registerHandler(this);
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        return null;
    }

    @Override
    public boolean handles(InventoryView view) {
        SlimefunItem machine = resolveMachine(view);
        if (machine == null) {
            return false;
        }
        String id = machine.getId();
        return id.startsWith("KOMUTECH_") && !SCRIPTED_MACHINE_IDS.contains(id);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!handles(event.getView())) {
            return;
        }
        MachineMenuGuard.protectTopInventory(event, slot -> isAllowedSlot(event.getView(), slot));
    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        if (!handles(event.getView())) {
            return;
        }
        MachineMenuGuard.protectTopInventoryDrag(event, slot -> isAllowedSlot(event.getView(), slot));
    }

    private static boolean isAllowedSlot(InventoryView view, int slot) {
        SlimefunItem machine = resolveMachine(view);
        if (machine == null) {
            return false;
        }
        return MachineScriptHelper.isMachineInventorySlot(machine, slot);
    }

    private static SlimefunItem resolveMachine(InventoryView view) {
        return MachineScriptHelper.resolveBlockMachine(view);
    }
}
