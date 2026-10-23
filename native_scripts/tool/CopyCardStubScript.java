package tech.komutech.native_scripts.tool;

import tech.origintech.native_scripts.NativeScript;
import tech.komutech.native_scripts.support.KomutechSupport;
import tech.origintech.native_scripts.support.UseEvents;

public final class CopyCardStubScript implements NativeScript {

    @Override
    public Object invoke(String functionName, Object... args) {
        if (!"onUse".equals(functionName)) {
            return null;
        }
        return UseEvents.parse(args[0]).map(ctx -> {
            KomutechSupport.send(ctx.player(), "大胆竟敢想作弊");
            return null;
        }).orElse(null);
    }
}
