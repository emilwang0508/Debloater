package com.naryo.debloater

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
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
    override fun execute(operation: String, packageName: String, expectedUser: Int, language: String): Bundle {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(if (language == "zh") Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH)
        }
        val localized = context.createConfigurationContext(config)
        if (operation == "shell") {
            check(Binder.getCallingUid() == context.applicationInfo.uid) { localized.getString(R.string.shell_caller_denied) }
            check(Process.myUid() == 2000) { localized.getString(R.string.shell_requires_adb) }
            require(packageName.isNotBlank() && packageName.length <= 8192) { localized.getString(R.string.invalid_command) }
            return run("/system/bin/sh", "-c", packageName)
        }
        val userResult = run("am", "get-current-user")
        val user = userResult.getString("output")?.trim()?.toIntOrNull()
        check(userResult.getInt("exit") == 0 && user != null && user >= 0) {
            localized.getString(R.string.unknown_user, userResult.getString("output"))
        }
        if (operation == "user") return Bundle().apply { putInt("user", user) }

        if (operation == "inventory") return inventory(user, localized)

        if (operation != "list" && operation != "system") {
            require(packageNamePattern.matches(packageName)) { localized.getString(R.string.invalid_package) }
        }
        val userId = user.toString()
        val appOp = listOf("cmd", "appops")
        val command = when (operation) {
            "background-status" -> appOp + listOf("get", "--user", userId, packageName, "RUN_ANY_IN_BACKGROUND")
            "background-restrict", "background-reset" -> {
                check(user == expectedUser) { localized.getString(R.string.user_changed) }
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
                    putBoolean("verified", verified)
                    putString("verification", if (verified) {
                        if (mode == "default") localized.getString(R.string.verified_default) else localized.getString(R.string.verified_restricted)
                    }
                        else localized.getString(R.string.unverified_background))
                    putString("output", listOf(getString("output").orEmpty().trim(),
                        localized.getString(R.string.state_readback, status.getInt("exit")), state.trim())
                        .filter { it.isNotBlank() }.joinToString("\n"))
                }
            }
            "system" -> listOf("pm", "list", "packages", "-s", "--user", userId)
            "list" -> listOf("pm", "list", "packages", "-u", "--user", userId)
            "details" -> listOf("dumpsys", "package", packageName)
            "path" -> listOf("pm", "path", "--user", userId, packageName)
            "freeze", "unfreeze" -> {
                check(user == expectedUser) { localized.getString(R.string.user_changed) }
                val freeze = operation == "freeze"
                val verb = if (freeze) "suspend" else "unsuspend"
                val result = run("pm", verb, "--user", userId, packageName)
                val prefix = "Package $packageName new suspended state: "
                val suspended = result.getString("output").orEmpty().lineSequence()
                    .firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()
                val verified = result.getInt("exit") == 0 && suspended == freeze.toString()
                return result.apply {
                    putInt("user", user)
                    putBoolean("verified", verified)
                    putString("verification", when {
                        verified && freeze -> localized.getString(R.string.verified_frozen)
                        verified -> localized.getString(R.string.verified_unfrozen)
                        freeze && suspended == "false" -> localized.getString(R.string.unverified_frozen)
                        !freeze && suspended == "true" -> localized.getString(R.string.unverified_unfrozen)
                        else -> localized.getString(R.string.unverified_freeze_result)
                    })
                }
            }
            "force-stop" -> listOf("am", "force-stop", "--user", userId, packageName)
            "disable" -> listOf("pm", "disable-user", "--user", userId, packageName)
            "uninstall" -> listOf("pm", "uninstall", "--user", userId, packageName)
            "restore" -> listOf("cmd", "package", "install-existing", "--user", userId, packageName)
            "enable" -> listOf("pm", "enable", "--user", userId, packageName)
            else -> error(localized.getString(R.string.unknown_operation))
        }
        if (operation in setOf("disable", "uninstall", "restore", "enable", "force-stop")) {
            check(user == expectedUser) { localized.getString(R.string.user_changed) }
        }
        return run(*command.toTypedArray()).apply { putInt("user", user) }
    }

    private fun inventory(user: Int, localized: Context): Bundle {
        fun packages(vararg flags: String): Set<String> {
            val result = run("pm", "list", "packages", "--user", user.toString(), *flags)
            check(result.getInt("exit") == 0 && !result.getBoolean("truncated")) {
                localized.getString(R.string.inventory_incomplete, result.getString("output").orEmpty().take(300))
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
