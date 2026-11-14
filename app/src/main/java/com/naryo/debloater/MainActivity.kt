package com.naryo.debloater

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.os.Build
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
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
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Spinner
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.view.View
import android.view.ViewGroup
import android.text.TextWatcher
import android.text.Editable
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
    private data class AppRow(val name: String, val title: String, val kind: String, val state: String)
    private lateinit var results: ListView
    private lateinit var query: EditText
    private lateinit var scope: Spinner
    private lateinit var summary: TextView
    private var inventory: List<AppRow>? = null
    private var inventoryUser = -1
    private var loadingInventory = false
    private val scopes = listOf("已安装", "系统应用", "用户应用", "已停用", "已卸载")
    private var service: IPackageService? = null
    private var binding = false
    private var shellTranscript = ""
    private val shellHistory = mutableListOf<String>()
    private var shellBusy = false
    private var terminalRefresh: (() -> Unit)? = null
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, PackageService::class.java))
            .processNameSuffix("package_service").daemon(false).version(5)
    }
    private val received = Shizuku.OnBinderReceivedListener { connect() }
    private val dead = Shizuku.OnBinderDeadListener {
        service = null
        invalidateInventory()
        binding = false
        status.text = "Shizuku · 已断开"
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { request, grant ->
        if (request == 1001) {
            if (grant == PackageManager.PERMISSION_GRANTED) connect()
            else connectionHelp("未授权", "可在 Shizuku 的授权管理中允许 Debloater。", true)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IPackageService.Stub.asInterface(binder)
            binding = false
            status.text = "Shizuku · 已连接"
            loadInventory()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            invalidateInventory()
            binding = false
            status.text = "Shizuku · 已断开"
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
        val root = column().apply {
            setBackgroundColor(adaptive(0xFFF2F6F3.toInt(), 0xFF121B17.toInt()))
        }
        root.addView(label("应用管理", 32f, bold = true))
        status = label("", 14f).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val connectionRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = surface(adaptive(Color.WHITE, 0xFF1D2A24.toInt()))
            setPadding(dp(12), 0, dp(4), 0)
        }
        root.addView(connectionRow, LinearLayout.LayoutParams(MATCH_PARENT, -2).apply {
            topMargin = dp(8)
        })
        connectionRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        connectionRow.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "连接 / 授权"
            isAllCaps = false
            minimumHeight = dp(48)
            setTextColor(adaptive(0xFF175C47.toInt(), 0xFFA5DDBF.toInt()))
            setOnClickListener { connect(requestPermission = true) }
        })

        query = EditText(this).apply {
            hint = "搜索应用名称或包名"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(savedInstanceState?.getString("searchQuery").orEmpty())
        }
        root.addView(query)
        scope = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, scopes)
            minimumHeight = dp(48)
            contentDescription = "应用分类"
            setSelection(savedInstanceState?.getInt("scope")?.coerceIn(scopes.indices) ?: 0)
        }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(scope, LinearLayout.LayoutParams(0, -2, 1f))
        fun control(text: String, press: () -> Unit) {
            controls.addView(Button(this).apply {
                this.text = text
                isAllCaps = false
                setOnClickListener { press() }
            })
        }
        control("刷新") { loadInventory() }
        control("工具") {
            showMenu("进阶工具", listOf(
                "输入完整包名" to { openManualPackage() },
                "ADB Shell 终端" to { showShell() }
            ))
        }
        root.addView(controls)
        summary = label("连接 Shizuku 后加载应用", 14f)
        summary.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        root.addView(summary)
        results = ListView(this).apply {
            adapter = appAdapter
            setOnItemClickListener { _, _, index, _ ->
                val row = appAdapter.getItem(index)
                openPackage(row.name, row.title, row.state)
            }
        }
        root.addView(results, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderInventory()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        query.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
                true
            } else false
        }
        scope.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = renderInventory()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        root.isFocusableInTouchMode = true
        root.requestFocus()
        setContentView(root)
        fitSystemAreas(window, root, horizontal = 20, vertical = 12)

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

    private fun connectionHelp(state: String, message: String, showHelp: Boolean) {
        status.text = "Shizuku · $state"
        if (showHelp) showResult("Shizuku", message)
    }

    private fun connect(requestPermission: Boolean = false) {
        try {
            when {
                !Shizuku.pingBinder() -> connectionHelp("未启动", "请先通过无线调试或 USB ADB 启动 Shizuku。", requestPermission)
                Shizuku.isPreV11() || Shizuku.getVersion() < 13 -> connectionHelp("需更新", "请更新到 Shizuku 13 或更高版本。", requestPermission)
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                    status.text = "Shizuku · 待授权"
                    if (requestPermission) {
                        if (Shizuku.shouldShowRequestPermissionRationale()) {
                            connectionHelp("未授权", "请在 Shizuku 的授权管理中允许本应用。", true)
                        } else Shizuku.requestPermission(1001)
                    }
                }
                service == null && (!binding || requestPermission) -> {
                    binding = true
                    status.text = "Shizuku · 连接中…"
                    Shizuku.bindUserService(args, connection)
                }
            }
        } catch (error: Exception) {
            binding = false
            connectionHelp("连接失败", error.message ?: "请重新连接 Shizuku。", requestPermission)
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

    private fun openPackage(name: String, title: String, state: String? = null) {
        if (state == "removed") {
            showMenu(name, listOf(
                "恢复当前用户安装" to { change(name, "restore", "恢复安装") },
                "查看应用详情" to { run("details", name, done = ::display) }
            ))
            return
        }
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
                .setPositiveButton(label) { _, _ -> run(operation, name, user) { result ->
                    display(result)
                    if (operation in setOf("disable", "enable", "uninstall", "restore")) loadInventory()
                } }
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

    private val appAdapter = object : BaseAdapter() {
        var rows = emptyList<AppRow>()
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView as? LinearLayout ?: column().apply {
                setPadding(dp(12), dp(9), dp(12), dp(9))
                minimumHeight = dp(64)
                addView(label("", 16f, bold = true))
                addView(label("", 13f))
            }
            val row = getItem(position)
            (cell.getChildAt(0) as TextView).text = row.title.ifBlank { row.name }
            val kind = if (row.kind == "system") "系统应用" else "用户应用"
            val state = when (row.state) {
                "removed" -> "当前用户未安装"
                "disabled" -> "已停用"
                else -> "已启用"
            }
            (cell.getChildAt(1) as TextView).text =
                if (row.title.isBlank()) "$kind · $state" else "${row.name}\n$kind · $state"
            return cell
        }
    }

    private fun invalidateInventory() {
        inventory = null
        loadingInventory = false
        appAdapter.rows = emptyList()
        appAdapter.notifyDataSetChanged()
        summary.text = "连接 Shizuku 后加载应用"
    }

    private fun loadInventory() {
        if (loadingInventory) return
        val connection = service
        loadingInventory = true
        summary.text = "正在读取应用列表…"
        run("inventory", failed = {
            if (service === connection) {
                loadingInventory = false
                summary.text = it
            }
        }) { data ->
            if (service !== connection) return@run
            val rows = data.getStringArrayList("packages").orEmpty()
            val user = data.getInt("user")
            worker.execute {
                @Suppress("DEPRECATION")
                val labels = runCatching {
                    packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                        .associateBy { it.packageName }
                }.getOrDefault(emptyMap())
                val parsed = rows.mapNotNull {
                    val fields = it.split('|', limit = 3)
                    if (fields.size != 3) return@mapNotNull null
                    val (kind, state, name) = fields
                    val title = labels[name]?.let { info ->
                        runCatching { info.loadLabel(packageManager).toString() }.getOrDefault("")
                    }.orEmpty()
                    AppRow(name, title, kind, state)
                }.sortedWith(compareBy<AppRow, String>(String.CASE_INSENSITIVE_ORDER) {
                    it.title.ifBlank { it.name }
                }.thenBy { it.name })
                runOnUiThread {
                    if (!isDestroyed && !isFinishing && service === connection) {
                        loadingInventory = false
                        inventory = parsed
                        inventoryUser = user
                        renderInventory()
                    }
                }
            }
        }
    }

    private fun renderInventory() {
        val apps = inventory ?: return
        val term = query.text.toString().trim()
        val words = term.split(Regex("\\s+")).filter { it.isNotEmpty() }
        appAdapter.rows = apps.filter { row ->
            val inScope = when (scope.selectedItemPosition) {
                1 -> row.kind == "system" && row.state != "removed"
                2 -> row.kind == "user" && row.state != "removed"
                3 -> row.state == "disabled"
                4 -> row.state == "removed"
                else -> row.state != "removed"
            }
            inScope && words.all { row.title.contains(it, true) || row.name.contains(it, true) }
        }
        appAdapter.notifyDataSetChanged()
        results.setSelection(0)
        summary.text = if (appAdapter.count == 0) "没有匹配的应用"
            else "${appAdapter.count} 个应用 · 用户 $inventoryUser"
    }

    @Suppress("DEPRECATION")
    private fun fitSystemAreas(window: Window, root: View, horizontal: Int, vertical: Int) {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val horizontalPadding = dp(horizontal)
        val verticalPadding = dp(vertical)
        root.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        if (Build.VERSION.SDK_INT < 30) {
            root.fitsSystemWindows = true
            window.decorView.systemUiVisibility = if (nightMode) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            return
        }
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.windowInsetsController?.setSystemBarsAppearance(if (nightMode) 0 else appearance, appearance)
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or
                WindowInsets.Type.displayCutout() or WindowInsets.Type.mandatorySystemGestures() or
                WindowInsets.Type.ime())
            view.setPadding(horizontalPadding + safe.left, verticalPadding + safe.top,
                horizontalPadding + safe.right, verticalPadding + safe.bottom)
            WindowInsets.CONSUMED
        }
        root.requestApplyInsets()
    }

    private fun showShell() {
        val dialog = Dialog(this, if (nightMode) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar)
        val layout = column().apply {
            setBackgroundColor(adaptive(0xFFF2F6F3.toInt(), 0xFF121B17.toInt()))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("ADB Shell", 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "关闭"
            setOnClickListener { dialog.dismiss() }
        })
        layout.addView(header)
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
        layout.addView(row)
        terminalRefresh = {
            transcript.text = shellTranscript.ifEmpty { "输入 id 查看 Shell 身份。支持管道，单次等待约 20 秒。\n" }
            execute.isEnabled = !shellBusy
            scroll.post { scroll.scrollTo(0, transcript.height) }
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
        dialog.setContentView(layout)
        dialog.setOnDismissListener { terminalRefresh = null }
        dialog.show()
        dialog.window?.let { terminalWindow ->
            terminalWindow.setLayout(MATCH_PARENT, MATCH_PARENT)
            fitSystemAreas(terminalWindow, layout, horizontal = 12, vertical = 8)
        }
        terminalRefresh?.invoke()
    }

    private fun display(result: Bundle) {
        val response = result.getString("output").orEmpty().trim()
        val hint = when {
            "DELETE_FAILED_INTERNAL_ERROR" in response ->
                "包管理器返回内部卸载错误。这个错误码无法定位原因。请用包详情核对当前用户的 installed/enabled 状态和系统包标记，再核对设备管理策略及设备厂商的限制。"
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
        outState.putInt("scope", scope.selectedItemPosition)
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
