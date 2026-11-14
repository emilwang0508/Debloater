package com.example.colorosdebloater

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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
    private var shellTranscript = ""
    private val shellHistory = mutableListOf<String>()
    private var shellBusy = false
    private var terminalRefresh: (() -> Unit)? = null
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, PackageService::class.java))
            .processNameSuffix("package_service").daemon(false).version(4)
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
        fun heading(text: String) = root.addView(TextView(this).apply {
            this.text = text
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 4)
        })
        fun button(title: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = title; setOnClickListener { action() } })
        }
        button("连接或授权 Shizuku") { connect(requestPermission = true) }
        heading("程序包搜索")
        root.addView(query)
        button("搜索系统应用") { search("system", query.text.toString().trim()) }
        button("模糊搜索程序包") { search("list", query.text.toString().trim()) }
        root.addView(results)
        heading("应用管理")
        root.addView(pkg)
        button("包详情与诊断") { withPackage { run("details", it, done = ::display) } }
        button("应用操作") {
            showMenu("应用操作", listOf(
                "停用" to { change("disable", "停用") },
                "重新启用" to { change("enable", "重新启用") },
                "为当前用户卸载" to { change("uninstall", "卸载") },
                "恢复当前用户安装" to { change("restore", "恢复安装") },
                "查看安装路径" to { withPackage { run("path", it, done = ::display) } }
            ))
        }
        button("后台限制（AppOps）") {
            showMenu("后台限制（AppOps）", listOf(
                "查询状态" to { withPackage { run("background-status", it, done = ::display) } },
                "限制后台运行" to { change("background-restrict", "限制后台运行") },
                "恢复系统默认" to { change("background-reset", "恢复系统默认") }
            ))
        }
        heading("命令工具")
        button("ADB Shell") { showShell() }
        root.addView(output)
        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, -2) })

        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderReceivedListenerSticky(received)
        connect()
    }

    private fun showMenu(title: String, items: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this).setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, index -> items[index].second() }
            .setNegativeButton("关闭", null)
            .show()
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
                .setPositiveButton(label) { _, _ -> run(operation, name, user, done = ::display) }
                .show()
        }
    }

    private fun run(
        operation: String,
        name: String = "",
        user: Int = -1,
        failed: (String) -> Unit = { output.text = it },
        done: (Bundle) -> Unit
    ) {
        val remote = service
        if (remote == null) {
            failed("请先连接或授权 Shizuku，连接完成后再重试")
            connect()
            return
        }
        output.text = "正在执行…"
        worker.execute {
            val result = runCatching { remote.execute(operation, name, user) }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) result.fold(done) {
                    failed("执行失败：${it.message ?: it.javaClass.simpleName}")
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
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar)
            .setTitle("ADB Shell")
            .setPositiveButton("关闭", null)
            .create()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (12 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
        }
        val transcript = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(transcript) }
        layout.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val input = EditText(this).apply {
            hint = "$ 输入命令"
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            filters = arrayOf(android.text.InputFilter.LengthFilter(8192))
        }
        val row = LinearLayout(this)
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val execute = Button(this).apply { text = "执行" }
        row.addView(execute)
        layout.addView(row)
        val controls = LinearLayout(this)
        var historyIndex = shellHistory.size
        fun control(label: String, action: () -> Unit) {
            controls.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        control("上一条") {
            if (historyIndex > 0) input.setText(shellHistory[--historyIndex])
            input.setSelection(input.length())
        }
        control("下一条") {
            if (historyIndex < shellHistory.size) historyIndex++
            input.setText(shellHistory.getOrNull(historyIndex).orEmpty())
            input.setSelection(input.length())
        }
        control("清屏") {
            shellTranscript = ""
            terminalRefresh?.invoke()
        }
        layout.addView(controls)
        terminalRefresh = {
            transcript.text = shellTranscript.ifEmpty { "输入 id 查看 Shell 身份。支持管道，单次等待约 20 秒。\n" }
            execute.isEnabled = !shellBusy
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        fun append(text: String) {
            shellTranscript = (shellTranscript + text).takeLast(60_000)
            terminalRefresh?.invoke()
        }
        fun submit() {
            val command = input.text.toString().trim()
            if (command.isEmpty() || shellBusy) return
            if (shellHistory.lastOrNull() != command) shellHistory.add(command)
            if (shellHistory.size > 50) shellHistory.removeAt(0)
            historyIndex = shellHistory.size
            input.text.clear()
            shellBusy = true
            append("$ $command\n")
            run("shell", command, failed = { error ->
                shellBusy = false
                append("$error\n\n")
            }) { result ->
                shellBusy = false
                val response = result.getString("output").orEmpty()
                if (response.isNotEmpty()) append(response.take(30_000).trimEnd() + "\n")
                if (result.getBoolean("truncated") || response.length > 30_000) append("输出已截断\n")
                if (result.getBoolean("timedOut")) append("执行超时\n")
                append("[退出码 ${result.getInt("exit")}]\n\n")
            }
        }
        execute.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == EditorInfo.IME_ACTION_GO || enter && event?.action == KeyEvent.ACTION_DOWN) submit()
            actionId == EditorInfo.IME_ACTION_GO || enter
        }
        dialog.setView(layout)
        dialog.setOnDismissListener { terminalRefresh = null }
        dialog.show()
        dialog.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        terminalRefresh?.invoke()
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
            result.getString("command").orEmpty(), result.getString("verification").orEmpty(),
            hint, response.take(30_000)
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
