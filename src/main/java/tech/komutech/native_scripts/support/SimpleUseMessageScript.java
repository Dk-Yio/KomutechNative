package tech.komutech.native_scripts.support;

import tech.origintech.native_scripts.NativeScript;
import tech.origintech.native_scripts.support.UseEvents;

public final class SimpleUseMessageScript implements NativeScript {

    private final String message;

    public SimpleUseMessageScript(String message) {
        this.message = message;
    }

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(ctx -> {
            KomutechSupport.send(ctx.player(), message);
            return null;
        }).orElse(null);
    }
}
