package tech.komutech.native_scripts.machine;

import org.bukkit.event.block.BlockBreakEvent;
import tech.origintech.native_scripts.NativeScript;

public final class BreakNoDropScript implements NativeScript {

    @Override
    public Object invoke(String functionName, Object... args) {
        if ("onBreak".equals(functionName) && args[0] instanceof BlockBreakEvent event) {
            event.setDropItems(false);
        }
        return null;
    }
}
