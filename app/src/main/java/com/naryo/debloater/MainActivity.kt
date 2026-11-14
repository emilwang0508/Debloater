package com.naryo.debloater

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.os.Build
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.widget.FrameLayout
import android.widget.ImageButton
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import android.widget.AbsListView
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
import android.widget.ImageView
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val iconWorker = Executors.newSingleThreadExecutor()
    private val icons = LruCache<String, Bitmap>(64)
    private val loadingIcons = mutableSetOf<String>()
    private val missingIcons = mutableSetOf<String>()
    private var matches = emptyList<AppRow>()
    private val pageSize = 30
    private lateinit var status: TextView
    private data class AppRow(val name: String, val title: String, val kind: String, val state: String)
    private lateinit var results: ListView
    private lateinit var query: EditText
    private lateinit var scope: Spinner
    private lateinit var summary: TextView
    private var inventory: List<AppRow>? = null
    private var pendingRefresh = false
    private var inventoryUser: Int? = null
    private var loadingInventory = false
    private val scopes get() = listOf(getString(R.string.installed), getString(R.string.system_apps), getString(R.string.user_apps), getString(R.string.disabled), getString(R.string.uninstalled))
    private var service: IPackageService? = null
    private var binding = false
    private var shellTranscript = ""
    private val shellHistory = mutableListOf<String>()
    private var shellBusy = false
    private var terminalRefresh: (() -> Unit)? = null
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, PackageService::class.java))
            .processNameSuffix("package_service").daemon(false).version(8)
    }
    private val received = Shizuku.OnBinderReceivedListener { connect() }
    private val dead = Shizuku.OnBinderDeadListener {
        service = null
        invalidateInventory()
        binding = false
        status.text = getString(R.string.disconnected)
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { request, grant ->
        if (request == 1001) {
            if (grant == PackageManager.PERMISSION_GRANTED) connect()
            else connectionHelp(getString(R.string.unauthorized), getString(R.string.authorization_denied), true)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IPackageService.Stub.asInterface(binder)
            binding = false
            status.text = getString(R.string.connected)
            run("user") { info ->
                if (inventoryUser != info.getInt("user", -1)) {
                    inventory = null
                    matches = emptyList()
                    appAdapter.rows = emptyList()
                    appAdapter.notifyDataSetChanged()
                    loadInventory()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            invalidateInventory()
            binding = false
            status.text = getString(R.string.disconnected)
        }
    }

    private val nightMode: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun adaptive(light: Int, dark: Int): Int = if (nightMode) dark else light

    override fun attachBaseContext(base: Context) {
        val preference = base.getSharedPreferences("settings", MODE_PRIVATE)
        val systemLanguage = base.resources.configuration.locales[0].language
        val selected = preference.getString("language", "system")
        val locale = when (selected) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "en" -> Locale.ENGLISH
            else -> if (systemLanguage == "zh") Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
        }
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (nightMode) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        val root = column().apply {
            setBackgroundColor(adaptive(0xFFF2F6F3.toInt(), 0xFF121B17.toInt()))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageButton(this, null, android.R.attr.borderlessButtonStyle).apply {
            setImageResource(R.drawable.ic_menu)
            setColorFilter(adaptive(0xFF173229.toInt(), 0xFFE3F2E9.toInt()))
            contentDescription = getString(R.string.open_menu)
            setOnClickListener { openDrawer() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label(getString(R.string.app_management), 26f, bold = true), LinearLayout.LayoutParams(-2, -2))
        header.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(10) })
        root.addView(header)
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
            text = getString(R.string.connect)
            isAllCaps = false
            minimumHeight = dp(48)
            setTextColor(adaptive(0xFF175C47.toInt(), 0xFFA5DDBF.toInt()))
            setOnClickListener { connect(requestPermission = true) }
        })

        query = EditText(this).apply {
            hint = getString(R.string.search_hint)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(savedInstanceState?.getString("searchQuery").orEmpty())
        }
        root.addView(query)
        scope = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, scopes)
            minimumHeight = dp(48)
            contentDescription = getString(R.string.app_filter)
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
        control(getString(R.string.refresh)) { loadInventory() }
        control(getString(R.string.tools)) {
            showMenu(getString(R.string.advanced_tools), listOf(
                getString(R.string.enter_package) to { openManualPackage() },
                getString(R.string.shell_terminal) to { showShell() }
            ))
        }
        root.addView(controls)
        summary = label(getString(R.string.connect_to_load), 14f)
        summary.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        results = ListView(this).apply {
            adapter = appAdapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                private var appendPending = false

                override fun onScrollStateChanged(view: AbsListView?, state: Int) = Unit

                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                    if (appendPending || visible == 0 || total >= matches.size || first + visible < total - 5) return
                    val currentMatches = matches
                    appendPending = true
                    post {
                        appendPending = false
                        if (isDestroyed || matches !== currentMatches) return@post
                        if (lastVisiblePosition >= appAdapter.count - 6 && appAdapter.count < matches.size) {
                            showAppPage(appAdapter.count + pageSize)
                        }
                    }
                }
            })
            setOnItemClickListener { _, _, index, _ ->
                val row = appAdapter.getItem(index)
                openPackage(row.name, row.title, row.state)
            }
        }
        val listArea = FrameLayout(this)
        listArea.addView(results, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        listArea.addView(summary, FrameLayout.LayoutParams(MATCH_PARENT, -2, Gravity.CENTER))
        summary.gravity = Gravity.CENTER
        results.emptyView = summary
        root.addView(listArea, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderInventory()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        query.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                if (inventory == null && service != null) loadInventory()
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

        restoreInventory {
            Shizuku.addBinderDeadListener(dead)
            Shizuku.addRequestPermissionResultListener(permission)
            Shizuku.addBinderReceivedListenerSticky(received)
            connect()
        }
    }

    private fun openDrawer() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
        val dialog = Dialog(this, if (nightMode) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar)
        val overlay = FrameLayout(this)
        val scrim = View(this).apply {
            setBackgroundColor(0x88000000.toInt())
            contentDescription = getString(R.string.close_menu)
            setOnClickListener { dialog.dismiss() }
        }
        overlay.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val panel = column().apply {
            setBackgroundColor(adaptive(0xFFF9FCFA.toInt(), 0xFF1D2A24.toInt()))
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isClickable = true
        }
        val width = minOf(dp(336), (resources.displayMetrics.widthPixels * 0.82f).toInt())
        overlay.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(panel)
        }, FrameLayout.LayoutParams(width, MATCH_PARENT, Gravity.LEFT))
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) },
            LinearLayout.LayoutParams(dp(40), dp(40)))
        brand.addView(label("Debloater", 24f, bold = true))
        panel.addView(brand)
        val preferences = getSharedPreferences("settings", MODE_PRIVATE)
        val keys = listOf("system", "zh", "en")
        val names = arrayOf(getString(R.string.follow_system), "简体中文", "English")
        val selected = keys.indexOf(preferences.getString("language", "system")).coerceAtLeast(0)
        panel.addView(action(getString(R.string.language_value, names[selected])) {
            dialog.dismiss()
            AlertDialog.Builder(this).setTitle(R.string.language)
                .setSingleChoiceItems(names, selected) { picker, index ->
                    picker.dismiss()
                    if (index != selected) {
                        preferences.edit().putString("language", keys[index]).apply()
                        recreate()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })
        dialog.setContentView(overlay)
        dialog.show()
        dialog.window?.let {
            it.setLayout(MATCH_PARENT, MATCH_PARENT)
            fitSystemAreas(it, overlay, horizontal = 0, vertical = 0)
        }
        panel.translationX = -width.toFloat()
        panel.animate().translationX(0f).setDuration(180).start()
    }

    private fun showMenu(title: String, items: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this).setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, index -> items[index].second() }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun connectionHelp(state: String, message: String, showHelp: Boolean) {
        status.text = "Shizuku · $state"
        if (showHelp) showResult("Shizuku", message)
    }

    private fun connect(requestPermission: Boolean = false) {
        try {
            when {
                !Shizuku.pingBinder() -> connectionHelp(getString(R.string.not_running), getString(R.string.start_shizuku), requestPermission)
                Shizuku.isPreV11() || Shizuku.getVersion() < 13 -> connectionHelp(getString(R.string.update_required), getString(R.string.update_shizuku), requestPermission)
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                    status.text = getString(R.string.awaiting_authorization)
                    if (requestPermission) {
                        if (Shizuku.shouldShowRequestPermissionRationale()) {
                            connectionHelp(getString(R.string.unauthorized), getString(R.string.allow_in_shizuku), true)
                        } else Shizuku.requestPermission(1001)
                    }
                }
                service == null && (!binding || requestPermission) -> {
                    binding = true
                    status.text = getString(R.string.connecting)
                    Shizuku.bindUserService(args, connection)
                }
            }
        } catch (error: Exception) {
            binding = false
            connectionHelp(getString(R.string.connection_failed), error.message ?: getString(R.string.reconnect), requestPermission)
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
            hint = getString(R.string.package_example)
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.enter_package))
            .setView(input).setNegativeButton(getString(R.string.cancel), null).setPositiveButton(getString(R.string.continue_action), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (!packageNamePattern.matches(name)) {
                    input.error = getString(R.string.invalid_package_hint)
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
                getString(R.string.restore_for_user) to { change(name, "restore", getString(R.string.restore)) },
                getString(R.string.app_details) to { run("details", name, done = ::display) }
            ))
            return
        }
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.app_details) to { run("details", name, done = ::display) },
            getString(R.string.permissions_notifications) to { showPermissionSettings(name) },
            getString(R.string.background_status) to { run("background-status", name, done = ::display) }
        )
        if (name != packageName) {
            actions += getString(R.string.restrict_background) to { change(name, "background-restrict", getString(R.string.restrict_background)) }
            actions += getString(R.string.reset_background) to { change(name, "background-reset", getString(R.string.reset_background)) }
            actions += getString(R.string.freeze) to { change(name, "freeze", getString(R.string.freeze)) }
            actions += getString(R.string.unfreeze) to { change(name, "unfreeze", getString(R.string.unfreeze)) }
            actions += getString(R.string.more_actions) to {
                showMenu(name, listOf(
                    getString(R.string.force_stop) to { change(name, "force-stop", getString(R.string.force_stop)) },
                    getString(R.string.disable) to { change(name, "disable", getString(R.string.disable)) },
                    getString(R.string.enable) to { change(name, "enable", getString(R.string.enable)) },
                    getString(R.string.uninstall_for_user) to { change(name, "uninstall", getString(R.string.uninstall)) },
                    getString(R.string.restore_for_user) to { change(name, "restore", getString(R.string.restore)) },
                    getString(R.string.installation_path) to { run("path", name, done = ::display) }
                ))
            }
        }
        showMenu(if (title.isBlank()) name else "$title\n$name", actions)
    }

    private fun showPermissionSettings(name: String) {
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", name, null))
        val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, name)
        showMenu(getString(R.string.permissions_notifications), listOf(
            getString(R.string.permission_settings) to { launchSettings(appInfo) },
            getString(R.string.notification_settings) to { launchSettings(notifications, appInfo) }
        ))
    }

    private fun launchSettings(intent: Intent, fallback: Intent? = null) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            if (fallback != null) launchSettings(fallback)
            else showResult(getString(R.string.settings_unavailable), getString(R.string.find_in_settings))
        }
    }

    private fun showResult(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton(getString(R.string.close), null).show()
            .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    private fun change(name: String, operation: String, label: String) {
        run("user") { info ->
            val user = info.getInt("user", -1)
            if (inventoryUser != null && inventoryUser != user) {
                loadInventory()
                showResult(getString(R.string.operation_incomplete), getString(R.string.user_changed))
                return@run
            }
            val effect = when (operation) {
                "freeze" -> getString(R.string.freeze_effect)
                "force-stop" -> getString(R.string.force_stop_effect)
                else -> getString(R.string.component_effect)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_action, label))
                .setMessage(getString(R.string.action_target, name, user, effect))
                .setNegativeButton(getString(R.string.cancel), null)
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
        failed: (String) -> Unit = { showResult(getString(R.string.operation_incomplete), it) },
        done: (Bundle) -> Unit
    ) {
        val remote = service
        if (remote == null) {
            failed(getString(R.string.connect_to_retry))
            connect()
            return
        }
        worker.execute {
            val result = runCatching { remote.execute(operation, name, user, resources.configuration.locales[0].language) }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) result.fold(done) {
                    failed(getString(R.string.execution_failed, it.message ?: it.javaClass.simpleName))
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
            val cell = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                minimumHeight = dp(64)
                addView(ImageView(this@MainActivity).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
                addView(column().apply {
                    addView(label("", 16f, bold = true))
                    addView(label("", 13f))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val row = getItem(position)
            bindAppIcon(cell.getChildAt(0) as ImageView, row.name)
            val text = cell.getChildAt(1) as LinearLayout
            (text.getChildAt(0) as TextView).text = row.title.ifBlank { row.name }
            val kind = if (row.kind == "system") getString(R.string.system_apps) else getString(R.string.user_apps)
            val state = when (row.state) {
                "removed" -> getString(R.string.not_installed_for_user)
                "disabled" -> getString(R.string.disabled)
                else -> getString(R.string.enabled)
            }
            (text.getChildAt(1) as TextView).text =
                if (row.title.isBlank()) "$kind · $state" else "${row.name}\n$kind · $state"
            return cell
        }
    }

    private fun bindAppIcon(view: ImageView, name: String) {
        view.tag = name
        val cached = icons.get(name)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageResource(android.R.drawable.sym_def_app_icon)
        if (name in missingIcons || !loadingIcons.add(name)) return
        val size = dp(40)
        iconWorker.execute {
            val bitmap = runCatching {
                val drawable = packageManager.getApplicationIcon(name)
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(Canvas(it))
                }
            }.getOrNull()
            runOnUiThread {
                loadingIcons.remove(name)
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (bitmap == null) {
                    missingIcons.add(name)
                    return@runOnUiThread
                }
                icons.put(name, bitmap)
                for (index in 0 until results.childCount) {
                    val row = results.getChildAt(index) as? LinearLayout
                    val icon = row?.getChildAt(0) as? ImageView
                    if (icon?.tag == name) icon.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun invalidateInventory() {
        loadingInventory = false
        pendingRefresh = false
        summary.text = getString(R.string.connect_to_load)
    }

    private fun restoreInventory(ready: () -> Unit) {
        val saved = getSharedPreferences("inventory", MODE_PRIVATE)
            .getString("rows_${android.os.Process.myUserHandle().hashCode()}_${resources.configuration.locales[0].language}", null)
        if (saved == null) {
            ready()
            return
        }
        val generation = inventory
        worker.execute {
            val rows = runCatching {
                val data = JSONArray(saved)
                List(data.length()) { index ->
                    val item = data.getJSONObject(index)
                    AppRow(item.getString("name"), item.getString("title"),
                        item.getString("kind"), item.getString("state"))
                }
            }.getOrNull()
            runOnUiThread {
                if (!isDestroyed && inventory === generation && rows != null) {
                    inventory = rows
                    inventoryUser = android.os.Process.myUserHandle().hashCode()
                    renderInventory()
                }
                if (!isDestroyed && !isFinishing) ready()
            }
        }
    }

    private fun saveInventory(user: Int, rows: List<AppRow>) {
        val data = JSONArray()
        for (row in rows) data.put(JSONObject().put("name", row.name).put("title", row.title)
            .put("kind", row.kind).put("state", row.state))
        getSharedPreferences("inventory", MODE_PRIVATE).edit().putString("rows_${user}_${resources.configuration.locales[0].language}", data.toString()).apply()
    }

    private fun finishInventoryLoad() {
        loadingInventory = false
        if (pendingRefresh) {
            pendingRefresh = false
            loadInventory()
        }
    }

    private fun loadInventory() {
        if (loadingInventory) {
            pendingRefresh = true
            return
        }
        val connection = service
        loadingInventory = true
        missingIcons.clear()
        summary.text = getString(R.string.loading_apps)
        run("inventory", failed = {
            if (service === connection) {
                summary.text = it
                finishInventoryLoad()
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
                saveInventory(user, parsed)
                runOnUiThread {
                    if (!isDestroyed && !isFinishing && service === connection) {
                        inventory = parsed
                        inventoryUser = user
                        renderInventory()
                        finishInventoryLoad()
                    }
                }
            }
        }
    }

    private fun renderInventory() {
        val apps = inventory ?: return
        val term = query.text.toString().trim()
        val words = term.split(Regex("\\s+")).filter { it.isNotEmpty() }
        matches = apps.filter { row ->
            val inScope = when (scope.selectedItemPosition) {
                1 -> row.kind == "system" && row.state != "removed"
                2 -> row.kind == "user" && row.state != "removed"
                3 -> row.state == "disabled"
                4 -> row.state == "removed"
                else -> row.state != "removed"
            }
            inScope && words.all { row.title.contains(it, true) || row.name.contains(it, true) }
        }
        showAppPage(pageSize)
        results.setSelection(0)
    }

    private fun showAppPage(limit: Int) {
        appAdapter.rows = matches.take(limit)
        appAdapter.notifyDataSetChanged()
        summary.text = getString(R.string.no_matches)
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
            text = getString(R.string.close)
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
            hint = getString(R.string.shell_hint)
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            filters = arrayOf(android.text.InputFilter.LengthFilter(8192))
        }
        val row = LinearLayout(this)
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val execute = Button(this).apply { text = getString(R.string.execute) }
        row.addView(execute)
        val controls = LinearLayout(this)
        var historyIndex = shellHistory.size
        fun control(label: String, action: () -> Unit) {
            controls.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        control(getString(R.string.previous)) {
            if (historyIndex > 0) input.setText(shellHistory[--historyIndex])
            input.setSelection(input.length())
        }
        control(getString(R.string.next)) {
            if (historyIndex < shellHistory.size) historyIndex++
            input.setText(shellHistory.getOrNull(historyIndex).orEmpty())
            input.setSelection(input.length())
        }
        control(getString(R.string.clear)) {
            shellTranscript = ""
            terminalRefresh?.invoke()
        }
        layout.addView(controls)
        layout.addView(row)
        terminalRefresh = {
            transcript.text = shellTranscript.ifEmpty { getString(R.string.shell_intro) }
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
                if (result.getBoolean("truncated") || response.length > 30_000) append(getString(R.string.output_truncated))
                if (result.getBoolean("timedOut")) append(getString(R.string.execution_timeout))
                append(getString(R.string.shell_exit, result.getInt("exit")))
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
                getString(R.string.uninstall_internal)
            "DELETE_FAILED_DEVICE_POLICY_MANAGER" in response || "DELETE_FAILED_OWNER_BLOCKED" in response ->
                getString(R.string.uninstall_policy)
            "DELETE_FAILED_USER_RESTRICTED" in response -> getString(R.string.uninstall_user_restricted)
            "SecurityException" in response || "Permission Denial" in response ->
                getString(R.string.permission_denied)
            result.getInt("exit") == 124 -> getString(R.string.operation_timeout)
            else -> ""
        }
        val backgroundStatus = Regex("(?m)^RUN_ANY_IN_BACKGROUND: ([a-z_]+)")
            .find(response)?.groupValues?.get(1)
        val summary = when {
            result.getString("verification") != null -> result.getString("verification").orEmpty()
            result.getInt("exit") == 0 && result.getString("command").orEmpty().startsWith("am force-stop ") ->
                getString(R.string.force_stop_accepted)
            backgroundStatus == "ignore" -> getString(R.string.background_restricted)
            "Default mode: allow" in response -> getString(R.string.background_default_allowed)
            else -> ""
        }
        val message = listOf(
            summary,
            getString(R.string.user_exit, result.getInt("user"), result.getInt("exit")),
            result.getString("command").orEmpty(),
            hint, response.take(30_000)
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        val command = result.getString("command").orEmpty()
        val title = when {
            result.getInt("exit") != 0 -> getString(R.string.operation_incomplete)
            command.startsWith("pm disable-user ") && "new state: disabled-user" !in response ->
                getString(R.string.disable_unverified)
            result.containsKey("verified") && !result.getBoolean("verified") -> getString(R.string.change_unverified)
            else -> getString(R.string.operation_result)
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
        iconWorker.shutdownNow()
        worker.shutdown()
        super.onDestroy()
    }
}
