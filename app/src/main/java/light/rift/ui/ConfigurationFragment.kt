package light.rift.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import light.rift.ktx.RiftAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import light.rift.GroupOrder
import light.rift.GroupType
import light.rift.Key
import light.rift.R
import light.rift.RiftApp
import light.rift.aidl.TrafficData
import light.rift.bg.AutoSpeedTest
import light.rift.bg.BaseService
import light.rift.bg.proto.UrlTest
import light.rift.database.DataStore
import light.rift.database.GroupManager
import light.rift.database.ProfileManager
import light.rift.database.ProxyEntity
import light.rift.database.ProxyGroup
import light.rift.database.RiftDatabase
import light.rift.database.TrafficStatsStore
import light.rift.database.preference.OnPreferenceDataStoreChangeListener
import light.rift.databinding.LayoutProfileListBinding
import light.rift.databinding.LayoutProgressListBinding
import light.rift.fmt.AbstractBean
import light.rift.fmt.toUniversalLink
import light.rift.group.GroupUpdater
import light.rift.group.RawUpdater
import light.rift.ktx.FixedLinearLayoutManager
import light.rift.ktx.Logs
import light.rift.ktx.SubscriptionFoundException
import light.rift.ktx.alert
import light.rift.ktx.app
import light.rift.ktx.dp2px
import light.rift.ktx.getColorAttr
import light.rift.ktx.getColour
import light.rift.ktx.isIpAddress
import light.rift.ktx.onMainDispatcher
import light.rift.ktx.readableMessage
import light.rift.ktx.runOnDefaultDispatcher
import light.rift.core.utils.Util
import light.rift.ktx.runOnLifecycleDispatcher
import light.rift.ktx.runOnMainDispatcher
import light.rift.ktx.scrollTo
import light.rift.ktx.showAllowingStateLoss
import light.rift.ktx.snackbar
import light.rift.ktx.startFilesForResult
import light.rift.ktx.tryToShow
import light.rift.ui.profile.ChainSettingsActivity
import light.rift.ui.profile.HttpSettingsActivity
import light.rift.ui.profile.HysteriaSettingsActivity
import light.rift.ui.profile.SSHSettingsActivity
import light.rift.ui.profile.ShadowsocksSettingsActivity
import light.rift.ui.profile.SocksSettingsActivity
import light.rift.ui.profile.TrojanSettingsActivity
import light.rift.ui.profile.TuicSettingsActivity
import light.rift.ui.profile.VMessSettingsActivity
import light.rift.widget.QRCodeDialog
import light.rift.widget.SparklineView
import light.rift.widget.UndoSnackbarManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import light.rift.core.Protocols
import light.rift.core.Protocols.getProtocolColor
import light.rift.core.proxy.anytls.AnyTLSSettingsActivity
import light.rift.core.proxy.config.ConfigSettingActivity
import light.rift.core.proxy.shadowtls.ShadowTLSSettingsActivity
import light.rift.core.ui.ConnectionTestNotification
import okhttp3.internal.closeQuietly
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false, val selectedItem: ProxyEntity? = null, val titleRes: Int = 0
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2

    // 批三第 16 项：列表顶状态卡。节点名/速度/出口 IP 由 MainActivity 转发
    // （速度回调、节点切换回调、连接成功时查到的出口 IP），这里只缓存 + 显示。
    private lateinit var statusNode: TextView
    private lateinit var statusRx: TextView
    private lateinit var statusTx: TextView
    private lateinit var statusIp: TextView
    private lateinit var statusLocation: TextView
    private lateinit var statusConn: TextView
    private lateinit var statusPing: TextView
    private lateinit var sparkline: SparklineView
    private var lastTxRate = 0L
    private var lastRxRate = 0L
    // 波形图（2026-11-12，0.2.21 -5）：service 记的历史只在进首页时
    // 读一次当初始样本，之后 live 样本接着往后加；清过流量后重置。
    private var sparklineSeeded = false
    private var lastNodeId = -1L
    private var cachedExitIp: String? = null
    private var cachedExitCountry: String? = null
    private var cachedExitCity: String? = null
    private var fetchingExitIp = false
    private var lastExitIpAttemptAt = 0L

    // 列表旗（2026-11-17，PRE -23；-25 改用 selectedProxy）：当前连接节点的
    // 出口国家；连着时列表优先用出口画旗，断开或不是当前节点时返回 null，走
    // 名字/入口那套。不能用 lastNodeId——它只在列表里点节点时才设，悬浮按钮/
    // 通知栏切换/重启后已连着的连接都不会设它，永远是 -1。
    internal fun exitCountryForCurrentNode(nodeId: Long): String? =
        if (nodeId == DataStore.selectedProxy) cachedExitCountry else null

    fun getCurrentGroupFragment(): GroupFragment? {
        return try {
            childFragmentManager.findFragmentByTag("f" + DataStore.selectedGroup) as GroupFragment?
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    /**
     * Auto speed test progress (step ②), forwarded by MainActivity from the
     * AIDL `cbTestProgress` callback. Routes to the currently visible group
     * only — a round for another group is invisible here, its results are
     * already in the DB and show on re-entry.
     */
    // -79 B-I1：本轮是否测过当前可见分组。逐节点 DONE 会把 testStates
    // 一项项排空，所以不能拿 testStates 是否为空判断轮次是否属于当前
    // 页——正常跑完时守卫恒为 false，reload 永远走不到。改为：任一
    // 非 ROUND_DONE 事件的节点在当前页 id 表里时置 true，ROUND_DONE
    // 时只在 true 才 reloadProfiles()，否则只清 testStates、不碰列表。
    private var testRoundForVisibleGroup = false

    fun onTestProgress(profileId: Long, state: Int) {
        val fragment = getCurrentGroupFragment() ?: return
        if (profileId == AutoSpeedTest.ROUND_DONE_ID) {
            // 轮次结束：瞬态表总要清；列表只在测的就是当前页时刷新，
            // 测别的分组时不碰当前页、不 yank 用户的滚动位置。
            fragment.testStates.clear()
            if (testRoundForVisibleGroup) {
                testRoundForVisibleGroup = false
                fragment.adapter?.reloadProfiles()
            }
            return
        }
        if (state == AutoSpeedTest.STATE_DONE) {
            fragment.testStates.remove(profileId)
        } else {
            fragment.testStates[profileId] = state
        }
        val adapter = fragment.adapter ?: return
        val index = adapter.configurationIdList.indexOf(profileId)
        if (index >= 0) {
            testRoundForVisibleGroup = true
            adapter.notifyItemChanged(index)
        }
    }

    /** Multi-select menu state (step ②): "cancel selection" + test-item enable. */
    fun refreshSelectionMenu() {
        val adapter = getCurrentGroupFragment()?.adapter ?: return
        toolbar.menu.findItem(R.id.action_cancel_selection)?.isVisible = adapter.selectionMode
        val canTest = !adapter.selectionMode || adapter.selectedIds.isNotEmpty()
        toolbar.menu.findItem(R.id.action_connection_tcp_ping)?.isEnabled = canTest
        toolbar.menu.findItem(R.id.action_connection_url_test)?.isEnabled = canTest
    }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(
            position: Int, positionOffset: Float, positionOffsetPixels: Int
        ) {
            if (adapter.groupList.size > position) {
                DataStore.selectedGroup = adapter.groupList[position].id
            }
        }
    }

    override fun onQueryTextChange(query: String): Boolean {
        getCurrentGroupFragment()?.adapter?.filter(query)
        return false
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    @SuppressLint("DetachAndAttachSameFragment")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!select) {
            toolbar.inflateMenu(R.menu.add_profile_menu)
            toolbar.setOnMenuItemClickListener(this)
        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }

        val searchView = toolbar.findViewById<SearchView>(R.id.action_search)
        if (searchView != null) {
            searchView.setOnQueryTextListener(this)
            searchView.maxWidth = Int.MAX_VALUE

            searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    cancelSearch(searchView)
                }
            }
        }

        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 2

        TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = adapter.groupList[position].displayName()
            }
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.attach()

        // 分流模式切换（2026-11-10）：规则 / 全局 / 直连。切模式即改配置，
        // VPN 在跑就 reload 生效，不在跑则下次启动生效。
        // 注意：不用 MaterialButtonToggleGroup——它在冷启动/主题切换首帧
        // 会在每字后留一条竖条残影（#79/#81 两版都复现，与描边无关），
        // 改用三个 TextView 手动管理选中态，描边由外框 shape 提供。
        val modeRule = view.findViewById<TextView>(R.id.route_mode_rule)
        val modeGlobal = view.findViewById<TextView>(R.id.route_mode_global)
        val modeDirect = view.findViewById<TextView>(R.id.route_mode_direct)
        val modeViews = listOf(modeRule to 0, modeGlobal to 1, modeDirect to 2)
        // 琥珀底上的深蓝字：白天夜间都用它（夜间主文字色是浅色，放琥珀底上看不清）。
        val modeCheckedText = 0xFF16224A.toInt()
        // 未选中用 Rift 主文字色（#16224A）。不用 textColorPrimary：它解析成
        // 系统默认近黑色（#DE000000），不是 Rift 主文字色，fallback 永远走不到。
        val modeUncheckedText = requireContext().getColor(R.color.color_rift_primary_text)
        fun syncRouteMode() {
            for ((tv, mode) in modeViews) {
                val selected = DataStore.routeMode == mode
                // 选中态是圆角琥珀药丸（route_mode_selected_bg），未选中透明、
                // 透出灰底组（2026-11-12 首页重设计 -4）。
                if (selected) {
                    tv.setBackgroundResource(R.drawable.route_mode_selected_bg)
                } else {
                    tv.background = null
                }
                tv.setTextColor(if (selected) modeCheckedText else modeUncheckedText)
            }
        }
        syncRouteMode()
        for ((tv, mode) in modeViews) {
            tv.setOnClickListener {
                if (DataStore.routeMode == mode) return@setOnClickListener
                DataStore.routeMode = mode
                syncRouteMode()
                if (DataStore.serviceState.connected) RiftApp.reloadService()
            }
        }

        // 批三第 16 项：列表顶状态卡。数据由 MainActivity 转发，见 onSpeedUpdate /
        // onNodeChanged / onExitIpChanged；首次显示时先按当前状态刷一遍。
        statusNode = view.findViewById(R.id.home_status_node)
        statusRx = view.findViewById(R.id.home_status_rx)
        statusTx = view.findViewById(R.id.home_status_tx)
        statusIp = view.findViewById(R.id.home_status_ip)
        statusLocation = view.findViewById(R.id.home_status_location)
        statusConn = view.findViewById(R.id.home_status_conn)
        statusPing = view.findViewById(R.id.home_status_ping)
        // 波形图（2026-11-12，0.2.21）：状态卡里的实时下行速度线，View 自己攒点。
        sparkline = view.findViewById(R.id.home_status_sparkline)
        refreshStatusCard()

        toolbar.setOnClickListener {
            val fragment = getCurrentGroupFragment()

            if (fragment != null) {
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                val selectedProfileIndex =
                    fragment.adapter!!.configurationIdList.indexOf(selectedProxy)
                if (selectedProfileIndex != -1) {
                    val layoutManager = fragment.layoutManager
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()

                    if (selectedProfileIndex !in first..last) {
                        fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                        return@setOnClickListener
                    }

                }

                fragment.configurationListView.scrollTo(0)
            }

        }

        DataStore.profileCacheStore.registerChangeListener(this)
    }

    // 批三第 16 项：列表顶状态卡。MainActivity 转发速度（每秒）、节点切换、
    // 连接成功时查到的出口 IP；这里只缓存 + 显示，setText 有变化才写（防闪）。
    fun onSpeedUpdate(txRate: Long, rxRate: Long) {
        lastTxRate = txRate
        lastRxRate = rxRate
        // 波形图每秒喂一个下行速度点（View 自己只留最近 60 个）。
        // -5：第一个 live 样本到来时先把 service 记的历史种进去，再接
        // live 样本；onResume 已种过时这里直接跳过（只种一次）。
        if (::sparkline.isInitialized) {
            seedSparklineFromHistory()
            sparkline.addSample(rxRate)
        }
        refreshStatusCard()
    }

    // 波形图（2026-11-12，0.2.21 -5）：service 每 60 秒把最近 60 个下行
    // 速率写进 DataStore；进首页（onResume / 第一个 live 样本）时读一次
    // 当初始样本，后台（YouTube 等）流过的量也有记录。只读一次；未连接
    // 或历史为空时不种，保持现在的空闲基线。跨进程读走 Room，直读即最新。
    private fun seedSparklineFromHistory() {
        if (sparklineSeeded || !::sparkline.isInitialized || !isAdded) return
        if (!DataStore.serviceState.connected) return
        val history = TrafficStatsStore.getSparklineHistory()
        if (history.isEmpty()) return
        sparkline.setSamples(history)
        sparklineSeeded = true
    }

    fun onNodeChanged(id: Long, name: String?) {
        // 节点真换了，出口 IP 可能也换了：清缓存，下次刷新时重查。
        // 只比较 id：连接成功时回调的也是当前节点，id 不变就不清，避免重复查。
        if (id != lastNodeId) {
            lastNodeId = id
            cachedExitIp = null
            cachedExitCountry = null
            cachedExitCity = null
            // 2026-11-22 -58: 节点换了就必须立刻重查出口，不能等 30 秒节流——
            // 旧 IP 对新节点一定是错的；节流只防重复刷新，不防换节点。
            lastExitIpAttemptAt = 0L
        }
        refreshStatusCard()
    }

    fun onExitIpChanged(ip: String?, countryCode: String?, city: String?) {
        cachedExitIp = ip
        cachedExitCountry = countryCode
        cachedExitCity = city
        // 记住出口（PRE -24；-25 改用 selectedProxy）：出口到货时按当前节点记下，
        // 重连/切节点时刷新；断开时 countryCode 为 null，不记。selectedProxy
        // 是持久化的当前节点 id，悬浮按钮/通知栏切换/重启后已连着都对得上；
        // lastNodeId 只在列表点选时才设，不能用。
        val currentId = DataStore.selectedProxy
        if (!countryCode.isNullOrBlank() && currentId > 0) {
            DataStore.setNodeEgressCountry(currentId, countryCode)
        }
        onExitCacheChanged()
    }

    // 出口缓存变了（到货/断开清掉）：状态卡 + 列表都要刷，当前节点的行才变旗。
    private fun onExitCacheChanged() {
        refreshStatusCard()
        getCurrentGroupFragment()?.adapter?.notifyDataSetChanged()
    }

    // 通知栏切分流后刷新选中态（2026-11-12，0.2.21）。
    // 注意：syncRouteMode 是 onViewCreated 里的局部函数，这里按同一套规则
    // 重写一遍（选中圆角琥珀药丸 + 深蓝字，未选中透明底 + 主文字色），
    // 供 MainActivity（DataStore 变化监听）与 onResume 调。
    // 跨进程注意：通知栏在后台进程改 routeMode，界面进程的 DataStore
    // 监听收不到，所以 onResume 回来时也要自查一次。
    fun refreshRouteMode() {
        if (!isAdded) return
        val root = view ?: return
        val modeRule = root.findViewById<TextView>(R.id.route_mode_rule) ?: return
        val modeGlobal = root.findViewById<TextView>(R.id.route_mode_global) ?: return
        val modeDirect = root.findViewById<TextView>(R.id.route_mode_direct) ?: return
        val modeCheckedText = 0xFF16224A.toInt()
        val modeUncheckedText = requireContext().getColor(R.color.color_rift_primary_text)
        for ((tv, mode) in listOf(modeRule to 0, modeGlobal to 1, modeDirect to 2)) {
            val selected = DataStore.routeMode == mode
            // 选中态是圆角琥珀药丸（route_mode_selected_bg），未选中透明、透出灰底组
            //（2026-11-12 首页重设计 -4）。
            if (selected) {
                tv.setBackgroundResource(R.drawable.route_mode_selected_bg)
            } else {
                tv.background = null
            }
            tv.setTextColor(if (selected) modeCheckedText else modeUncheckedText)
        }
    }

    override fun onResume() {
        super.onResume()
        // 通知栏切了分流、回来看首页时选中态要跟上（跨进程监听收不到）。
        refreshRouteMode()
        // 波形图 -5：回首页时把 service 记的历史种进去，后台流量也有记录。
        seedSparklineFromHistory()
    }

    private fun refreshStatusCard() {
        if (!isAdded || !::statusNode.isInitialized) return
        // 节点名现查：改名后不用等回调也能跟上。
        val name = ProfileManager.getProfile(DataStore.selectedProxy)?.displayName()
        val nodeText = name ?: getString(R.string.home_status_node_none)
        if (statusNode.text.toString() != nodeText) statusNode.text = nodeText
        // 速度（2026-11-12 首页重设计）：左右两个大数字，下载在左、上传在右，
        // 各自只在变化时写（防闪），与原来同一套 guard。
        val rxText = getString(R.string.speed, Formatter.formatFileSize(requireContext(), lastRxRate))
        if (statusRx.text.toString() != rxText) statusRx.text = rxText
        // 速度为 0 时变淡（2026-11-13，0.2.23 -6）：空闲不喊“0 B/s”；有流量时恢复主色。
        val rxColor = requireContext().getColorAttr(
            if (lastRxRate == 0L) android.R.attr.textColorSecondary else android.R.attr.textColorPrimary
        )
        if (statusRx.currentTextColor != rxColor) statusRx.setTextColor(rxColor)
        val txText = getString(R.string.speed, Formatter.formatFileSize(requireContext(), lastTxRate))
        if (statusTx.text.toString() != txText) statusTx.text = txText
        val txColor = requireContext().getColorAttr(
            if (lastTxRate == 0L) android.R.attr.textColorSecondary else android.R.attr.textColorPrimary
        )
        if (statusTx.currentTextColor != txColor) statusTx.setTextColor(txColor)
        // 出口 IP：未连接显示“未连接”；已连接但还没拿到（MainActivity 转发丢了，
        // 比如连接时人不在主页）就地补查一次，失败 30 秒内不重试。
        val ipText = when {
            !DataStore.serviceState.connected -> getString(
                R.string.home_status_ip, getString(R.string.home_status_ip_off)
            )

            cachedExitIp.isNullOrBlank() -> {
                fetchExitIpIfNeeded()
                getString(R.string.home_status_ip, getString(R.string.home_status_ip_fetching))
            }

            else -> {
                val flag = EgressIp.flagEmoji(cachedExitCountry ?: "")
                val ip = if (flag.isNotBlank()) "$flag $cachedExitIp" else cachedExitIp!!
                getString(R.string.home_status_ip, ip)
            }
        }
        if (statusIp.text.toString() != ipText) statusIp.text = ipText
        // 出口 IP 行右边：国家 · 英文城市（“美国 · Los Angeles”），只作装饰、填右边
        // 的空（用户 2026-11-12 定：显示，英文也行）。未连接/还没拿到时隐藏。
        val locText = if (DataStore.serviceState.connected && !cachedExitIp.isNullOrBlank()) {
            exitLocationText()
        } else ""
        if (statusLocation.text.toString() != locText) statusLocation.text = locText
        statusLocation.visibility = if (locText.isBlank()) View.GONE else View.VISIBLE
        // 卡顶状态行（2026-11-12 首页重设计 -4）：左边绿“已连接”（未连接时灰“未连接”），
        // 右边当前节点延时（有测速结果才显示）。延时读当前节点的 ping（批量测速/
        // 底栏重测会写回它），每秒速度回调都会顺带刷一次。
        val connected = DataStore.serviceState.connected
        val connText = if (connected) "● ${getString(R.string.home_status_connected)}"
            else "○ ${getString(R.string.home_status_disconnected)}"
        if (statusConn.text.toString() != connText) statusConn.text = connText
        statusConn.setTextColor(
            if (connected) requireContext().getColor(R.color.color_rift_success)
            else requireContext().getColor(android.R.color.tertiary_text_light)
        )
        val ping = ProfileManager.getProfile(DataStore.selectedProxy)?.ping ?: 0
        val pingText = if (connected && ping > 0) "$ping ms" else ""
        if (statusPing.text.toString() != pingText) statusPing.text = pingText
        statusPing.visibility = if (pingText.isBlank()) View.GONE else View.VISIBLE
    }

    /** 国家名（中文）· 英文城市，如“美国 · Los Angeles”。国家名用系统 Locale 由
     * countryCode 翻；城市名 ipwho.is 只给英文，系统翻不了中文，直接显示英文。 */
    private fun exitLocationText(): String {
        val country = countryName(cachedExitCountry)
        val city = cachedExitCity?.trim().orEmpty()
        return when {
            country.isNotBlank() && city.isNotBlank() -> "$country · $city"
            country.isNotBlank() -> country
            city.isNotBlank() -> city
            else -> ""
        }
    }

    private fun countryName(countryCode: String?): String {
        if (countryCode.isNullOrBlank() || countryCode.length != 2) return ""
        return try {
            java.util.Locale("", countryCode.uppercase())
                .getDisplayCountry(java.util.Locale.CHINESE)
        } catch (_: Exception) {
            ""
        }
    }

    private fun fetchExitIpIfNeeded() {
        if (fetchingExitIp) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastExitIpAttemptAt < 30_000) return
        lastExitIpAttemptAt = now
        fetchingExitIp = true
        runOnDefaultDispatcher {
            val nodeIdAtStart = DataStore.selectedProxy
            val info = EgressIp.fetch()
            onMainDispatcher {
                fetchingExitIp = false
                // 2026-11-22 -57: 节点已换就丢掉这次结果，不写缓存；
                // 快速连点时旧查询会比新查询晚到，没有这道关就一直顶旧 IP。
                if (DataStore.selectedProxy != nodeIdAtStart) return@onMainDispatcher
                if (info != null) {
                    cachedExitIp = info.ip
                    cachedExitCountry = info.countryCode
                    cachedExitCity = info.city
                    val currentId = DataStore.selectedProxy
                    if (info.countryCode.isNotBlank() && currentId > 0) {
                        DataStore.setNodeEgressCountry(currentId, info.countryCode)
                    }
                    onExitCacheChanged()
                } else {
                    // 2026-11-22 -56: 查不到就清掉缓存，不留旧 IP 冒充当前出口——
                    // 旧 IP 顶着不走就是“切节点没反应”的错觉来源。
                    if (cachedExitIp != null || cachedExitCountry != null || cachedExitCity != null) {
                        cachedExitIp = null
                        cachedExitCountry = null
                        cachedExitCity = null
                        onExitCacheChanged()
                    }
                }
            }
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            // editingGroup
            if (key == Key.PROFILE_GROUP) {
                val targetId = DataStore.editingGroup
                if (targetId > 0 && targetId != DataStore.selectedGroup) {
                    DataStore.selectedGroup = targetId
                    val targetIndex = adapter.groupList.indexOfFirst { it.id == targetId }
                    if (targetIndex >= 0) {
                        groupPager.setCurrentItem(targetIndex, false)
                    } else {
                        adapter.reload()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)

        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }

        super.onDestroy()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = getCurrentGroupFragment()
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) runOnDefaultDispatcher {
                try {
                    val fileName =
                        requireContext().contentResolver.query(file, null, null, null, null)
                            ?.use { cursor ->
                                cursor.moveToFirst()
                                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                                    .let(cursor::getString)
                            }
                    val proxies = mutableListOf<AbstractBean>()
                    if (fileName != null && fileName.endsWith(".zip")) {
                        // try parse wireguard zip
                        val zip =
                            ZipInputStream(requireContext().contentResolver.openInputStream(file)!!)
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val fileText = zip.bufferedReader().readText()
                            RawUpdater.parseRaw(fileText, entry.name)
                                ?.let { pl -> proxies.addAll(pl) }
                            zip.closeEntry()
                        }
                        zip.closeQuietly()
                    } else {
                        val fileText =
                            requireContext().contentResolver.openInputStream(file)!!.use {
                                it.bufferedReader().readText()
                            }
                        RawUpdater.parseRaw(fileText, fileName ?: "")
                            ?.let { pl -> proxies.addAll(pl) }
                    }
                    if (proxies.isEmpty()) onMainDispatcher {
                        snackbar(getString(R.string.no_proxies_found_in_file)).show()
                    } else import(proxies)
                } catch (e: SubscriptionFoundException) {
                    (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }

    suspend fun import(proxies: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()
        for (proxy in proxies) {
            ProfileManager.createProfile(targetId, proxy)
        }
        onMainDispatcher {
            DataStore.editingGroup = targetId
            snackbar(
                requireContext().resources.getQuantityString(
                    R.plurals.added, proxies.size, proxies.size
                )
            ).show()
        }

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }

            R.id.action_import_clipboard -> {
                val text = RiftApp.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else runOnDefaultDispatcher {
                    try {
                        val proxies = RawUpdater.parseRaw(text)
                        if (proxies.isNullOrEmpty()) onMainDispatcher {
                            snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                        } else import(proxies)
                    } catch (e: SubscriptionFoundException) {
                        (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                    } catch (e: Exception) {
                        Logs.w(e)

                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }

            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }

            R.id.action_new_socks -> {
                startActivity(Intent(requireActivity(), SocksSettingsActivity::class.java))
            }

            R.id.action_new_http -> {
                startActivity(Intent(requireActivity(), HttpSettingsActivity::class.java))
            }

            R.id.action_new_ss -> {
                startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            }

            R.id.action_new_vmess -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            }

            R.id.action_new_vless -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java).apply {
                    putExtra("vless", true)
                })
            }

            R.id.action_new_trojan -> {
                startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            }

            R.id.action_new_hysteria -> {
                startActivity(Intent(requireActivity(), HysteriaSettingsActivity::class.java))
            }

            R.id.action_new_tuic -> {
                startActivity(Intent(requireActivity(), TuicSettingsActivity::class.java))
            }

            R.id.action_new_ssh -> {
                startActivity(Intent(requireActivity(), SSHSettingsActivity::class.java))
            }

            R.id.action_new_shadowtls -> {
                startActivity(Intent(requireActivity(), ShadowTLSSettingsActivity::class.java))
            }

            R.id.action_new_anytls -> {
                startActivity(Intent(requireActivity(), AnyTLSSettingsActivity::class.java))
            }

            R.id.action_new_config -> {
                startActivity(Intent(requireActivity(), ConfigSettingActivity::class.java))
            }

            R.id.action_new_chain -> {
                startActivity(Intent(requireActivity(), ChainSettingsActivity::class.java))
            }

            R.id.action_update_subscription -> {
                val group = DataStore.currentGroup()
                if (group.type != GroupType.SUBSCRIPTION) {
                    snackbar(R.string.group_not_subscription).show()
                    Logs.e("onMenuItemClick: Group(${group.displayName()}) is not subscription")
                } else {
                    runOnLifecycleDispatcher {
                        GroupUpdater.startUpdate(group, true)
                    }
                }
            }

            R.id.action_clear_traffic_statistics -> {
                runOnDefaultDispatcher {
                    // 0.2.21 -3：清全部节点（全部分组），不只当前组；同时把
                    // 每日桶（今日/本期/30天）也清掉。后台服务凭清除时间点
                    // 丢掉清之前攒的待刷量，见 TrafficLooper。
                    val profiles = RiftDatabase.proxyDao.getAll()
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.tx != 0L || profile.rx != 0L) {
                            profile.tx = 0
                            profile.rx = 0
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                    TrafficStatsStore.clearAll()
                }
                // 波形图 -5：清完流量统计，波形也回到空闲基线，下次进首页
                // 重新读历史（已清空，不会把清之前的波形种回来）。
                if (::sparkline.isInitialized) sparkline.clearSamples()
                sparklineSeeded = false
            }

            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    val profiles = RiftDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0) {
                            profile.status = 0
                            profile.ping = 0
                            profile.error = null
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                }
            }

            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    val profiles = RiftDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0 && profile.status != 1) {
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            RiftAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(R.string.delete_confirm_prompt)
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_connection_test_delete_slow -> {
                runOnDefaultDispatcher {
                    val profiles = RiftDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    // 只清测过且慢的：没测过（status 0 / ping 0）和测不通的（status 2/3）不在此列，后者走“清理不可用配置”。
                    val toClear =
                        profiles.filter { it.status == 1 && it.ping > slowPingThresholdMs }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            RiftAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(
                                    getString(R.string.delete_confirm_prompt) + "\n" +
                                            toClear.mapIndexedNotNull { index, proxyEntity ->
                                                if (index < 20) {
                                                    "${proxyEntity.displayName()} (${proxyEntity.ping} ms)"
                                                } else if (index == 20) {
                                                    "......"
                                                } else {
                                                    null
                                                }
                                            }.joinToString("\n")
                                )
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_remove_duplicate -> {
                runOnDefaultDispatcher {
                    val profiles = RiftDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
                    for (pf in profiles) {
                        val proxy = Protocols.Deduplication(pf.requireBean(), pf.displayType())
                        if (!uniqueProxies.add(proxy)) {
                            toClear += pf
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            RiftAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(
                                    getString(R.string.delete_confirm_prompt) + "\n" +
                                            toClear.mapIndexedNotNull { index, proxyEntity ->
                                                if (index < 20) {
                                                    proxyEntity.displayName()
                                                } else if (index == 20) {
                                                    "......"
                                                } else {
                                                    null
                                                }
                                            }.joinToString("\n")
                                )
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_cancel_selection -> {
                getCurrentGroupFragment()?.adapter?.exitSelectionMode()
            }

            R.id.action_connection_tcp_ping -> {
                pingTest(false)
            }

            R.id.action_connection_url_test -> {
                urlTest()
            }
        }
        return true
    }

    inner class TestDialog {
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        val builder = RiftAlertDialogBuilder(requireContext()).setView(binding.root)
            .setPositiveButton(R.string.minimize) { _, _ ->
                minimize()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancel()
            }
            .setCancelable(false)

        lateinit var cancel: () -> Unit
        lateinit var minimize: () -> Unit

        val dialogStatus = AtomicInteger(0) // 1: hidden 2: cancelled
        var notification: ConnectionTestNotification? = null

        val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
        var proxyN = 0
        val finishedN = AtomicInteger(0)

        fun update(profile: ProxyEntity) {
            if (dialogStatus.get() != 2) {
                results.add(profile)
            }
            runOnMainDispatcher {
                val context = context ?: return@runOnMainDispatcher
                val progress = finishedN.addAndGet(1)
                val status = dialogStatus.get()
                notification?.updateNotification(
                    progress,
                    proxyN,
                    progress >= proxyN || status == 2
                )
                if (status >= 1) return@runOnMainDispatcher
                if (!isAdded) return@runOnMainDispatcher

                // refresh dialog

                var profileStatusText: String? = null
                var profileStatusColor = 0

                when (profile.status) {
                    -1 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    0 -> {
                        profileStatusText = getString(R.string.connection_test_testing)
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    1 -> {
                        profileStatusText = getString(R.string.available, profile.ping)
                        profileStatusColor = context.getColour(R.color.material_green_500)
                    }

                    2 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }

                    3 -> {
                        val err = profile.error ?: ""
                        val msg = Protocols.genFriendlyMsg(err)
                        profileStatusText = if (msg != err) msg else getString(R.string.unavailable)
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }
                }

                val text = SpannableStringBuilder().apply {
                    append("\n" + profile.displayName())
                    append("\n")
                    append(
                        profile.displayType(),
                        ForegroundColorSpan(context.getProtocolColor(profile.type)),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append(" ")
                    append(
                        profileStatusText,
                        ForegroundColorSpan(profileStatusColor),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append("\n")
                }

                binding.nowTesting.text = text
                binding.progress.text = "$progress / $proxyN"
            }
        }

    }

    // 批量测速补齐（0.2.29 -13）：测完自动按延迟排；清理慢节点只删测过（status 1）且延迟超阈值的。
    private val slowPingThresholdMs = 1000

    // 测完自动按延迟排（0.2.29 -14 修 -13 的竞态）：
    // -13 是 fire-and-forget：排序和存结果各跑各的，reloadProfiles 读库时
    // 还是旧延迟，通知也可能先于落盘到，排序就丢了。
    // -14 按顺序来：先落盘结果，再改排序，最后直接刷新当前列表，不靠通知时序。
    private fun autoSortByDelayAfterTest(results: Collection<ProxyEntity>) {
        runOnDefaultDispatcher {
            // 1. 先把测速结果落盘，reloadProfiles 读库才能看到新延迟
            results.forEach {
                try {
                    ProfileManager.updateProfile(it)
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
            // 2. 改当前组排序为按延迟（落盘 + 发通知）
            val group = DataStore.currentGroup()
            if (group.order != GroupOrder.BY_DELAY) {
                group.order = GroupOrder.BY_DELAY
                GroupManager.updateGroup(group)
            }
            // 3. 直接刷新当前分组的列表，不等 GroupManager 通知
            runOnMainDispatcher {
                getCurrentGroupFragment()?.let {
                    it.proxyGroup.order = GroupOrder.BY_DELAY
                    it.adapter?.reloadProfiles()
                }
                toolbar.menu.findItem(R.id.action_order_by_delay)?.isChecked = true
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("EXPERIMENTAL_API_USAGE")
    fun pingTest(icmpPing: Boolean) {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        // Cross-process mutex (step ②): skip while the auto worker is testing.
        val testStartMs = System.currentTimeMillis()
        val autoMutex = DataStore.configurationStore.getString(AutoSpeedTest.MUTEX_KEY, "") ?: ""
        if (autoMutex.startsWith("auto:")) {
            val ts = autoMutex.substringAfter("auto:").toLongOrNull() ?: 0L
            if (testStartMs - ts < AutoSpeedTest.MUTEX_TTL_MS) {
                DataStore.runningTest = false
                return
            }
        }
        DataStore.configurationStore.putString(AutoSpeedTest.MUTEX_KEY, "manual:$testStartMs")
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val group = DataStore.currentGroup()

        val mainJob = runOnDefaultDispatcher {
            val allProfiles = RiftDatabase.proxyDao.getByGroup(group.id).filter {
                if (icmpPing) {
                    if (it.requireBean().canICMPing()) {
                        return@filter true
                    }
                } else {
                    if (it.requireBean().canTCPing()) {
                        return@filter true
                    }
                }
                return@filter false
            }
            // Multi-select (step ②): batch-test only the checked rows.
            val adapter = getCurrentGroupFragment()?.adapter
            val profilesList =
                if (adapter?.selectionMode == true && adapter.selectedIds.isNotEmpty()) {
                    allProfiles.filter { it.id in adapter.selectedIds }
                } else allProfiles
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList)
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(launch(Dispatchers.IO) {
                    while (isActive) {
                        val profile = profiles.poll() ?: break

                        profile.status = 0
                        var address = profile.requireBean().serverAddress
                        if (!address.isIpAddress()) {
                            try {
                                RiftApp.underlyingNetwork!!.getAllByName(address).apply {
                                    if (isNotEmpty()) {
                                        address = this[0].hostAddress
                                    }
                                }
                            } catch (ignored: UnknownHostException) {
                            }
                        }
                        if (!isActive) break
                        if (!address.isIpAddress()) {
                            profile.status = 2
                            profile.error = app.getString(R.string.connection_test_domain_not_found)
                            test.update(profile)
                            continue
                        }
                        try {
                            if (icmpPing) {
                                // removed
                            } else {
                                val socket =
                                    RiftApp.underlyingNetwork?.socketFactory?.createSocket()
                                        ?: Socket()
                                try {
                                    socket.soTimeout = 3000
                                    socket.bind(InetSocketAddress(0))
                                    val start = SystemClock.elapsedRealtime()
                                    socket.connect(
                                        InetSocketAddress(
                                            address, profile.requireBean().serverPort
                                        ), 3000
                                    )
                                    if (!isActive) break
                                    profile.status = 1
                                    profile.ping = (SystemClock.elapsedRealtime() - start).toInt()
                                    test.update(profile)
                                } finally {
                                    socket.closeQuietly()
                                }
                            }
                        } catch (e: Exception) {
                            if (!isActive) break
                            val message = e.readableMessage

                            if (icmpPing) {
                                profile.status = 2
                                profile.error = getString(R.string.connection_test_unreachable)
                            } else {
                                profile.status = 2
                                when {
                                    !message.contains("failed:") -> profile.error =
                                        getString(R.string.connection_test_timeout)

                                    else -> when {
                                        message.contains("ECONNREFUSED") -> {
                                            profile.error =
                                                getString(R.string.connection_test_refused)
                                        }

                                        message.contains("ENETUNREACH") -> {
                                            profile.error =
                                                getString(R.string.connection_test_unreachable)
                                        }

                                        else -> {
                                            profile.status = 3
                                            profile.error = message
                                        }
                                    }
                                }
                            }
                            test.update(profile)
                        }
                    }
                })
            }

            testJobs.joinAll()

            runOnMainDispatcher {
                autoSortByDelayAfterTest(test.results)
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                test.results.forEach {
                    try {
                        ProfileManager.updateProfile(it)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
                // 测速闪烁（2026-11-10）：不要再 postReload 全量刷新。
                // 每个 updateProfile 已经触发 onUpdated -> notifyItemChanged，
                // 即每项只刷一次；再加一次 reloadProfiles() -> notifyDataSetChanged()
                // 全表重绑，7 个节点就是 7 次单项 + 1 次全量在瞬间叠加，列表一闪。
                // Cross-process mutex (step ②): release our manual-test lock.
                val curMutex = DataStore.configurationStore.getString(AutoSpeedTest.MUTEX_KEY, "") ?: ""
                if (curMutex.startsWith("manual:")) {
                    DataStore.configurationStore.putString(AutoSpeedTest.MUTEX_KEY, "")
                }
                DataStore.runningTest = false
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[${group.displayName()}] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    /** 单节点测速（2026-11-13，0.2.23 -4）：节点卡 ⋮ 菜单“测速”用，只测这一个节点，
     * 不弹批量测速框；结果写回 profile，updateProfile 触发单项刷新。
     * TCP 直连逻辑与 pingTest 的单节点分支一致（3 秒超时）。 */
    @OptIn(DelicateCoroutinesApi::class)
    fun testSingleProfile(profile: ProxyEntity) {
        if (DataStore.runningTest) return
        runOnDefaultDispatcher {
            profile.status = 0
            ProfileManager.updateProfile(profile)
            var address = profile.requireBean().serverAddress
            if (!address.isIpAddress()) {
                try {
                    RiftApp.underlyingNetwork!!.getAllByName(address).apply {
                        if (isNotEmpty()) {
                            address = this[0].hostAddress
                        }
                    }
                } catch (ignored: UnknownHostException) {
                }
            }
            if (!address.isIpAddress()) {
                profile.status = 2
                profile.error = getString(R.string.connection_test_domain_not_found)
                ProfileManager.updateProfile(profile)
                return@runOnDefaultDispatcher
            }
            try {
                val socket =
                    RiftApp.underlyingNetwork?.socketFactory?.createSocket()
                        ?: Socket()
                try {
                    socket.soTimeout = 3000
                    socket.bind(InetSocketAddress(0))
                    val start = SystemClock.elapsedRealtime()
                    socket.connect(
                        InetSocketAddress(
                            address, profile.requireBean().serverPort
                        ), 3000
                    )
                    profile.status = 1
                    profile.ping = (SystemClock.elapsedRealtime() - start).toInt()
                } finally {
                    socket.closeQuietly()
                }
            } catch (e: Exception) {
                val message = e.readableMessage
                profile.status = 2
                when {
                    !message.contains("failed:") -> profile.error =
                        getString(R.string.connection_test_timeout)

                    message.contains("ECONNREFUSED") -> {
                        profile.error =
                            getString(R.string.connection_test_refused)
                    }

                    message.contains("ENETUNREACH") -> {
                        profile.error =
                            getString(R.string.connection_test_unreachable)
                    }

                    else -> {
                        profile.status = 3
                        profile.error = message
                    }
                }
            }
            ProfileManager.updateProfile(profile)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun urlTest() {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        // Cross-process mutex (step ②): skip while the auto worker is testing.
        val testStartMs = System.currentTimeMillis()
        val autoMutex = DataStore.configurationStore.getString(AutoSpeedTest.MUTEX_KEY, "") ?: ""
        if (autoMutex.startsWith("auto:")) {
            val ts = autoMutex.substringAfter("auto:").toLongOrNull() ?: 0L
            if (testStartMs - ts < AutoSpeedTest.MUTEX_TTL_MS) {
                DataStore.runningTest = false
                return
            }
        }
        DataStore.configurationStore.putString(AutoSpeedTest.MUTEX_KEY, "manual:$testStartMs")
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val group = DataStore.currentGroup()

        val mainJob = runOnDefaultDispatcher {
            val allProfiles = RiftDatabase.proxyDao.getByGroup(group.id)
            // Multi-select (step ②): batch-test only the checked rows.
            val adapter = getCurrentGroupFragment()?.adapter
            val profilesList =
                if (adapter?.selectionMode == true && adapter.selectedIds.isNotEmpty()) {
                    allProfiles.filter { it.id in adapter.selectedIds }
                } else allProfiles
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList)
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(launch(Dispatchers.IO) {
                    val urlTest = UrlTest() // note: this is NOT in bg process
                    while (isActive) {
                        val profile = profiles.poll() ?: break
                        profile.status = 0

                        try {
                            val result = urlTest.doTest(profile)
                            profile.status = 1
                            profile.ping = result
                        } catch (e: Exception) {
                            profile.status = 3
                            profile.error = e.readableMessage
                        }

                        test.update(profile)
                    }
                })
            }

            testJobs.joinAll()

            runOnMainDispatcher {
                autoSortByDelayAfterTest(test.results)
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                test.results.forEach {
                    try {
                        ProfileManager.updateProfile(it)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
                // 测速闪烁（2026-11-10）：不要再 postReload 全量刷新。
                // 每个 updateProfile 已经触发 onUpdated -> notifyItemChanged，
                // 即每项只刷一次；再加一次 reloadProfiles() -> notifyDataSetChanged()
                // 全表重绑，7 个节点就是 7 次单项 + 1 次全量在瞬间叠加，列表一闪。
                // Cross-process mutex (step ②): release our manual-test lock.
                val curMutex = DataStore.configurationStore.getString(AutoSpeedTest.MUTEX_KEY, "") ?: ""
                if (curMutex.startsWith("manual:")) {
                    DataStore.configurationStore.putString(AutoSpeedTest.MUTEX_KEY, "")
                }
                DataStore.runningTest = false
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[${group.displayName()}] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    inner class GroupPagerAdapter : FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, GroupFragment> = HashMap()

        fun reload(now: Boolean = false) {

            if (!select) {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            }

            runOnDefaultDispatcher {
                var newGroupList = ArrayList(RiftDatabase.groupDao.allGroups())
                if (newGroupList.isEmpty()) {
                    RiftDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(RiftDatabase.groupDao.allGroups())
                }
                newGroupList.find { it.ungrouped }?.let {
                    if (RiftDatabase.proxyDao.countByGroup(it.id) == 0L) {
                        newGroupList.remove(it)
                    }
                }

                var selectedGroup = selectedItem?.groupId ?: DataStore.currentGroupId()
                var set = false
                if (selectedGroup > 0L) {
                    selectedGroupIndex = newGroupList.indexOfFirst { it.id == selectedGroup }
                    set = true
                } else if (groupList.size == 1) {
                    selectedGroup = groupList[0].id
                    if (DataStore.selectedGroup != selectedGroup) {
                        DataStore.selectedGroup = selectedGroup
                    }
                }

                val runFunc = if (now) activity?.let { it::runOnUiThread } else groupPager::post
                if (runFunc != null) {
                    runFunc {
                        groupList = newGroupList
                        notifyDataSetChanged()
                        if (set) groupPager.setCurrentItem(selectedGroupIndex, false)
                        val hideTab = groupList.size < 2
                        tabLayout.isGone = hideTab
                        toolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
                        if (!select) {
                            groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                        }
                    }
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun createFragment(position: Int): Fragment {
            return GroupFragment().apply {
                proxyGroup = groupList[position]
                groupFragments[proxyGroup.id] = this
                if (position == selectedGroupIndex) {
                    selected = true
                }
            }
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        override fun containsItem(itemId: Long): Boolean {
            return groupList.any { it.id == itemId }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            tabLayout.post {
                groupList.add(group)

                if (groupList.any { !it.ungrouped }) tabLayout.post {
                    tabLayout.visibility = View.VISIBLE
                }

                notifyItemInserted(groupList.size - 1)
                tabLayout.getTabAt(groupList.size - 1)?.select()
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return

            tabLayout.post {
                groupList.removeAt(index)
                notifyItemRemoved(index)
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) return

            tabLayout.post {
                tabLayout.getTabAt(index)?.text = group.displayName()
            }
        }

        override suspend fun groupUpdated(groupId: Long) = Unit

        override suspend fun onAdd(profile: ProxyEntity) {
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload()
            }
        }

        override suspend fun onUpdated(data: TrafficData) = Unit

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && RiftDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            }
        }
    }

    class GroupFragment : Fragment() {

        lateinit var proxyGroup: ProxyGroup
        var selected = false

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            return LayoutProfileListBinding.inflate(inflater).root
        }

        lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
        var adapter: ConfigurationAdapter? = null

        // Auto speed test transient states (step ②): profileId -> 1 testing / 2 queued.
        // In-memory only, never written to DB; cleared when the round ends.
        val testStates = ConcurrentHashMap<Long, Int>()

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            if (::proxyGroup.isInitialized) {
                outState.putParcelable("proxyGroup", proxyGroup)
            }
        }

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            savedInstanceState?.getParcelable<ProxyGroup>("proxyGroup")?.also {
                proxyGroup = it
                onViewCreated(requireView(), null)
            }
        }

        private val isEnabled: Boolean
            get() {
                return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
            }

        lateinit var layoutManager: LinearLayoutManager
        lateinit var configurationListView: RecyclerView

        // 批三第 4 项：订阅卡（分组有订阅且非选择模式时显示）。
        private lateinit var subscriptionCard: View
        private lateinit var subscriptionName: TextView
        private lateinit var subscriptionUpdated: TextView
        private lateinit var subscriptionUpdate: View

        // 批三第 17 项：空状态（节点数为 0 时显示）。
        private lateinit var emptyState: View
        private lateinit var emptyStateAdd: View

        val select by lazy {
            try {
                (parentFragment as ConfigurationFragment).select
            } catch (e: Exception) {
                Logs.e(e)
                false
            }
        }
        val selectedItem by lazy {
            try {
                (parentFragment as ConfigurationFragment).selectedItem
            } catch (e: Exception) {
                Logs.e(e)
                null
            }
        }

        override fun onResume() {
            super.onResume()

            if (::configurationListView.isInitialized && configurationListView.size == 0) {
                configurationListView.adapter = adapter
                runOnDefaultDispatcher {
                    adapter?.reloadProfiles()
                }
            } else if (!::configurationListView.isInitialized) {
                onViewCreated(requireView(), null)
            }
            syncSubscriptionCard()
            checkOrderMenu()
            configurationListView.requestFocus()
        }

        fun checkOrderMenu() {
            if (select) return

            val pf = requireParentFragment() as? ToolbarFragment ?: return
            val menu = pf.toolbar.menu
            val origin = menu.findItem(R.id.action_order_origin)
            val byName = menu.findItem(R.id.action_order_by_name)
            val byDelay = menu.findItem(R.id.action_order_by_delay)
            when (proxyGroup.order) {
                GroupOrder.ORIGIN -> {
                    origin.isChecked = true
                }

                GroupOrder.BY_NAME -> {
                    byName.isChecked = true
                }

                GroupOrder.BY_DELAY -> {
                    byDelay.isChecked = true
                }
            }

            fun updateTo(order: Int) {
                if (proxyGroup.order == order) return
                runOnDefaultDispatcher {
                    proxyGroup.order = order
                    GroupManager.updateGroup(proxyGroup)
                }
            }

            origin.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.ORIGIN)
                true
            }
            byName.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_NAME)
                true
            }
            byDelay.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_DELAY)
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            if (!::proxyGroup.isInitialized) return

            configurationListView = view.findViewById(R.id.configuration_list)
            layoutManager = FixedLinearLayoutManager(configurationListView)
            configurationListView.layoutManager = layoutManager
            adapter = ConfigurationAdapter()
            ProfileManager.addListener(adapter!!)
            GroupManager.addListener(adapter!!)
            configurationListView.adapter = adapter
            configurationListView.setItemViewCacheSize(20)

            // 批三第 4 项：订阅卡。只在分组有订阅且非选择模式时显示；
            // 更新按钮复用 GroupUpdater.startUpdate（内部已防重复更新）。
            subscriptionCard = view.findViewById(R.id.subscription_card)
            subscriptionName = view.findViewById(R.id.subscription_name)
            subscriptionUpdated = view.findViewById(R.id.subscription_updated)
            subscriptionUpdate = view.findViewById(R.id.subscription_update)
            subscriptionUpdate.setOnClickListener {
                GroupUpdater.startUpdate(proxyGroup, true)
            }
            syncSubscriptionCard()

            // 批三第 17 项：空状态。节点数为 0 时显示一句话 + 添加按钮；
            // 按钮弹添加菜单（复用工具栏的 add_profile_menu 与 onMenuItemClick）。
            // 空状态只由 adapter 回调驱动：初次加载前 itemCount 是 0，不在这里
            // 预判，避免没数据时闪一下。
            emptyState = view.findViewById(R.id.empty_state)
            emptyStateAdd = view.findViewById(R.id.empty_state_add)
            emptyStateAdd.setOnClickListener {
                val popup = PopupMenu(requireContext(), emptyStateAdd)
                popup.menuInflater.inflate(R.menu.add_profile_menu, popup.menu)
                popup.menu.removeItem(R.id.action_search)
                popup.setOnMenuItemClickListener { item ->
                    (parentFragment as? ConfigurationFragment)?.onMenuItemClick(item) ?: false
                }
                popup.show()
            }
            adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() {
                    syncSubscriptionCard()
                    syncEmptyState()
                }

                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    syncEmptyState()
                }

                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    syncEmptyState()
                }
            })

            if (!select) {

                undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)

                ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
                ) {
                    override fun getSwipeDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ): Int {
                        return 0
                    }

                    override fun getDragDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) = if (isEnabled) super.getDragDirs(recyclerView, viewHolder) else 0

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    }

                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
                    ): Boolean {
                        adapter?.move(
                            viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                        )
                        return true
                    }

                    override fun clearView(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) {
                        super.clearView(recyclerView, viewHolder)
                        adapter?.commitMove()
                    }
                }).attachToRecyclerView(configurationListView)

            }

        }

        // 批三第 4 项：订阅卡。分组有订阅且非选择模式时显示订阅名 + 上次更新时间；
        // 从未更新过显示“从未更新”（复用 group_status_empty_subscription）。
        // 自建节点没有流量/到期数据，不画用量条。订阅更新后 groupUpdated 会触发
        // adapter 刷新，observer 的 onChanged 会顺带刷这张卡。
        private fun syncSubscriptionCard() {
            if (!::subscriptionCard.isInitialized) return
            val subscription = proxyGroup.subscription
            if (subscription == null || select) {
                subscriptionCard.isGone = true
                return
            }
            subscriptionCard.isVisible = true
            subscriptionName.text = proxyGroup.displayName()
            subscriptionUpdated.text = if (subscription.lastUpdated > 0) {
                getString(
                    R.string.home_subscription_updated,
                    Util.timeStamp2Text(subscription.lastUpdated * 1000L)
                )
            } else {
                getString(R.string.group_status_empty_subscription)
            }
        }

        // 批三第 17 项：空状态。只由 adapter 回调驱动，不在加载前预判。
        private fun syncEmptyState() {
            if (!::emptyState.isInitialized) return
            emptyState.isVisible = (adapter?.itemCount ?: 0) == 0
        }

        override fun onDestroy() {
            adapter?.let {
                ProfileManager.removeListener(it)
                GroupManager.removeListener(it)
            }

            super.onDestroy()

            if (!::undoManager.isInitialized) return
            undoManager.flush()
        }

        inner class ConfigurationAdapter : RecyclerView.Adapter<ConfigurationHolder>(),
            ProfileManager.Listener,
            GroupManager.Listener,
            UndoSnackbarManager.Interface<ProxyEntity> {

            init {
                setHasStableIds(true)
            }

            var configurationIdList: MutableList<Long> = mutableListOf()
            val configurationList = HashMap<Long, ProxyEntity>()

            // Multi-select for manual batch test (step ②): long-press a row to enter.
            val selectedIds = LinkedHashSet<Long>()
            var selectionMode = false

            fun exitSelectionMode() {
                if (!selectionMode && selectedIds.isEmpty()) return
                selectionMode = false
                selectedIds.clear()
                notifyDataSetChanged()
                (parentFragment as? ConfigurationFragment)?.refreshSelectionMenu()
            }

            private fun getItem(profileId: Long): ProxyEntity {
                var profile = configurationList[profileId]
                if (profile == null) {
                    profile = ProfileManager.getProfile(profileId)
                    if (profile != null) {
                        configurationList[profileId] = profile
                    }
                }
                return profile!!
            }

            private fun getItemAt(index: Int) = getItem(configurationIdList[index])

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): ConfigurationHolder {
                return ConfigurationHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.layout_profile, parent, false)
                )
            }

            override fun getItemId(position: Int): Long {
                return configurationIdList[position]
            }

            override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
                try {
                    holder.bind(getItemAt(position))
                } catch (ignored: NullPointerException) { // when group deleted
                }
            }

            override fun getItemCount(): Int {
                return configurationIdList.size
            }

            private val updated = HashSet<ProxyEntity>()

            fun filter(name: String) {
                if (name.isEmpty()) {
                    reloadProfiles()
                    return
                }
                configurationIdList.clear()
                val lower = name.lowercase()
                configurationIdList.addAll(configurationList.filter {
                    it.value.displayName().lowercase().contains(lower) ||
                            it.value.displayType().lowercase().contains(lower) ||
                            it.value.displayAddress().lowercase().contains(lower)
                }.keys)
                notifyDataSetChanged()
            }

            fun move(from: Int, to: Int) {
                val first = getItemAt(from)
                var previousOrder = first.userOrder
                val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                    -1, to + 1 downTo from
                )
                for (i in range) {
                    val next = getItemAt(i + step)
                    val order = next.userOrder
                    next.userOrder = previousOrder
                    previousOrder = order
                    configurationIdList[i] = next.id
                    updated.add(next)
                }
                first.userOrder = previousOrder
                configurationIdList[to] = first.id
                updated.add(first)
                notifyItemMoved(from, to)
            }

            fun commitMove() = runOnDefaultDispatcher {
                updated.forEach { RiftDatabase.proxyDao.updateProxy(it) }
                updated.clear()
            }

            fun remove(pos: Int) {
                if (pos < 0) return
                configurationIdList.removeAt(pos)
                notifyItemRemoved(pos)
            }

            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                for ((index, item) in actions) {
                    configurationListView.post {
                        configurationList[item.id] = item
                        configurationIdList.add(index, item.id)
                        notifyItemInserted(index)
                    }
                }
            }

            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                val profiles = actions.map { it.second }
                runOnDefaultDispatcher {
                    for (entity in profiles) {
                        ProfileManager.deleteProfile(entity.groupId, entity.id)
                    }
                }
            }

            override suspend fun onAdd(profile: ProxyEntity) {
                if (profile.groupId != proxyGroup.id) return

                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val pos = itemCount
                    configurationList[profile.id] = profile
                    configurationIdList.add(profile.id)
                    notifyItemInserted(pos)
                }
            }

            override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
                if (profile.groupId != proxyGroup.id) return
                val index = configurationIdList.indexOf(profile.id)
                if (index < 0) return
                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    configurationList[profile.id] = profile
                    notifyItemChanged(index)
                    //
                    val oldProfile = configurationList[profile.id]
                    if (noTraffic && oldProfile != null) {
                        runOnDefaultDispatcher {
                            onUpdated(
                                TrafficData(
                                    id = profile.id,
                                    rx = oldProfile.rx,
                                    tx = oldProfile.tx
                                )
                            )
                        }
                    }
                }
            }

            override suspend fun onUpdated(data: TrafficData) {
                try {
                    val index = configurationIdList.indexOf(data.id)
                    if (index != -1) {
                        val holder = layoutManager.findViewByPosition(index)
                            ?.let { configurationListView.getChildViewHolder(it) } as ConfigurationHolder?
                        if (holder != null) {
                            onMainDispatcher {
                                holder.bind(holder.entity, data)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }

            override suspend fun onRemoved(groupId: Long, profileId: Long) {
                if (groupId != proxyGroup.id) return
                val index = configurationIdList.indexOf(profileId)
                if (index < 0) return

                configurationListView.post {
                    configurationIdList.removeAt(index)
                    configurationList.remove(profileId)
                    notifyItemRemoved(index)
                }
            }

            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit

            override suspend fun groupUpdated(group: ProxyGroup) {
                if (group.id != proxyGroup.id) return
                proxyGroup = group
                reloadProfiles()
            }

            override suspend fun groupUpdated(groupId: Long) {
                if (groupId != proxyGroup.id) return
                proxyGroup = RiftDatabase.groupDao.getById(groupId) ?: return
                reloadProfiles()
            }

            fun reloadProfiles() {
                // Multi-select does not survive a full reload (step ②) — a
                // finished auto-test round ends it too, by design.
                if (selectionMode || selectedIds.isNotEmpty()) {
                    selectionMode = false
                    selectedIds.clear()
                }
                var newProfiles = RiftDatabase.proxyDao.getByGroup(proxyGroup.id)
                when (proxyGroup.order) {
                    GroupOrder.BY_NAME -> {
                        newProfiles = newProfiles.sortedBy { it.displayName() }

                    }

                    GroupOrder.BY_DELAY -> {
                        newProfiles =
                            newProfiles.sortedBy { if (it.status == 1) it.ping else 114514 }
                    }
                }

                configurationList.clear()
                configurationList.putAll(newProfiles.associateBy { it.id })
                val newProfileIds = newProfiles.map { it.id }

                var selectedProfileIndex = -1

                if (selected) {
                    val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                    selectedProfileIndex = newProfileIds.indexOf(selectedProxy)
                }

                configurationListView.post {
                    configurationIdList.clear()
                    configurationIdList.addAll(newProfileIds)
                    notifyDataSetChanged()

                    if (selectedProfileIndex != -1) {
                        configurationListView.scrollTo(selectedProfileIndex, true)
                    } else if (newProfiles.isNotEmpty()) {
                        configurationListView.scrollTo(0, true)
                    }

                    // A reload ends multi-select (step ②); sync the menu.
                    (parentFragment as? ConfigurationFragment)?.refreshSelectionMenu()
                }
            }

        }

        val profileAccess = Mutex()
        val reloadAccess = Mutex()

        inner class ConfigurationHolder(val view: View) : RecyclerView.ViewHolder(view),
            PopupMenu.OnMenuItemClickListener {

            lateinit var entity: ProxyEntity

            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileFlag: TextView = view.findViewById(R.id.profile_flag)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profilePrimary: TextView = view.findViewById(R.id.profile_primary)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)
            val profileCheckbox: CheckBox = view.findViewById(R.id.profile_checkbox)

            val trafficText: TextView = view.findViewById(R.id.traffic_text)
            val selectedView: LinearLayout = view.findViewById(R.id.selected_view)
            val overflowButton: ImageView = view.findViewById(R.id.overflow)

            // 只刷延时/流量：测速完成（payload）时调用。两行卡（2026-11-13，0.2.23 -4）：
            // 第一行右边是延时（测过才显示，20sp，按延时着色），第二行右边是流量；
            // 地址行已删，不再把流量挪进延时位。
            private fun bindStatusTraffic(proxyEntity: ProxyEntity, trafficData: TrafficData? = null) {
                var rx = proxyEntity.rx
                var tx = proxyEntity.tx
                if (trafficData != null) {
                    // use new data
                    tx = trafficData.tx
                    rx = trafficData.rx
                }

                val showTraffic = rx + tx != 0L
                trafficText.isVisible = showTraffic
                if (showTraffic) {
                    trafficText.text = view.context.getString(
                        R.string.traffic,
                        Formatter.formatFileSize(view.context, tx),
                        Formatter.formatFileSize(view.context, rx)
                    )
                }

                if (proxyEntity.status <= 0) {
                    profileStatus.textSize = 15f
                    profileStatus.text = ""
                } else if (proxyEntity.status == 1) {
                    profileStatus.text = "${proxyEntity.ping} ms"
                    profileStatus.textSize = 20f
                    profileStatus.setTextColor(requireContext().getColour(when {
                        proxyEntity.ping < 200 -> R.color.color_rift_success
                        proxyEntity.ping <= 400 -> R.color.color_rift_accent
                        else -> R.color.color_rift_danger
                    }))
                } else {
                    profileStatus.textSize = 15f
                    profileStatus.setTextColor(requireContext().getColour(R.color.color_rift_danger))
                    if (proxyEntity.status == 2) {
                        profileStatus.text = proxyEntity.error
                    }
                }

                if (proxyEntity.status == 3) {
                    val err = proxyEntity.error ?: "<?>"
                    val msg = Protocols.genFriendlyMsg(err)
                    profileStatus.text = if (msg != err) msg else getString(R.string.unavailable)
                    profileStatus.setOnClickListener {
                        alert(err).tryToShow()
                    }
                } else {
                    profileStatus.setOnClickListener(null)
                }
            }

            // 国旗（2026-11-13，0.2.23 -10）：名字前面一面旗。先名字解析，
            // 再读缓存，最后后台查服务器 IP 归属（只查一次、记缓存）；
            // 查不到就不显示。holder 复用后只认 entity.id，别把旧结果
            // 刷到别的节点上。
            private fun bindFlag(proxyEntity: ProxyEntity) {
                // 列表旗优先用出口（2026-11-17，PRE -23）：正连着且这行就是当前
                // 节点、出口国家已查到时，用出口画旗；断开或没查到时走下面
                // 名字/入口那套。
                if (DataStore.serviceState.connected) {
                    val exitCountry = (parentFragment as? ConfigurationFragment)
                        ?.exitCountryForCurrentNode(proxyEntity.id)
                    if (exitCountry != null) {
                        setFlag(exitCountry)
                        return
                    }
                }
                // 记住的出口（PRE -24）：以前连过这台、记下了出口国家，断开也
                // 显示真实出口；重连会刷新。
                val recorded = DataStore.nodeEgressCountry(proxyEntity.id)
                if (recorded != null) {
                    setFlag(recorded)
                    return
                }
                val name = proxyEntity.displayName() ?: ""
                val codeFromName = NodeFlag.countryCodeForName(name)
                if (codeFromName != null) {
                    setFlag(codeFromName)
                    return
                }
                val server = try {
                    proxyEntity.requireBean().serverAddress
                } catch (_: Exception) {
                    null
                }
                if (server.isNullOrBlank()) {
                    profileFlag.isGone = true
                    return
                }
                val cached = NodeFlag.cachedCountryCode(server)
                if (cached != null) {
                    setFlag(cached)
                    return
                }
                profileFlag.isGone = true
                if (!NodeFlag.markFetching(server)) return
                runOnDefaultDispatcher {
                    val code = NodeFlag.fetchCountryCode(server)
                    if (code != null) NodeFlag.cacheCountryCode(server, code)
                    NodeFlag.unmarkFetching(server)
                    if (code != null) {
                        onMainDispatcher {
                            if (entity.id == proxyEntity.id) setFlag(code)
                        }
                    }
                }
            }

            private fun setFlag(countryCode: String) {
                val flag = EgressIp.flagEmoji(countryCode)
                if (flag.isBlank()) {
                    profileFlag.isGone = true
                } else {
                    profileFlag.text = flag
                    profileFlag.isVisible = true
                }
            }

            fun bind(proxyEntity: ProxyEntity, trafficData: TrafficData? = null) {
                if (parentFragment !is ConfigurationFragment) return

                entity = proxyEntity

                view.setOnClickListener {
                    if (adapter?.selectionMode == true) {
                        toggleSelection(proxyEntity.id)
                    } else if (select) {
                        (requireActivity() as SelectCallback).returnProfile(proxyEntity.id)
                    } else {
                        selectProfile(proxyEntity)
                    }
                }

                // Multi-select (step ②): long-press any row to enter, tap to toggle.
                view.setOnLongClickListener {
                    if (adapter?.selectionMode != true && !select) {
                        adapter?.selectionMode = true
                        adapter?.selectedIds?.add(proxyEntity.id)
                        adapter?.notifyDataSetChanged()
                        (parentFragment as? ConfigurationFragment)?.refreshSelectionMenu()
                        true
                    } else {
                        false
                    }
                }

                profileName.text = proxyEntity.displayName()
                profileType.text = proxyEntity.displayType()
                // 主备组（2026-11-17，PRE -22）：本组主节点显示“主”标记。
                profilePrimary.isVisible =
                    DataStore.groupPrimaryProfile(proxyGroup.id) == proxyEntity.id
                bindFlag(proxyEntity)

                // Multi-select checkbox (step ②): always re-set on bind — holder
                // reuse would otherwise carry a stale checked state to another row.
                profileCheckbox.isVisible = adapter?.selectionMode == true
                profileCheckbox.isChecked = adapter?.selectedIds?.contains(proxyEntity.id) == true
                profileCheckbox.setOnClickListener { toggleSelection(proxyEntity.id) }

                // Auto speed test transient state (step ②): shown instead of the
                // stored ping while the round is running; never written to DB.
                val testState = testStates[proxyEntity.id]
                if (testState != null) {
                    profileStatus.textSize = 15f
                    profileStatus.setTextColor(
                        requireContext().getColour(R.color.color_rift_accent)
                    )
                    profileStatus.text = when (testState) {
                        AutoSpeedTest.STATE_TESTING ->
                            getString(R.string.connection_test_testing)
                        AutoSpeedTest.STATE_QUEUED ->
                            getString(R.string.auto_speed_test_queued)
                        else -> ""
                    }
                    profileStatus.setOnClickListener(null)
                } else {
                    bindStatusTraffic(proxyEntity, trafficData)
                }

                // ⋮ 菜单（2026-11-13，0.2.23 -4）：连接/测速/编辑/分享/二维码/删除；
                // 选节点模式（select）下不显示，点卡就是选节点。
                overflowButton.isGone = select
                overflowButton.setOnClickListener { showItemMenu(it, proxyEntity) }

                runOnDefaultDispatcher {
                    val selected = (selectedItem?.id ?: DataStore.selectedProxy) == proxyEntity.id
                    onMainDispatcher {
                        selectedView.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                    }
                }
            }

            /** Multi-select (step ②): flip one row's selection, rebind it, sync menu. */
            private fun toggleSelection(id: Long) {
                val ids = adapter?.selectedIds ?: return
                if (id in ids) ids.remove(id) else ids.add(id)
                val index = adapter?.configurationIdList?.indexOf(id) ?: -1
                if (index >= 0) adapter?.notifyItemChanged(index)
                (parentFragment as? ConfigurationFragment)?.refreshSelectionMenu()
            }

            /** 点卡 / ⋮ 菜单“连接”共用：选这个节点，切节点后若 VPN 在跑就重载。 */
            private fun selectProfile(proxyEntity: ProxyEntity) {
                runOnDefaultDispatcher {
                    var update: Boolean
                    var lastSelected: Long
                    profileAccess.withLock {
                        update = DataStore.selectedProxy != proxyEntity.id
                        lastSelected = DataStore.selectedProxy
                        DataStore.selectedProxy = proxyEntity.id
                        onMainDispatcher {
                            selectedView.visibility = View.VISIBLE
                        }
                    }

                    if (update) {
                        ProfileManager.postUpdate(lastSelected)
                        if (DataStore.serviceState.canStop && reloadAccess.tryLock()) {
                            RiftApp.reloadService()
                            reloadAccess.unlock()
                        }
                    } else if (RiftApp.isTv) {
                        if (DataStore.serviceState.started) {
                            RiftApp.stopService()
                        } else {
                            RiftApp.startService()
                        }
                    }
                }
            }

            /** ⋮ 菜单（2026-11-13，0.2.23 -4）。链式节点没有链接，分享/二维码去掉；
             * 正在跑的节点不能编辑/删除，对应项置灰。 */
            private fun showItemMenu(anchor: View, proxyEntity: ProxyEntity) {
                val popup = PopupMenu(requireContext(), anchor)
                popup.menuInflater.inflate(R.menu.profile_item_menu, popup.menu)
                val selected = (selectedItem?.id ?: DataStore.selectedProxy) == proxyEntity.id
                val started = selected && DataStore.serviceState.started &&
                    DataStore.currentProfile == proxyEntity.id
                if (proxyEntity.type == ProxyEntity.TYPE_CHAIN || !proxyEntity.haveLink()) {
                    popup.menu.removeItem(R.id.action_item_share)
                    popup.menu.removeItem(R.id.action_item_qr)
                }
                // 主备组（2026-11-17，PRE -22）：组内多于 1 个节点、且不是当前主节点时，
                // 显示“设为本组主节点”。holder 与 adapter 是平级（都在 GroupFragment 内），
                // 列表要走 adapter?.configurationIdList，不能直接用（-22 #9 编译错的教训）。
                if ((adapter?.configurationIdList?.size ?: 0) <= 1 ||
                    DataStore.groupPrimaryProfile(proxyGroup.id) == proxyEntity.id
                ) {
                    popup.menu.removeItem(R.id.action_item_set_primary)
                }
                popup.menu.findItem(R.id.action_item_edit)?.isEnabled = !started
                popup.menu.findItem(R.id.action_item_delete)?.isEnabled = !started
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_item_connect -> selectProfile(proxyEntity)
                        R.id.action_item_test -> (parentFragment as? ConfigurationFragment)
                            ?.testSingleProfile(proxyEntity)
                        R.id.action_item_set_primary -> {
                            runOnDefaultDispatcher {
                                DataStore.setGroupPrimaryProfile(proxyGroup.id, proxyEntity.id)
                                onMainDispatcher {
                                    Toast.makeText(
                                        view.context, R.string.primary_set, Toast.LENGTH_SHORT
                                    ).show()
                                    adapter?.notifyDataSetChanged()
                                }
                            }
                        }
                        R.id.action_item_edit -> editProfile(proxyEntity)
                        R.id.action_item_share -> showShare(anchor, proxyEntity)
                        R.id.action_item_qr -> showQrCode(proxyEntity)
                        R.id.action_item_delete -> deleteProfile(proxyEntity)
                    }
                    true
                }
                popup.show()
            }

            /** ⋮ 菜单“编辑”：原来卡上编辑按钮的逻辑。 */
            private fun editProfile(proxyEntity: ProxyEntity) {
                if (proxyEntity.type == ProxyEntity.TYPE_WG) {
                    alert(getString(R.string.wireguard_removed)).tryToShow()
                    return
                }
                view.context.startActivity(
                    proxyEntity.settingIntent(
                        view.context, proxyGroup.type == GroupType.SUBSCRIPTION
                    )
                )
            }

            /** ⋮ 菜单“删除”：原来卡上删除按钮的逻辑。 */
            private fun deleteProfile(proxyEntity: ProxyEntity) {
                adapter?.let {
                    val index = it.configurationIdList.indexOf(proxyEntity.id)
                    it.remove(index)
                    undoManager.remove(index to proxyEntity)
                }
            }

            /** ⋮ 菜单“分享”：原来卡上分享按钮弹的分享菜单。 */
            private fun showShare(anchor: View, proxyEntity: ProxyEntity) {
                val popup = PopupMenu(requireContext(), anchor)
                popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

                when {
                    !proxyEntity.haveStandardLink() -> {
                        popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                        popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                            R.id.action_standard_clipboard
                        )
                    }

                    !proxyEntity.haveLink() -> {
                        popup.menu.removeItem(R.id.action_group_qr)
                        popup.menu.removeItem(R.id.action_group_clipboard)
                    }
                }

                popup.setOnMenuItemClickListener(this@ConfigurationHolder)
                popup.show()
            }

            /** ⋮ 菜单“二维码”：有标准链接就出标准码，没有就出通用码。 */
            private fun showQrCode(proxyEntity: ProxyEntity) {
                try {
                    currentName = proxyEntity.displayName()!!
                    when {
                        proxyEntity.haveStandardLink() -> showCode(proxyEntity.toStdLink())
                        proxyEntity.haveLink() -> showCode(proxyEntity.requireBean().toUniversalLink())
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    (activity as MainActivity).snackbar(e.readableMessage).show()
                }
            }

            var currentName = ""
            fun showCode(link: String) {
                QRCodeDialog(link, currentName).showAllowingStateLoss(parentFragmentManager)
            }

            fun export(link: String) {
                val success = RiftApp.trySetPrimaryClip(link)
                (activity as MainActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            override fun onMenuItemClick(item: MenuItem): Boolean {
                try {
                    currentName = entity.displayName()!!
                    when (item.itemId) {
                        R.id.action_standard_qr -> showCode(entity.toStdLink())
                        R.id.action_standard_clipboard -> export(entity.toStdLink())
                        R.id.action_universal_qr -> showCode(entity.requireBean().toUniversalLink())
                        R.id.action_universal_clipboard -> export(
                            entity.requireBean().toUniversalLink()
                        )

                        R.id.action_config_export_clipboard -> export(entity.exportConfig().first)
                        R.id.action_config_export_file -> {
                            val cfg = entity.exportConfig()
                            DataStore.serverConfig = cfg.first
                            startFilesForResult(
                                (parentFragment as ConfigurationFragment).exportConfig, cfg.second
                            )
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    (activity as MainActivity).snackbar(e.readableMessage).show()
                    return true
                }
                return true
            }
        }

    }

    private val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(data)!!
                            .bufferedWriter()
                            .use {
                                it.write(DataStore.serverConfig)
                            }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }

                }
            }
        }

    private fun cancelSearch(searchView: SearchView) {
        searchView.onActionViewCollapsed()
        searchView.clearFocus()
    }

}
