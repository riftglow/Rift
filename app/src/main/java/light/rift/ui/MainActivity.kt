package light.rift.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.preference.PreferenceDataStore
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import eightbitlab.com.blurview.BlurViewFacade
import light.rift.BuildConfig
import light.rift.GroupType
import light.rift.Key
import light.rift.R
import light.rift.RiftApp
import light.rift.aidl.IRiftAppService
import light.rift.aidl.SpeedDisplayData
import light.rift.aidl.TrafficData
import light.rift.bg.BaseService
import light.rift.bg.RiftConnection
import light.rift.database.RiftDatabase
import light.rift.database.DataStore
import light.rift.database.GroupManager
import light.rift.database.ProfileManager
import light.rift.database.ProxyGroup
import light.rift.database.SubscriptionBean
import light.rift.database.preference.OnPreferenceDataStoreChangeListener
import light.rift.databinding.LayoutMainBinding
import light.rift.fmt.AbstractBean
import light.rift.fmt.KryoConverters
import light.rift.group.GroupInterfaceAdapter
import light.rift.group.GroupUpdater
import light.rift.ktx.RiftAlertDialogBuilder
import light.rift.ktx.alert
import light.rift.ktx.getColorAttr
import light.rift.ktx.isPlay
import light.rift.ktx.isPreview
import light.rift.ktx.launchCustomTab
import light.rift.ktx.onMainDispatcher
import light.rift.ktx.parseProxies
import light.rift.ktx.readableMessage
import light.rift.ktx.runOnDefaultDispatcher
import light.rift.core.utils.Util

