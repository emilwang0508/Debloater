# ColorOS Debloater

在 ColorOS 上通过 Shizuku 查询与管理应用包。支持按应用名称或包名搜索、停用、当前 Android 用户卸载、恢复预装包，以及查看包管理器错误。

1. 在手机上通过无线调试或 USB ADB 启动 Shizuku。
2. 打开应用，点「连接或授权 Shizuku」并完成授权。
3. 按应用名或包名输入关键词，使用「模糊搜索程序包」。多个关键词以空格分隔，所有词都匹配时才显示。
4. 停用或卸载前核对确认框中的包名和 Android 用户编号。对当前用户卸载系统应用后，可用「恢复当前用户安装」尝试恢复，再按需启用。

点「ADB Shell」可执行单条命令，例如 `id` 或 `pm list packages -s`。它需要以无线调试或 USB ADB 启动的 Shizuku 13 或更高版本，支持管道并返回输出和退出码。

应用通过 Shizuku UserService 执行包管理命令。系统保护或设备策略可能拒绝卸载。遇到 `DELETE_FAILED_INTERNAL_ERROR` 时，界面会显示原始输出、退出码及排查提示。

## 编译

使用 JDK 17 或 21、Gradle 9.3.1、Android SDK 37.0 与 Build Tools 36.0.0。运行 `gradle assembleDebug lintDebug`，APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。项目以 API 37 编译，保留 API 36 作为目标版本。

ColorOS 17 的 Shizuku 授权和 OEM 包管理行为需要在真机验证。
