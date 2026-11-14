package com.example.colorosdebloater

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

        if (operation != "list" && operation != "system") {
            require(packageNamePattern.matches(packageName)) { "包名无效" }
        }
        val userId = user.toString()
        val command = when (operation) {
            "system" -> listOf("pm", "list", "packages", "-s", "--user", userId)
            "list" -> listOf("pm", "list", "packages", "-u", "--user", userId)
            "details" -> listOf("dumpsys", "package", packageName)
            "path" -> listOf("pm", "path", "--user", userId, packageName)
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
