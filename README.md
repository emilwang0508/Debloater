# Debloater

[简体中文](#简体中文) · [English](#english) · [APK / GitHub Release](https://github.com/emilwang0508/Debloater/releases/tag/v0.9.0)

## 简体中文

Debloater 是一款通过 Shizuku 管理 Android 应用的免 Root 工具。应用包名为 `com.naryo.debloater`。

### 安装与连接

1. 打开 [v0.9.0 发布页](https://github.com/emilwang0508/Debloater/releases/tag/v0.9.0)，在 **Assets** 中下载 `Debloater-v0.9.0.apk` 并安装。APK 最低安装要求为 Android 8.0（API 26），实际使用还需要设备支持 Shizuku。
2. 安装并启动 [Shizuku](https://shizuku.rikka.app/) 13 或更新版本，可使用无线调试或 USB ADB。
3. 打开 Debloater，未授权时点「授权」，允许 Shizuku 授权。旧版包名不同，可与本版并存，本版需要单独授权。

### 查找与管理应用
应用安装、更新、卸载或组件状态变化后，列表会自动刷新。侧边栏的「操作记录」最多保留最近 50 条冻结、停用、卸载和恢复相关操作，可从已确认的记录进入恢复操作。
从左上角打开侧边栏，点「语言」可选择跟随系统、简体中文或 English。跟随系统时，中文系统显示简体中文，其他系统语言显示英文。列表优先读取本地缓存，搜索只筛选缓存。点「刷新」或完成卸载、恢复、停用等操作后会重新读取。

输入应用名称或包名即可即时搜索。多个关键词用空格分隔，所有词都匹配时才显示。列表可筛选「已安装」「系统应用」「用户应用」「已停用」「已卸载」。滚动接近末尾时自动加载下一批，搜索覆盖当前分类的完整清单。可用的应用图标会随列表行加载。

| 入口 | 用法 |
| --- | --- |
| 应用详情、安装路径 | 查看包管理器原始信息。知道包名时，也可在「工具」中手动输入。 |
| 权限与通知 | 「应用权限」打开应用的系统详情页，再点系统页的「权限」。「应用通知」打开系统通知设置。 |
| 后台限制 | 查询、限制后台运行或恢复默认设置。修改后回读 `RUN_ANY_IN_BACKGROUND` 状态。 |
| 冻结 / 解除冻结 | 挂起或解除挂起当前用户的应用。挂起会限制打开界面和显示通知，后台服务仍可能运行。 |
| 更多操作 | 可强行停止、停用、重新启用、为当前用户卸载，或尝试恢复当前用户安装。强行停止会结束当前运行，应用仍可再次启动。 |
| 工具 → ADB Shell 终端 | 用以 ADB 模式启动的 Shizuku 执行命令，查看输出和退出码。支持命令历史，每条命令独立执行。 |

变更前请核对包名和 Android 用户编号。「恢复当前用户安装」只适用于系统仍保留安装包的情况。厂商保护或设备策略可能拒绝相关操作。看到退出码 `0` 时，仍应检查实际回读状态。若出现 `DELETE_FAILED_INTERNAL_ERROR`，可在操作结果中查看原始输出和诊断提示。

## English

Debloater is a rootless Android app-management tool powered by Shizuku. Its package ID is `com.naryo.debloater`. Menu names below include the Chinese labels shown in the app.

### Install and connect

1. Download `Debloater-v0.9.0.apk` from **Assets** on the [v0.9.0 release page](https://github.com/emilwang0508/Debloater/releases/tag/v0.9.0) and install it. The APK requires Android 8.0 (API 26) or later. The device must also support Shizuku.
2. Install and start [Shizuku](https://shizuku.rikka.app/) 13 or newer using wireless debugging or USB ADB.
3. Open Debloater, tap **授权** (Authorize) when prompted, and grant Shizuku access. Builds with the old package ID can coexist with this version, which needs its own authorization.

### Find and manage apps
The list refreshes when Android reports app installation, updates, removal, or component changes. Operation history in the drawer stores up to 50 recent freeze, disable, uninstall, and recovery actions. Confirmed entries can provide a shortcut to recovery.
Open the top-left menu and select Language to choose Follow system, Simplified Chinese, or English. Follow system uses Simplified Chinese for Chinese system locales and English otherwise. Search filters the cached list. Refresh or a package state change reloads the list.

Search by display name or package ID. Separate keywords with spaces to match all terms. Filter by installed, system, user, disabled, or uninstalled for the current user. More results load automatically as you scroll. Search covers the entire selected category, and available app icons load as rows are displayed.

| Menu | What it does |
| --- | --- |
| App details and installation path | Inspect package-manager output. You can also enter a known package ID from **工具** (Tools). |
| 权限与通知 (Permissions and notifications) | Open the app's system info page to manage permissions, or open its notification settings directly. |
| Background restrictions | Check, apply, or reset the background restriction. Debloater reads back the `RUN_ANY_IN_BACKGROUND` setting after changes. |
| Freeze / unfreeze | Suspend or unsuspend the app for the current user. Suspension restricts opening the app and showing notifications. Background services may still run. |
| 更多操作 (More actions) | Force-stop, disable, enable, uninstall for the current user, or try restoring an existing package. Force-stop ends the current execution. The app can still be launched again. |
| 工具 → ADB Shell 终端 (Tools → ADB Shell terminal) | Run commands through Shizuku in ADB mode, view output and exit codes, and reuse command history. Each command runs in a separate shell. |

Check the package ID and Android user before applying a change. Restore works when Android still has the package. Device policy or vendor protections may reject operations. Check the returned state even when the exit code is `0`. When uninstall returns `DELETE_FAILED_INTERNAL_ERROR`, the result includes the raw output and diagnostic hints.

## 构建 / Build

使用 / Use JDK 17 or 21, Gradle 9.3.1, Android SDK 37.0, and Build Tools 36.0.0.

```sh
gradle assembleDebug lintDebug
```

APK 输出 / APK output: `app/build/outputs/apk/debug/app-debug.apk`.

### 版本合并 / Release merges

将 `release/vX.Y.Z` 合入 `main` 前，在 `app/build.gradle.kts` 将 `versionName` 设为 `X.Y.Z`，并将 `versionCode` 提高到大于已分发的所有构建（包括预览版）。通过 PR 合并，等版本检查和 APK 构建通过后再合入。

Before merging `release/vX.Y.Z` into `main`, set `versionName` to `X.Y.Z` in `app/build.gradle.kts` and increase `versionCode` above every distributed build, including previews. Merge through a pull request after the version check and APK build pass.
