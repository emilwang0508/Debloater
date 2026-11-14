package com.example.colorosdebloater

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var results: LinearLayout
    private lateinit var query: EditText
    private lateinit var pkg: EditText
    private var service: IPackageService? = null
    private var binding = false
    private var searchGeneration = 0
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, PackageService::class.java))
            .processNameSuffix("package_service").daemon(false).version(3)
    }
    private val received = Shizuku.OnBinderReceivedListener { connect() }
    private val dead = Shizuku.OnBinderDeadListener {
        service = null
        binding = false
        status.text = "Shizuku 服务已断开"
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { request, grant ->
        if (request == 1001) {
            if (grant == PackageManager.PERMISSION_GRANTED) connect()
            else status.text = "Shizuku 授权被拒绝，可在 Shizuku 的授权管理中修改"
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IPackageService.Stub.asInterface(binder)
            binding = false
            status.text = "Shizuku 已授权，包管理服务已连接"
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            binding = false
            status.text = "包管理服务已断开，请重新连接"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(padding + insets.systemWindowInsetLeft,
                    padding + insets.systemWindowInsetTop,
                    padding + insets.systemWindowInsetRight,
                    padding + insets.systemWindowInsetBottom)
                insets
            }
        }
        status = TextView(this)
        query = EditText(this).apply { hint = "应用名或包名，多个关键词用空格分隔" }
        pkg = EditText(this).apply { hint = "完整包名"; isSingleLine = true }
        output = TextView(this).apply { setTextIsSelectable(true) }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(status)
        fun button(title: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = title; setOnClickListener { action() } })
        }
        button("连接或授权 Shizuku") { connect(requestPermission = true) }
        root.addView(query)
        button("搜索系统应用") { search("system", query.text.toString().trim()) }
        button("模糊搜索程序包") { search("list", query.text.toString().trim()) }
        button("ADB Shell") { showShell() }
        root.addView(results)
        root.addView(pkg)
        button("查看包详情与诊断") { withPackage { run("details", it, done = ::display) } }
        button("查看安装路径") { withPackage { run("path", it, done = ::display) } }
        button("停用当前用户") { change("disable", "停用") }
        button("卸载当前用户") { change("uninstall", "卸载") }
        button("恢复当前用户安装") { change("restore", "恢复安装") }
        button("重新启用") { change("enable", "重新启用") }
        root.addView(output)
        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, -2) })

        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderReceivedListenerSticky(received)
        connect()
    }

    private fun connect(requestPermission: Boolean = false) {
        try {
            when {
                !Shizuku.pingBinder() -> status.text = "请先通过无线调试或 USB ADB 启动 Shizuku"
                Shizuku.isPreV11() || Shizuku.getVersion() < 13 -> status.text = "请更新到 Shizuku 13 或更高版本"
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                    status.text = "等待 Shizuku 授权"
                    if (requestPermission) {
                        if (Shizuku.shouldShowRequestPermissionRationale()) {
                            status.text = "请在 Shizuku 的授权管理中允许本应用"
                        } else Shizuku.requestPermission(1001)
                    }
                }
                service == null && (!binding || requestPermission) -> {
                    binding = true
                    status.text = "正在连接包管理服务…"
                    Shizuku.bindUserService(args, connection)
                }
            }
        } catch (error: Exception) {
            binding = false
            status.text = "连接失败：${error.message}"
        }
    }

    private fun withPackage(action: (String) -> Unit) {
        val name = pkg.text.toString().trim()
        if (packageNamePattern.matches(name) && name != packageName) action(name)
        else output.text = "请输入有效的目标应用包名"
    }

    private fun change(operation: String, label: String) = withPackage { name ->
        run("user") { info ->
            val user = info.getInt("user", -1)
            AlertDialog.Builder(this)
                .setTitle("确认${label}")
                .setMessage("目标：$name\n当前 Android 用户：$user\n系统组件的状态变更可能影响相关功能。")
                .setNegativeButton("取消", null)
                .setPositiveButton(label) { _, _ -> run(operation, name, user, ::display) }
                .show()
        }
    }

    private fun run(
        operation: String,
        name: String = "",
        user: Int = -1,
        done: (Bundle) -> Unit
    ) {
        val remote = service
        if (remote == null) {
            output.text = "请先连接或授权 Shizuku，连接完成后再重试"
            connect()
            return
        }
        output.text = "正在执行…"
        worker.execute {
            val result = runCatching { remote.execute(operation, name, user) }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) result.fold(done) {
                    output.text = "执行失败：${it.message ?: it.javaClass.simpleName}"
                }
            }
        }
    }

    private fun search(operation: String, term: String) {
        val generation = ++searchGeneration
        results.removeAllViews()
        run(operation) { result ->
            if (generation != searchGeneration) return@run
            if (result.getInt("exit") != 0) {
                display(result)
                return@run
            }
            val names = result.getString("output").orEmpty().lineSequence()
                .filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.toSet()
            @Suppress("DEPRECATION")
            val localeLabels = packageManager.getInstalledApplications(0)
                .filter { it.packageName in names }
                .associate { it.packageName to it.loadLabel(packageManager).toString() }
            val keywords = term.split(Regex("\\s+")).filter { it.isNotBlank() }
            val matches = names.map { it to localeLabels[it].orEmpty() }
                .filter { (id, title) ->
                    keywords.all { keyword -> id.contains(keyword, true) || title.contains(keyword, true) }
                }
                .sortedWith(compareByDescending<Pair<String, String>> { (id, title) ->
                    when {
                        title.equals(term, true) || id.equals(term, true) -> 3
                        title.startsWith(term, true) || id.startsWith(term, true) -> 2
                        title.contains(term, true) || id.contains(term, true) -> 1
                        else -> 0
                    }
                }.thenBy { it.first })
            val limit = 40
            output.text = "用户 ${result.getInt("user")} · ${matches.size} 个结果" +
                if (matches.size > limit) "（前 $limit 个，请缩小搜索范围）" else ""
            if (matches.isEmpty()) results.addView(TextView(this).apply { text = "没有匹配应用" })
            for ((name, title) in matches.take(limit)) {
                results.addView(Button(this).apply {
                    text = if (title.isBlank()) name else "$title\n$name"
                    isAllCaps = false
                    setOnClickListener { pkg.setText(name); run("details", name, done = ::display) }
                })
            }
        }
    }

    private fun showShell() {
        val input = EditText(this).apply {
            hint = "例如 id 或 pm list packages -s"
            setSingleLine(false)
            maxLines = 6
            filters = arrayOf(android.text.InputFilter.LengthFilter(8192))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("ADB Shell")
            .setMessage("通过 Shizuku 以 ADB Shell 权限执行单条命令。支持管道，最长等待 20 秒。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("执行", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val command = input.text.toString().trim()
                if (command.isEmpty()) {
                    input.error = "请输入命令"
                    return@setOnClickListener
                }
                dialog.dismiss()
                run("shell", command) { result ->
                    val lines = mutableListOf("退出码 ${result.getInt("exit")}")
                    if (result.getBoolean("timedOut")) lines.add("执行超时")
                    val response = result.getString("output").orEmpty()
                    lines.add(response.take(30_000).ifBlank { "命令没有输出" })
                    if (result.getBoolean("truncated") || response.length > 30_000) {
                        lines.add("输出已截断，可缩小命令输出范围后重试。")
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Shell 执行结果")
                        .setMessage(lines.joinToString("\n\n"))
                        .setPositiveButton("关闭", null)
                        .show()
                        .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
                    output.text = "Shell 执行完成，退出码 ${result.getInt("exit")}"
                }
            }
        }
        dialog.show()
    }

    private fun display(result: Bundle) {
        val response = result.getString("output").orEmpty().trim()
        val hint = when {
            "DELETE_FAILED_INTERNAL_ERROR" in response ->
                "包管理器返回内部卸载错误。这个错误码无法定位原因。请用包详情核对当前用户的 installed/enabled 状态和系统包标记，再核对设备管理策略及 ColorOS 的限制。"
            "DELETE_FAILED_DEVICE_POLICY_MANAGER" in response || "DELETE_FAILED_OWNER_BLOCKED" in response ->
                "请核对设备管理应用或设备所有者设置的卸载限制。"
            "DELETE_FAILED_USER_RESTRICTED" in response -> "请核对当前 Android 用户的卸载限制。"
            "SecurityException" in response || "Permission Denial" in response ->
                "系统拒绝执行该操作，请核对 Shizuku 的启动方式和设备限制。"
            result.getInt("exit") == 124 -> "命令超时，请重新检查包状态后再操作。"
            else -> ""
        }
        output.text = listOf(
            "用户 ${result.getInt("user")} · 退出码 ${result.getInt("exit")}",
            result.getString("command").orEmpty(), hint, response.take(30_000)
        ).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        if (service != null || binding) runCatching { Shizuku.unbindUserService(args, connection, true) }
        worker.shutdown()
        super.onDestroy()
    }
}
