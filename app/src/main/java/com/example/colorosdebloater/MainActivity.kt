package com.example.colorosdebloater

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Switch
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
    private lateinit var results: LinearLayout
    private lateinit var query: EditText
    private lateinit var systemOnly: Switch
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

    private val nightMode: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun adaptive(light: Int, dark: Int): Int = if (nightMode) dark else light

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (nightMode) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (nightMode) 0 else
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                if (android.os.Build.VERSION.SDK_INT >= 26) android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        val root = column().apply {
            setBackgroundColor(adaptive(0xFFF2F6F3.toInt(), 0xFF121B17.toInt()))
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(dp(20) + insets.systemWindowInsetLeft,
                    dp(18) + insets.systemWindowInsetTop,
                    dp(20) + insets.systemWindowInsetRight,
                    dp(24) + insets.systemWindowInsetBottom)
                insets
            }
        }
        root.addView(label("应用管理", 32f, bold = true))
        status = label("", 14f)
        val connectionCard = card(root)
        connectionCard.addView(label("Shizuku 连接", 18f, bold = true))
        connectionCard.addView(status)
        connectionCard.addView(action("连接 / 授权") { connect(requestPermission = true) })

        val searchCard = card(root)
        searchCard.addView(label("查找应用", 20f, bold = true))
        query = EditText(this).apply {
            hint = "输入应用名称或包名"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        query.setText(savedInstanceState?.getString("searchQuery").orEmpty())
        searchCard.addView(query)
        systemOnly = Switch(this).apply {
            text = "仅显示系统应用"
            isChecked = savedInstanceState?.getBoolean("systemOnly") == true
            minHeight = dp(48)
        }
        searchCard.addView(systemOnly)
        fun submitSearch() {
            getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(query.windowToken, 0)
            search(if (systemOnly.isChecked) "system" else "list", query.text.toString().trim())
        }
        query.setOnEditorActionListener { _, id, _ ->
            if (id != EditorInfo.IME_ACTION_SEARCH) false else {
                submitSearch()
                true
            }
        }
        searchCard.addView(action("搜索", primary = true) { submitSearch() })
        results = column()
        searchCard.addView(results)

        val advanced = card(root)
        advanced.addView(action("进阶工具") {
            showMenu("进阶工具", listOf(
                "输入完整包名" to { openManualPackage() },
                "ADB Shell 终端" to { showShell() }
            ))
        })
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root, MATCH_PARENT, -2)
        })

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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(adaptive(0xFF173229.toInt(), 0xFFE3F2E9.toInt()))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun surface(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
    }

    private fun card(parent: LinearLayout) = column().apply {
        background = surface(adaptive(Color.WHITE, 0xFF1D2A24.toInt()))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        parent.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, -2).apply {
            topMargin = dp(14)
        })
    }

    private fun action(value: String, primary: Boolean = false, onClick: () -> Unit) =
        Button(this).apply {
            text = value
            isAllCaps = false
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(14), dp(6), dp(14), dp(6))
            val fill = if (primary) adaptive(0xFF175C47.toInt(), 0xFFA5DDBF.toInt())
                else adaptive(0xFFE8F2EC.toInt(), 0xFF2B4237.toInt())
            background = RippleDrawable(ColorStateList.valueOf(if (nightMode && !primary) 0x24FFFFFF else 0x24000000), surface(fill), null)
            setTextColor(if (primary) adaptive(Color.WHITE, 0xFF10291E.toInt())
                else adaptive(0xFF173229.toInt(), 0xFFE3F2E9.toInt()))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, -2).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun openManualPackage() {
        val input = EditText(this).apply {
            hint = "例如 com.oplus.olc"
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this).setTitle("输入完整包名")
            .setView(input).setNegativeButton("取消", null).setPositiveButton("继续", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (!packageNamePattern.matches(name)) {
                    input.error = "请输入有效的完整包名"
                    return@setOnClickListener
                }
                dialog.dismiss()
                openPackage(name, "")
            }
        }
        dialog.show()
    }

    private fun openPackage(name: String, title: String) {
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "查看应用详情" to { run("details", name, done = ::display) },
            "查询后台限制" to { run("background-status", name, done = ::display) }
        )
        if (name != packageName) {
            actions += "限制后台运行" to { change(name, "background-restrict", "限制后台运行") }
            actions += "恢复后台默认设置" to { change(name, "background-reset", "恢复后台默认设置") }
            actions += "更多操作" to {
                showMenu(name, listOf(
                    "停用应用" to { change(name, "disable", "停用应用") },
                    "重新启用" to { change(name, "enable", "重新启用") },
                    "为当前用户卸载" to { change(name, "uninstall", "卸载应用") },
                    "恢复当前用户安装" to { change(name, "restore", "恢复安装") },
                    "查看安装路径" to { run("path", name, done = ::display) }
                ))
            }
        }
        showMenu(if (title.isBlank()) name else "$title\n$name", actions)
    }

    private fun showResult(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("关闭", null).show()
            .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    private fun change(name: String, operation: String, label: String) {
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
        failed: (String) -> Unit = { showResult("操作未完成", it) },
        done: (Bundle) -> Unit
    ) {
        val remote = service
        if (remote == null) {
            failed("请先连接或授权 Shizuku，连接完成后再重试")
            connect()
            return
        }
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
        val summary = label("正在搜索…", 14f)
        results.addView(summary)
        run(operation, failed = {
            if (generation == searchGeneration) summary.text = it
        }) { result ->
            if (generation != searchGeneration) return@run
            if (result.getInt("exit") != 0) {
                summary.text = "搜索未完成"
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
            summary.text = if (matches.isEmpty()) "没有匹配应用" else "${matches.size} 个结果" +
                if (matches.size > limit) "（前 $limit 个，请缩小搜索范围）" else ""
            for ((name, title) in matches.take(limit)) {
                results.addView(action(if (title.isBlank()) name else "$title\n$name") {
                    openPackage(name, title)
                })
            }
        }
    }

    private fun showShell() {
        val dialog = AlertDialog.Builder(this, if (nightMode) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar)
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
        val backgroundStatus = Regex("(?m)^RUN_ANY_IN_BACKGROUND: ([a-z_]+)")
            .find(response)?.groupValues?.get(1)
        val summary = when {
            result.getString("verification") != null -> result.getString("verification").orEmpty()
            backgroundStatus == "ignore" -> "已限制后台运行"
            "Default mode: allow" in response -> "使用系统默认规则，允许后台运行"
            else -> ""
        }
        val message = listOf(
            summary,
            "用户 ${result.getInt("user")} · 退出码 ${result.getInt("exit")}",
            result.getString("command").orEmpty(),
            hint, response.take(30_000)
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        val command = result.getString("command").orEmpty()
        val title = when {
            result.getInt("exit") != 0 -> "操作未完成"
            command.startsWith("pm disable-user ") && "new state: disabled-user" !in response ->
                "未确认应用已停用"
            result.getString("verification")?.startsWith("未能确认") == true -> "未确认设置生效"
            else -> "操作结果"
        }
        showResult(title, message)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("searchQuery", query.text.toString())
        outState.putBoolean("systemOnly", systemOnly.isChecked)
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
