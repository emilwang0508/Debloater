# ColorOS Debloater

在 ColorOS 上通过 Shizuku 查询与管理应用包。支持按应用名称或包名搜索、停用、当前 Android 用户卸载、恢复预装包，以及查看包管理器错误。

1. 在手机上通过无线调试或 USB ADB 启动 Shizuku。
2. 打开应用，点「连接或授权 Shizuku」并完成授权。
3. 点「查找中国移动自动注册」，或按应用名、包名搜索。结果可能包含相关的运营商组件，点选后核对包详情。
4. 停用或卸载前核对确认框中的包名和 Android 用户编号。对当前用户卸载系统应用后，可用「恢复当前用户安装」尝试恢复，再按需启用。

应用通过 Shizuku UserService 执行包管理命令。系统保护或设备策略可能拒绝卸载。遇到 `DELETE_FAILED_INTERNAL_ERROR` 时，界面会显示原始输出、退出码及排查提示。

## 编译

使用 JDK 17 或 21、Gradle 9.3.1、Android SDK 37.0 与 Build Tools 36.0.0。运行 `gradle assembleDebug lintDebug`，APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。项目以 API 37 编译，保留 API 36 作为目标版本。

ColorOS 17 的 Shizuku 授权和 OEM 包管理行为需要在真机验证。
