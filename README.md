# KomutechNative

口木科技（Komutech）纯 Java 版，独立 Slimefun 插件。

## 构建

```powershell
.\tools\build-source.ps1 -ProjectRoot .\KomutechNative
# 或批量
.\tools\build-all-source.ps1 -SkipBackup
```

产物：`build/KomutechNative-Release-3.0.1.jar`

## 项目结构

```
src/main/java/tech/origintech/
  NativeAddonConfig.java
  native_scripts/
    NativeScriptRegistry.java
    komutech/                 全部附属脚本
      support/                KomutechSupport、工具脚本等
src/main/resources/addon/
```

## 共享框架工具

`RscFramework/native_scripts/support/`：`UseEvents`、`ChatColors`、`InventoryHelper`、`MachineScriptHelper` 等。

## 说明

- 脚本位于 `native_scripts/komutech/` 命名空间
- 附属配置内嵌于 JAR，启动后解压到 `plugins/KomutechNative/addons/Komutech/`
- 可与 RykenSlimefunCustomizer 共存
- 管理命令：`/komutech`、`/komutech reload`（权限 `komutech.admin`）

## 部署

1. 将 JAR 放入服务器 `plugins/`
2. 确认 Slimefun 及 `pluginDepends` 已满足
3. 重启服务器，使用 `/komutech reload` 验证附属加载

## 依赖

- JDK 21
- 内核 `RscFramework` 源码（由 `build-source.ps1` 自动引用）
