package tech.komutech.native_scripts.machine;

import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechSupport;

public final class PendingAddonScript implements NativeScript {

    private static final String PLACE_MESSAGE = "想用看说明";
    private static final String OPEN_MESSAGE = "想用看说明";

    private final Mode mode;

    public enum Mode {
        PLACE,
        OPEN
    }

    public PendingAddonScript(Mode mode) {
        this.mode = mode;
    }

    public static PendingAddonScript onPlace() {
        return new PendingAddonScript(Mode.PLACE);
    }

    public static PendingAddonScript onOpen() {
        return new PendingAddonScript(Mode.OPEN);
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if (mode == Mode.PLACE && "onPlace".equals(functionName) && args[0] instanceof BlockPlaceEvent event) {
            Player player = event.getPlayer();
            KomutechSupport.send(player, PLACE_MESSAGE);
        } else if (mode == Mode.OPEN && "onOpen".equals(functionName) && args[0] instanceof Player player) {
            KomutechSupport.send(player, OPEN_MESSAGE);
        }
        return null;
    }
}