class MainActivity : ThemedActivity(),
    RiftConnection.Callback,
    OnPreferenceDataStoreChangeListener {

    lateinit var binding: LayoutMainBinding

    // 抽屉当前选中行（2026-11-10 第 19 项手写行）：displayFragmentWithId 与
    // Activity 重建后都经 updateDrawerItemSelection 更新，点已在当前页的行只关抽屉。
    private var selectedDrawerId: Int = R.id.nav_configuration

    // 底栏玻璃效果（2026-11-19 -34）：BlurView 4.0 的 setupWith 只跑一次，
    // 存 facade 备用；开关切换只改显隐与栏背景。
    private var glassFacade: BlurViewFacade? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 应用内更新的安装授权 launcher（0.2.29 -26）：必须在 onStart 前注册。
        AppUpdater.registerInstallLauncher(this)

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        // 抽屉：手写行（2026-11-10 第 19 项，OwnBox 式），不用 NavigationView，
        // 旧 nav_view_black 也随之去掉，只剩一个抽屉容器。
        // 抽屉宽按本机算：min(280dp, 屏宽 × 0.64)，两台机占屏比例一致。
        // 之前 min(280dp, 屏宽 - 56dp) 两台都是 280dp，但 15 屏小、占比更大，
        // 看上去更宽更笨重（2026-11-15）。按比例后 14 Pro 仍 280dp，15 约 267dp。
        // 320dp 对这几个短词太宽了，280dp 更像抽屉（-7 改）。
        val drawerWidth = minOf(
            (280 * resources.displayMetrics.density).toInt(),
            (resources.displayMetrics.widthPixels * 0.64).toInt(),
        )
        binding.navView.layoutParams = binding.navView.layoutParams.apply { width = drawerWidth }
        // 抽屉行点按：已在当前页只关抽屉，否则切页（与旧 NavigationView 行为一致）。
        listOf(
            binding.navConfiguration to R.id.nav_configuration,
            binding.navGroup to R.id.nav_group,
            binding.navRoute to R.id.nav_route,
            binding.navLog to R.id.nav_log,
            binding.navDashboard to R.id.nav_dashboard,
            binding.navConnections to R.id.nav_connections,
            binding.navUsage to R.id.nav_usage,
            binding.navTools to R.id.nav_tools,
            binding.navAbout to R.id.nav_about,
            binding.navSettings to R.id.nav_settings,
        ).forEach { (row, id) -> row.setOnClickListener { onDrawerRowClicked(id) } }

        // 抽屉头留白：nav_view 已有 fitsSystemWindows，系统已把状态栏高度
        // 算进容器顶部内边距，这里只补 12dp 缝。之前把状态栏高度又加了一遍，
        // 头顶空出一大片（-7 修）。
        val headerView = binding.navHeader
        val gap = (12 * resources.displayMetrics.density).toInt()
        headerView.root.updatePadding(top = gap)

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        } else {
            // Activity 重建（如切黑白模式）：FragmentManager 会自动恢复当前页，
            // 但 displayFragment() 不会再跑，底栏/FAB 就留在默认的显示状态，
            // 在非节点页上一直挂着，直到下次切页面才藏回去。按恢复的页面重套一次显隐。
            // post 到布局后才跑：BottomAppBar 的 performHide 要拿实测高度，
            // onCreate 里高度还是 0，藏了等于没藏。
            val current = supportFragmentManager.findFragmentById(R.id.fragment_holder)
            if (current is ToolbarFragment) {
                binding.stats.post { applyStatsBarVisibility(current) }
                // Activity 重建时 FragmentManager 恢复当前页，抽屉选中态也要跟着恢复，
                // 否则还停在默认的配置行。非抽屉页（如设置页打开的日志）不映射，保持默认选中。
                updateDrawerItemSelection(idForFragment(current))
            }
        }
        onBackPressedDispatcher.addCallback {
            // 返回键：节点页回桌面；分类子页回设置总表；其他页回节点页。
            val current = supportFragmentManager.findFragmentById(R.id.fragment_holder)
            when {
                current is ConfigurationFragment -> moveTaskToBack(true)
                current is SettingsCategoryFragment -> displayFragment(SettingsFragment())
                else -> displayFragmentWithId(R.id.nav_configuration)
            }
        }

        binding.fab.setOnClickListener {
            if (DataStore.serviceState.canStop) RiftApp.stopService() else connect.launch(
                null
            )
        }
        binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }
        // 点底栏节点名回到节点列表（Hiddify/Karing 同款入口）；点底栏其他
        // 地方仍是测速。节点名自己有点击监听时，底栏的点击不会被触发。
        binding.stats.findViewById<TextView>(R.id.node).setOnClickListener {
            // 已在节点列表就什么都不做：否则每次点都 replace 重建整个
            // 列表，旧列表未拆完、新列表已开始布局 = 重影闪。
            val current = supportFragmentManager.findFragmentById(R.id.fragment_holder)
            if (current !is ConfigurationFragment) displayFragmentWithId(R.id.nav_configuration)
        }

        setContentView(binding.root)
        applyGlassBottomBar()
        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        refreshNavMenu(DataStore.enableClashAPI)

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                //动态申请
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }

        if (isPreview) {
            RiftAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // 应用内更新每天自动查一次（0.2.29 -26）：静默，有更新才弹对话框。
        AppUpdater.checkDaily(this)
    }

    fun refreshNavMenu(clashApi: Boolean) {
        // 实时连接入口只在 Clash API 开时出现（行隐藏，不切页）。
        binding.navConnections.isVisible = clashApi
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_group)

            RiftAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            RiftAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    private fun onDrawerRowClicked(@IdRes id: Int) {
        if (id == selectedDrawerId) {
            binding.drawerLayout.closeDrawers()
        } else {
            displayFragmentWithId(id)
        }
    }

    // 抽屉选中态：跟当前页走，不只在初次打开时标一次。
    // 切页（displayFragmentWithId）与 Activity 重建后都调这里。
    private fun updateDrawerItemSelection(@IdRes selectedId: Int) {
        selectedDrawerId = selectedId
        // 图标/文字色沿用抽屉旧选择器（navigation_icon.xml）：选中用主文字色，
        // 未选中用 textColorPrimary，与 NavigationView 时代一致。
        val rowColors = ContextCompat.getColorStateList(this, R.color.navigation_icon)
        val selectedColor = rowColors?.getColorForState(
            intArrayOf(android.R.attr.state_checked), Color.BLACK
        ) ?: Color.BLACK
        val unselectedColor = rowColors?.getColorForState(intArrayOf(), Color.BLACK) ?: Color.BLACK
        for (row in drawerRows()) {
            val selected = row.id == selectedId
            row.isSelected = selected
            row.background = drawerRowBackground(selected)
            val color = if (selected) selectedColor else unselectedColor
            (row.getChildAt(0) as? ImageView)?.drawable?.mutate()?.setTint(color)
            (row.getChildAt(1) as? TextView)?.setTextColor(color)
        }
    }

    // 卡片式抽屉：行在 MaterialCardView 内（卡片 > 纵向容器 > 行），组头是 TextView。
    // 直接遍历 drawerMenuItems 的子项会拿到卡片而非行，故下钻一层收集全部导航行。
    private fun drawerRows(): List<LinearLayout> {
        val rows = mutableListOf<LinearLayout>()
        for (i in 0 until binding.drawerMenuItems.childCount) {
            val card = binding.drawerMenuItems.getChildAt(i) as? MaterialCardView ?: continue
            val container = card.getChildAt(0) as? LinearLayout ?: continue
            for (j in 0 until container.childCount) {
                val row = container.getChildAt(j) as? LinearLayout ?: continue
                if (row.id != View.NO_ID) rows.add(row)
            }
        }
        return rows
    }

    // 选中行 12dp 圆角淡底（color_rift_drawer_selected，白天 #FDEBD6、夜间 #2A2113），
    // 未选中透明；按压水波纹限制在圆角内。12dp 是嵌在 16dp 卡片内的内层圆角。
    private fun drawerRowBackground(selected: Boolean): RippleDrawable {
        val fillColor = if (selected) {
            ContextCompat.getColor(this, R.color.color_rift_drawer_selected)
        } else {
            Color.TRANSPARENT
        }
        val fill = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(fillColor)
        }
        val ripple = ColorUtils.setAlphaComponent(getColorAttr(R.attr.primaryOrTextPrimary), 20)
        return RippleDrawable(ColorStateList.valueOf(ripple), fill, null)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // Fragment 到抽屉行的映射（Activity 重建后恢复选中态用；非抽屉页返回当前选中，保持不动）。
    private fun idForFragment(fragment: Any?): Int = when (fragment) {
        is ConfigurationFragment -> R.id.nav_configuration
        is GroupFragment -> R.id.nav_group
        is RouteFragment -> R.id.nav_route
        is SettingsFragment -> R.id.nav_settings
        is SettingsCategoryFragment -> R.id.nav_settings
        is LogcatFragment -> R.id.nav_log
        is WebviewFragment -> R.id.nav_dashboard
        is ConnectionsFragment -> R.id.nav_connections
        is UsageFragment -> R.id.nav_usage
        is ToolsFragment -> R.id.nav_tools
        is AboutFragment -> R.id.nav_about
        else -> selectedDrawerId
    }


    // 底栏/FAB 显隐：节点页显示，其余页按 showBottomBar 设置决定。
    // displayFragment() 与 onCreate()（Activity 重建时）共用。
    private fun applyStatsBarVisibility(fragment: ToolbarFragment) {
        if (fragment is ConfigurationFragment) {
            binding.stats.allowShow = true
            // S-U5: allowShow alone does not bring a hidden bar
            // back — without performShow() it stays hidden until
            // the next scroll.
            binding.stats.performShow()
            binding.fab.show()
        } else if (!DataStore.showBottomBar) {
            binding.stats.allowShow = false
            binding.stats.performHide()
            binding.fab.hide()
        }
    }

    // 底栏玻璃效果（2026-11-19 -34）：BlurView 4.0，模糊目标是包住列表页的
    // BlurTarget（layout_main）。只在 Android 12+（RenderEffect 硬件路径）
    // 且非低内存设备上开；以下一律回落实色底栏。开关在设置 → 其他 → 安全与显示，
    // 改动即时生效（onPreferenceDataStoreChanged）；昼夜切换重建 Activity，
    // 遮罩色跟着新主题重设。模糊半径 5（× 默认缩放 4 ≈ 效果图的 20）。
    private fun applyGlassBottomBar() {
        val lowRam = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        val enabled = DataStore.glassEffect &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !lowRam
        binding.stats.setGlassEnabled(enabled)
        binding.statsBlur.isVisible = enabled
        if (enabled && glassFacade == null) {
            glassFacade = binding.statsBlur.setupWith(binding.blurTarget)
                .setBlurRadius(5f)
                .setOverlayColor(ContextCompat.getColor(this, R.color.color_rift_glass_overlay))
        }
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        applyStatsBarVisibility(fragment)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }

            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_log -> displayFragment(LogcatFragment())
            R.id.nav_dashboard -> displayFragment(WebviewFragment())
            R.id.nav_connections -> displayFragment(ConnectionsFragment())
            R.id.nav_usage -> displayFragment(UsageFragment())
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_about -> displayFragment(AboutFragment())

            else -> return false
        }
        updateDrawerItemSelection(id)
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
        profileName: String? = null,
    ) {
        DataStore.serviceState = state

        binding.fab.changeState(state, DataStore.serviceState, animate)
        binding.stats.changeState(state, profileName)
        if (state == BaseService.State.Connected) {
            // 底栏第二行追加出口 IP（OwnBox 式常驻显示）。静默失败：
            // 查不到就只显示节点名，不打扰用户。
            // 底栏第一行右端显示当前节点延时 + 重测（设计图）。
            runOnDefaultDispatcher {
                val info = EgressIp.fetch()
                val ping = runCatching {
                    RiftDatabase.proxyDao.getById(DataStore.selectedProxy)?.ping ?: 0
                }.getOrDefault(0)
                onMainDispatcher {
                    if (!isFinishing && !isDestroyed) {
                        binding.stats.setExitIp(info?.ip, info?.countryCode)
                        binding.stats.setConnectedDelay(ping)
                        // 批三第 16 项：同一份出口 IP 顺手给主页状态卡，不再多查一次。
                        currentConfigurationFragment()?.onExitIpChanged(info?.ip, info?.countryCode, info?.city)
                    }
                }
            }
        } else {
            // 断开时清掉状态卡的出口 IP（底栏由 StatsBar.changeState 自己清）。
            currentConfigurationFragment()?.onExitIpChanged(null, null, null)
        }
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.isShown) {
                anchorView = binding.fab
            }
            // TODO
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true, profileName)
    }

    val connection = RiftConnection(RiftConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: IRiftAppService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    // 批三第 16 项：主页状态卡的数据源。速度/节点名/出口 IP 由这里转发给当前
    // 的 ConfigurationFragment（在主页时才有，其他页返回 null，不做事）。
    private fun currentConfigurationFragment(): ConfigurationFragment? =
        supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
        currentConfigurationFragment()?.onSpeedUpdate(stats.txRateProxy, stats.rxRateProxy)
    }

    override fun cbTrafficUpdate(data: TrafficData) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
            val name = ProfileManager.getProfile(id)?.displayName()
            onMainDispatcher {
                binding.stats.setNodeName(name)
                currentConfigurationFragment()?.onNodeChanged(id, name)
            }
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            // 通知栏切分流（0.2.21）：routeMode 在后台进程改了，首页控件
            // 不会自动跟上，这里推给当前节点页重刷选中态。
            Key.ROUTE_MODE -> currentConfigurationFragment()?.refreshRouteMode()
            Key.GLASS_EFFECT -> applyGlassBottomBar()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        RiftApp.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(RiftConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(RiftConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                // 焦点落到当前选中行，方向键可直接在行间移动
                //（旧 NavigationView 是焦点容器，现在行自己接焦点）。
                binding.drawerMenuItems.findViewById<View>(selectedDrawerId)?.requestFocus()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}
