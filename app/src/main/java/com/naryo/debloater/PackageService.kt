package com.naryo.debloater

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Process
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

internal val packageNamePattern = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

class PackageService(private val context: Context) : IPackageService.Stub() {
    override fun destroy() = exitProcess(0)

    @Synchronized
    override fun execute(operation: String, packageName: String, expectedUser: Int): Bundle {
        if (operation == "shell") {
            check(Binder.getCallingUid() == context.applicationInfo.uid) { "调用方无权执行 Shell 命令" }
            check(Process.myUid() == 2000) { "请以无线调试或 USB ADB 启动 Shizuku" }
            require(packageName.isNotBlank() && packageName.length <= 8192) { "命令为空或过长" }
            return run("/system/bin/sh", "-c", packageName)
        }
        val userResult = run("am", "get-current-user")
        val user = userResult.getString("output")?.trim()?.toIntOrNull()
        check(userResult.getInt("exit") == 0 && user != null && user >= 0) {
            "无法识别当前 Android 用户：${userResult.getString("output")}"
        }
        if (operation == "user") return Bundle().apply { putInt("user", user) }

        if (operation == "inventory") return inventory(user)

        if (operation != "list" && operation != "system") {
            require(packageNamePattern.matches(packageName)) { "包名无效" }
        }
        val userId = user.toString()
        val appOp = listOf("cmd", "appops")
        val command = when (operation) {
            "background-status" -> appOp + listOf("get", "--user", userId, packageName, "RUN_ANY_IN_BACKGROUND")
            "background-restrict", "background-reset" -> {
                check(user == expectedUser) { "当前 Android 用户已切换，请重新确认操作。" }
                val mode = if (operation == "background-restrict") "ignore" else "default"
                val result = run(*(appOp + listOf("set", "--user", userId, packageName, "RUN_ANY_IN_BACKGROUND", mode)).toTypedArray())
                val status = run(*(appOp + listOf("get", "--user", userId, packageName, "RUN_ANY_IN_BACKGROUND")).toTypedArray())
                val state = status.getString("output").orEmpty()
                val reportedMode = Regex("(?m)^RUN_ANY_IN_BACKGROUND: ([a-z_]+)").find(state)?.groupValues?.get(1)
                val usesDefault = reportedMode == "default" ||
                    (reportedMode == null && state.lineSequence().any { it.trim() == "No operations." } &&
                        state.lineSequence().any { it.trim().startsWith("Default mode:") })
                return result.apply {
                    putInt("user", user)
                    val verified = getInt("exit") == 0 && status.getInt("exit") == 0 &&
                        (if (mode == "default") usesDefault else reportedMode == mode)
                    putString("verification", if (verified) "已确认：${if (mode == "default") "使用系统默认规则" else "后台限制已写入"}"
                        else "未能确认设置生效，请检查回读状态。")
                    putString("output", listOf(getString("output").orEmpty().trim(),
                        "状态回读（退出码 ${status.getInt("exit")}）", state.trim())
                        .filter { it.isNotBlank() }.joinToString("\n"))
                }
            }
            "system" -> listOf("pm", "list", "packages", "-s", "--user", userId)
            "list" -> listOf("pm", "list", "packages", "-u", "--user", userId)
            "details" -> listOf("dumpsys", "package", packageName)
            "path" -> listOf("pm", "path", "--user", userId, packageName)
            "freeze", "unfreeze" -> {
                check(user == expectedUser) { "当前 Android 用户已切换，请重新确认操作。" }
                val freeze = operation == "freeze"
                val verb = if (freeze) "suspend" else "unsuspend"
                val result = run("pm", verb, "--user", userId, packageName)
                val prefix = "Package $packageName new suspended state: "
                val suspended = result.getString("output").orEmpty().lineSequence()
                    .firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()
                val verified = result.getInt("exit") == 0 && suspended == freeze.toString()
                return result.apply {
                    putInt("user", user)
                    putString("verification", when {
                        verified && freeze -> "已确认：应用已挂起"
                        verified -> "已确认：应用已解除挂起"
                        freeze && suspended == "false" -> "未能确认设置生效：系统回读显示应用未挂起。"
                        !freeze && suspended == "true" -> "未能确认设置生效：系统回读显示应用仍被挂起。"
                        else -> "未能确认设置生效：请查看命令输出。"
                    })
                }
            }
            "disable" -> listOf("pm", "disable-user", "--user", userId, packageName)
            "uninstall" -> listOf("pm", "uninstall", "--user", userId, packageName)
            "restore" -> listOf("cmd", "package", "install-existing", "--user", userId, packageName)
            "enable" -> listOf("pm", "enable", "--user", userId, packageName)
            else -> error("未知操作")
        }
        if (operation in setOf("disable", "uninstall", "restore", "enable")) {
            check(user == expectedUser) { "当前 Android 用户已切换，请重新确认操作。" }
        }
        return run(*command.toTypedArray()).apply { putInt("user", user) }
    }

    private fun inventory(user: Int): Bundle {
        fun packages(vararg flags: String): Set<String> {
            val result = run("pm", "list", "packages", "--user", user.toString(), *flags)
            check(result.getInt("exit") == 0 && !result.getBoolean("truncated")) {
                "无法完整读取应用列表：${result.getString("output").orEmpty().take(300)}"
            }
            return result.getString("output").orEmpty().lineSequence()
                .filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.toSet()
        }
        val all = packages("-u")
        val installed = packages()
        val system = packages("-s", "-u")
        val disabled = packages("-d")
        val rows = (all + installed).map { name ->
            val kind = if (name in system) "system" else "user"
            val state = when {
                name !in installed -> "removed"
                name in disabled -> "disabled"
                else -> "installed"
            }
            "$kind|$state|$name"
        }
        return Bundle().apply {
            putInt("user", user)
            putStringArrayList("packages", ArrayList(rows))
        }
    }

    private fun run(vararg command: String): Bundle {
        val process = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = StringBuffer()
        val reader = thread(isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().use {
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        val remaining = 150_000 - output.length
                        if (remaining > 0) output.append(buffer, 0, minOf(remaining, count))
                    }
                }
            }
        }
        val completed = process.waitFor(20, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        reader.join(1000)
        return Bundle().apply {
            putString("command", command.joinToString(" "))
            putString("output", output.toString())
            putInt("exit", if (completed) process.exitValue() else 124)
            putBoolean("timedOut", !completed)
            putBoolean("truncated", output.length >= 150_000)
        }
    }
}
