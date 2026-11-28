package light.rift.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.annotation.StringRes
import androidx.annotation.XmlRes
import androidx.core.app.ActivityCompat
import androidx.preference.*
import light.rift.Key
import light.rift.R
import light.rift.BootReceiver
import light.rift.RiftApp
import light.rift.bg.AutoSpeedTest
import light.rift.core.utils.Util
import light.rift.database.DataStore
import light.rift.database.preference.EditTextPreferenceModifiers
import light.rift.ktx.*
import light.rift.utils.Theme
import light.rift.core.ui.*

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    // 设置分类（2026-11-11 -7）：长设置页拆成 7 个子页，每类一个 XML。
    // 子页只加载自己的 XML，所以下面所有 findPreference 都判空——某类页里
    // 没有的偏好就是 null，只跳过、不设监听、不崩溃。
    enum class Category(@XmlRes val xmlRes: Int, @StringRes val titleRes: Int) {
        GENERAL(R.xml.settings_general, R.string.general_settings),
        ROUTE(R.xml.settings_route, R.string.cag_route),
        DNS(R.xml.settings_dns, R.string.cag_dns),
        HYSTERIA2(R.xml.settings_hysteria2, R.string.cag_hysteria2),
        ANYTLS(R.xml.settings_anytls, R.string.cag_anytls),
        INBOUND(R.xml.settings_inbound, R.string.inbound_settings),
        MISC(R.xml.settings_misc, R.string.cag_misc),
    }

    companion object {
        private const val ARG_CATEGORY = "category"

        fun newInstance(category: Category) = SettingsPreferenceFragment().apply {
            arguments = Bundle().apply { putString(ARG_CATEGORY, category.name) }
        }
    }

    private lateinit var isProxyApps: SwitchPreference

    private lateinit var globalCustomConfig: EditConfigPreference


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
        // 设置子页卡片化（2026-11-15）：按 PreferenceCategory 把同组偏好画进白圆角卡片。
        listView.addItemDecoration(SettingsCardItemDecoration(requireContext()))
        // 关掉框架自带的分隔线（2026-11-15，深度审核根因）：PreferenceFragmentCompat
        // 会在每行下方画一条 1dp #1F000000 的 DividerDecoration（onDrawOver，盖在最上层），
        // 每组末行下面那条正好落在卡片底边上，横贯全宽——就是用户说的那条线。
        // 卡片装饰六版改法都动不到它。卡内行间分隔线由 SettingsCardItemDecoration 自己画，不受影响。
        setDivider(null)
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    // 设置子页卡片化（2026-11-15）：分组标题套小标题布局；可跳转/弹窗的偏好
    // （下拉、输入框、自定义配置等）右侧加小箭头。开关自带 Switch、不设箭头；
    // 不可选的说明项不设。卡片背景与分隔线由 SettingsCardItemDecoration 画。
    private fun applyCardStyling() {
        fun walk(group: PreferenceGroup) {
            for (i in 0 until group.preferenceCount) {
                val pref = group.getPreference(i)
                if (pref is PreferenceCategory) {
                    pref.layoutResource = R.layout.preference_category_header
                    walk(pref)
                } else if (pref is PreferenceGroup) {
                    walk(pref)
                } else if (pref.isSelectable && pref !is SwitchPreference) {
                    pref.widgetLayoutResource = R.layout.preference_chevron
                }
            }
        }
        preferenceScreen?.let { walk(it) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        val category = arguments?.getString(ARG_CATEGORY)?.let {
            runCatching { Category.valueOf(it) }.getOrDefault(Category.GENERAL)
        } ?: Category.GENERAL
        addPreferencesFromResource(category.xmlRes)
        applyCardStyling()

        findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)?.let { nightTheme ->
            nightTheme.setOnPreferenceChangeListener { _, newTheme ->
                Theme.currentNightMode = (newTheme as String).toInt()
                Theme.applyNightTheme()
                true
            }
        }
        // 开机自启（第 11 项）：开关本身早已存在（isAutoConnect），但 BootReceiver
        // 在清单里默认是禁用的，只有服务启动时才同步一次组件状态。拨动开关时直接同步，
        // 否则只改了存值、接收器仍是禁用的，开机不会自启。
        findPreference<SwitchPreference>(Key.PERSIST_ACROSS_REBOOT)?.setOnPreferenceChangeListener { _, newValue ->
            BootReceiver.enabled = newValue as Boolean
            true
        }
        val mixedPort = findPreference<EditTextPreference>(Key.MIXED_PORT)
        val localProxyAuth = findPreference<SwitchPreference>(Key.LOCAL_PROXY_AUTH)
        val mixedInboundPass = findPreference<EditTextPreference>(Key.MIXED_INBOUND_PASS)
        val serviceMode = findPreference<Preference>(Key.SERVICE_MODE)
        val allowAccess = findPreference<Preference>(Key.ALLOW_ACCESS)
        val appendHttpProxy = findPreference<SwitchPreference>(Key.APPEND_HTTP_PROXY)

        val showDirectSpeed = findPreference<SwitchPreference>(Key.SHOW_DIRECT_SPEED)
        val ipv6Mode = findPreference<Preference>(Key.IPV6_MODE)
        val blockStun = findPreference<SwitchPreference>(Key.BLOCK_STUN)
        // -79 C-S-1：STUN 拦截与 AAAA fake-ip 都只在 VPN（tun）模式生效——
        // 仅代理模式下没有 tun 入站，应用的 STUN UDP 根本不进核心，开关开
        // 着也拦不住。灰掉开关并改摘要，免得给人“已受保护”的错觉。
        // -80：VPN 模式不写摘要（设置页不留解释说明行）；仅代理模式的
        // “仅 VPN 模式生效”是灰掉状态的功能提示，保留。
        if (DataStore.serviceMode == Key.MODE_PROXY) {
            blockStun?.isEnabled = false
            blockStun?.summary = getString(R.string.block_stun_summary_proxy_only)
        } else {
            blockStun?.isEnabled = true
            blockStun?.summary = null
        }
        val trafficSniffing = findPreference<Preference>(Key.TRAFFIC_SNIFFING)

        val bypassLan = findPreference<SwitchPreference>(Key.BYPASS_LAN)
        val bypassLanInCore = findPreference<SwitchPreference>(Key.BYPASS_LAN_IN_CORE)

        val remoteDns = findPreference<EditTextPreference>(Key.REMOTE_DNS)
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)
        val enableDnsRouting = findPreference<SwitchPreference>(Key.ENABLE_DNS_ROUTING)
        val enableFakeDns = findPreference<SwitchPreference>(Key.ENABLE_FAKEDNS)
        // Optimistic DNS（第 14 项）：超时下拉只在开关开着时可选。
        val dnsOptimisticEnabled = findPreference<SwitchPreference>(Key.DNS_OPTIMISTIC_ENABLED)
        val dnsOptimisticTimeout = findPreference<SimpleMenuPreference>(Key.DNS_OPTIMISTIC_TIMEOUT)
        if (dnsOptimisticEnabled != null && dnsOptimisticTimeout != null) {
            dnsOptimisticTimeout.isEnabled = dnsOptimisticEnabled.isChecked
            dnsOptimisticEnabled.setOnPreferenceChangeListener { _, newValue ->
                dnsOptimisticTimeout.isEnabled = newValue as Boolean
                needReload()
                true
            }
        }

        // Hysteria2 增强（第 13 项）与 AnyTLS 会话复用（第 14 项）：全局设置，
        // 改动后重载服务；AnyTLS 三个数字框只接受数字输入，空 = 未设。
        val hy2BbrProfile = findPreference<SimpleMenuPreference>(Key.HY2_BBR_PROFILE)
        val hy2DisableChromeParrot = findPreference<SwitchPreference>(Key.HY2_DISABLE_CHROME_PARROT)
        val hy2HopIntervalMax = findPreference<SimpleMenuPreference>(Key.HY2_HOP_INTERVAL_MAX)
        val hy2BrutalDebug = findPreference<SwitchPreference>(Key.HY2_BRUTAL_DEBUG)
        val anytlsIdleCheckInterval = findPreference<EditTextPreference>(Key.ANYTLS_IDLE_CHECK_INTERVAL)
        val anytlsIdleTimeout = findPreference<EditTextPreference>(Key.ANYTLS_IDLE_TIMEOUT)
        val anytlsMinIdleSession = findPreference<EditTextPreference>(Key.ANYTLS_MIN_IDLE_SESSION)
        anytlsIdleCheckInterval?.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        anytlsIdleTimeout?.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        anytlsMinIdleSession?.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)

        val logLevel = findPreference<LongClickListPreference>(Key.LOG_LEVEL)
        val mtu = findPreference<MTUPreference>(Key.MTU)
        findPreference<EditConfigPreference>(Key.GLOBAL_CUSTOM_CONFIG)?.let {
            globalCustomConfig = it
            it.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)
        }

        logLevel?.let {
            it.dialogLayoutResource = R.layout.layout_loglevel_help
            it.setOnPreferenceChangeListener { _, _ ->
                needRestart()
                true
            }
            // 日志上限长按入口已删：-29 方案 A 把上限定死 2MB（保留后半段），
            // 再小的设置值都会被 rift.go 钳回 2048KB，入口留着只会误导
            // 人以为能调小。DataStore.logBufSize 与 rift.go 的钳制保留，
            // 上限实际恒为 2MB。
        }

        mixedPort?.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)

        findPreference<Preference>(Key.METERED_NETWORK)?.let { metedNetwork ->
            if (Build.VERSION.SDK_INT < 28) {
                metedNetwork.remove()
            }
        }
        findPreference<SwitchPreference>(Key.PROXY_APPS)?.let {
            isProxyApps = it
            it.setOnPreferenceChangeListener { _, newValue ->
                startActivity(Intent(activity, AppManagerActivity::class.java))
                if (newValue as Boolean) DataStore.dirty = true
                newValue
            }
        }

        val profileTrafficStatistics =
            findPreference<SwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)
        val speedInterval = findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)
        if (profileTrafficStatistics != null && speedInterval != null) {
            profileTrafficStatistics.isEnabled = speedInterval.value.toString() != "0"
            speedInterval.setOnPreferenceChangeListener { _, newValue ->
                profileTrafficStatistics.isEnabled = newValue.toString() != "0"
                needReload()
                true
            }
        }

        serviceMode?.setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) RiftApp.stopService()
            true
        }

        val acquireWakeLock = findPreference<SwitchPreference>(Key.ACQUIRE_WAKE_LOCK)
        findPreference<SwitchPreference>(Key.ENABLE_CLASH_API)?.setOnPreferenceChangeListener { _, newValue ->
            (activity as MainActivity?)?.refreshNavMenu(newValue as Boolean)
            needReload()
            true
        }

        // Auto speed test (step ②, 2026-11-25): switch/interval change re-schedules
        // the :bg periodic worker (or cancels it when switched off).
        val autoSpeedTestListener = Preference.OnPreferenceChangeListener { _, _ ->
            runOnDefaultDispatcher { AutoSpeedTest.reconfigure() }
            true
        }
        findPreference<SwitchPreference>(Key.AUTO_SPEED_TEST_ENABLED)?.onPreferenceChangeListener =
            autoSpeedTestListener
        findPreference<SimpleMenuPreference>(Key.AUTO_SPEED_TEST_INTERVAL)?.onPreferenceChangeListener =
            autoSpeedTestListener
        // "Last auto test" is display-only: show the last run time, or "never".
        findPreference<Preference>(Key.AUTO_SPEED_TEST_LAST_TIME)?.let { lastTime ->
            val ts = DataStore.autoSpeedTestLastTime
            lastTime.summary = if (ts > 0) Util.timeStamp2Text(ts)
            else getString(R.string.auto_speed_test_never)
        }

        // 用户手动改端口：记为非自动分配，之后端口冲突时 BaseService 不会
        // 静默把它打回默认（随机端口才自动重置）。
        mixedPort?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
            DataStore.mixedPortAuto = false
            needReload()
            true
        }
        // 本地代理认证开关/密码改动也要重载服务：运行中的核心仍用旧 users 配置，
        // 而诊断侧每次读最新 DataStore，错配会导致 URL 检查/流媒体报认证失败。
        localProxyAuth?.onPreferenceChangeListener = reloadListener
        mixedInboundPass?.onPreferenceChangeListener = reloadListener
        appendHttpProxy?.onPreferenceChangeListener = reloadListener
        showDirectSpeed?.onPreferenceChangeListener = reloadListener
        trafficSniffing?.onPreferenceChangeListener = reloadListener
        bypassLan?.onPreferenceChangeListener = reloadListener
        bypassLanInCore?.onPreferenceChangeListener = reloadListener
        mtu?.onPreferenceChangeListener = reloadListener

        enableFakeDns?.onPreferenceChangeListener = reloadListener
        remoteDns?.onPreferenceChangeListener = reloadListener
        directDns?.onPreferenceChangeListener = reloadListener
        enableDnsRouting?.onPreferenceChangeListener = reloadListener
        dnsOptimisticTimeout?.onPreferenceChangeListener = reloadListener
        hy2BbrProfile?.onPreferenceChangeListener = reloadListener
        hy2DisableChromeParrot?.onPreferenceChangeListener = reloadListener
        hy2HopIntervalMax?.onPreferenceChangeListener = reloadListener
        hy2BrutalDebug?.onPreferenceChangeListener = reloadListener
        anytlsIdleCheckInterval?.onPreferenceChangeListener = reloadListener
        anytlsIdleTimeout?.onPreferenceChangeListener = reloadListener
        anytlsMinIdleSession?.onPreferenceChangeListener = reloadListener

        ipv6Mode?.onPreferenceChangeListener = reloadListener
        blockStun?.onPreferenceChangeListener = reloadListener
        allowAccess?.onPreferenceChangeListener = reloadListener

        acquireWakeLock?.onPreferenceChangeListener = reloadListener
        if (::globalCustomConfig.isInitialized) {
            globalCustomConfig.onPreferenceChangeListener = reloadListener
        }
    }

    override fun onResume() {
        super.onResume()

        if (::isProxyApps.isInitialized) {
            isProxyApps.isChecked = DataStore.proxyApps
        }
        if (::globalCustomConfig.isInitialized) {
            globalCustomConfig.notifyChanged()
        }
    }

}
