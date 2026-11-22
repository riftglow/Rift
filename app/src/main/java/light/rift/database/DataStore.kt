package light.rift.database

import android.os.Binder
import androidx.preference.PreferenceDataStore
import light.rift.CONNECTION_TEST_URL
import light.rift.GroupType
import light.rift.IPv6Mode
import light.rift.Key
import light.rift.TunImplementation
import light.rift.bg.BaseService
import light.rift.bg.VpnService
import light.rift.database.preference.OnPreferenceDataStoreChangeListener
import light.rift.database.preference.PublicDatabase
import light.rift.database.preference.RoomPreferenceDataStore
import light.rift.ui.WebDavBackup
import light.rift.ktx.boolean
import light.rift.ktx.int
import light.rift.ktx.long
import light.rift.ktx.parsePort
import light.rift.ktx.string
import light.rift.ktx.stringToInt
import light.rift.ktx.stringToIntIfExists
import light.rift.core.TempDatabase

object DataStore : OnPreferenceDataStoreChangeListener {

    // share service state in main & bg process
    @Volatile
    var serviceState = BaseService.State.Idle

    val configurationStore = RoomPreferenceDataStore(PublicDatabase.kvPairDao)
    val profileCacheStore = RoomPreferenceDataStore(TempDatabase.profileCacheDao)

    // last used, but may not be running
    var currentProfile by configurationStore.long(Key.PROFILE_CURRENT)

    var selectedProxy by configurationStore.long(Key.PROFILE_ID)
    var selectedGroup by configurationStore.long(Key.PROFILE_GROUP) { currentGroupId() } // "ungrouped" group id = 1

    // only in bg process
    var vpnService: VpnService? = null
    var baseService: BaseService.Interface? = null

    // main

    var runningTest = false

    fun currentGroupId(): Long {
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) return currentSelected
        val groups = RiftDatabase.groupDao.allGroups()
        if (groups.isNotEmpty()) {
            val groupId = groups[0].id
            selectedGroup = groupId
            return groupId
        }
        val groupId = RiftDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
        selectedGroup = groupId
        return groupId
    }

    fun currentGroup(): ProxyGroup {
        var group: ProxyGroup? = null
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) {
            group = RiftDatabase.groupDao.getById(currentSelected)
        }
        if (group != null) return group
        val groups = RiftDatabase.groupDao.allGroups()
        if (groups.isEmpty()) {
            group = ProxyGroup(ungrouped = true).apply {
                id = RiftDatabase.groupDao.createGroup(this)
            }
        } else {
            group = groups[0]
        }
        selectedGroup = group.id
        return group
    }

    // Batch 2+3 (2026-11-17, PRE -22): per-group outbound mode + primary profile,
    // stored as dynamic keys in the main kv store — no DB migration, no entity change.
    // Mode: "selector" (manual, default) or "urltest" (core auto-picks fastest member).
    fun groupOutboundMode(groupId: Long): String =
        configurationStore.getString("groupOutboundMode_$groupId", "selector") ?: "selector"

    fun setGroupOutboundMode(groupId: Long, mode: String) =
        configurationStore.putString("groupOutboundMode_$groupId", mode)

    // Primary (主) member of a group, 0 = none (fall back to current main / first member).
    fun groupPrimaryProfile(groupId: Long): Long =
        configurationStore.getLong("groupPrimaryProfile_$groupId", 0L)

    fun setGroupPrimaryProfile(groupId: Long, profileId: Long) =
        configurationStore.putLong("groupPrimaryProfile_$groupId", profileId)

    // 列表旗记住出口（2026-11-17，PRE -24）：每台连过的节点记下它测到的出口国家，
    // 断开也显示真实出口；重连/切节点时刷新。null = 这台还没连过、没测到。
    fun nodeEgressCountry(nodeId: Long): String? =
        configurationStore.getString("nodeEgressCountry_$nodeId", "")?.takeIf { it.isNotBlank() }

    fun setNodeEgressCountry(nodeId: Long, countryCode: String) =
        configurationStore.putString("nodeEgressCountry_$nodeId", countryCode)

    // 删组/删节点时清掉对应的动态 key，不留孤 key 在 kv 表里。
    // 组 key：出站模式、主节点、订阅提醒的三处去重标记。
    fun removeGroupKeys(groupId: Long) {
        configurationStore.remove("groupOutboundMode_$groupId")
        configurationStore.remove("groupPrimaryProfile_$groupId")
        configurationStore.remove("subReminderExpireDay_$groupId")
        configurationStore.remove("subReminderTraffic_$groupId")
        configurationStore.remove("subReminderExpired_$groupId")
    }

    // 节点 key：记住的出口国家。
    fun removeNodeKeys(nodeId: Long) {
        configurationStore.remove("nodeEgressCountry_$nodeId")
    }

    // 主节点被删时清掉指向它的主节点 key（缺省即 0 = 无主节点）。
    fun clearGroupPrimaryProfile(groupId: Long) {
        configurationStore.remove("groupPrimaryProfile_$groupId")
    }

    fun selectedGroupForImport(): Long {
        val current = currentGroup()
        if (current.type == GroupType.BASIC) return current.id
        val groups = RiftDatabase.groupDao.allGroups()
        return groups.find { it.type == GroupType.BASIC }!!.id
    }

    var appTLSVersion by configurationStore.string(Key.APP_TLS_VERSION) { "1.2" }
    // 2026-11-07：与 settings_misc.xml 默认值 "1.2" 对齐（此前代码默认 ""）。
    // RawUpdater 只对 "1.3" 做 restrictedTLS，"1.2" 与 "" 行为相同，此处只为与 XML 一致。
    var enableClashAPI by configurationStore.boolean(Key.ENABLE_CLASH_API)
    // 安全加固（0.2.29 -26）：值由 SecurityHardening.ensureInitialized() 在配置构建时生成一次并持久化。
    var clashApiSecret by configurationStore.string(Key.CLASH_API_SECRET) { "" }
    var mixedInboundUser by configurationStore.string(Key.MIXED_INBOUND_USER) { "rift" }
    var mixedInboundPass by configurationStore.string(Key.MIXED_INBOUND_PASS) { "" }
    var localProxyAuthEnabled by configurationStore.boolean(Key.LOCAL_PROXY_AUTH) { true }
    // 应用内更新（0.2.29 -26）：待安装的版本名（用于复用已下载的 APK）、上次自动检查时间。
    var pendingUpdateVersion by configurationStore.string(Key.PENDING_UPDATE_VERSION) { "" }
    var lastUpdateCheckTime by configurationStore.long(Key.LAST_UPDATE_CHECK) { 0L }
    var showBottomBar by configurationStore.boolean(Key.SHOW_BOTTOM_BAR)
    // 玻璃效果（2026-11-19 -34 底栏，-35 扩展到弹窗）：默认开，仅 Android 12+ 且非低内存设备生效。
    var glassEffect by configurationStore.boolean(Key.GLASS_EFFECT) { true }

    var allowInsecureOnRequest by configurationStore.boolean(Key.ALLOW_INSECURE_ON_REQUEST)
    var networkChangeResetConnections by configurationStore.boolean(Key.NETWORK_CHANGE_RESET_CONNECTIONS) { true }
    var wakeResetConnections by configurationStore.boolean(Key.WAKE_RESET_CONNECTIONS) { false }
    // 2026-11-07：唤醒重置默认改关。实测：小米 14 Pro 关后 2h08m 息屏亮屏干净、
    // 小米 15 关后 5h51m 过夜亮屏秒进外网（68 条 VLESS 代理连接在走），
    // 均无需切节点；开着反而会在亮屏时把连接全关掉、服务回得慢。
    // 开关本身保留，已存的设置值不受影响；开着时唤醒仍走 reload（见 BaseService）。

    // WebDAV backup: server / username in plain DataStore, password only as
    // AndroidKeyStore-encrypted ciphertext (see WebDavBackup).
    var webdavServer by configurationStore.string(Key.WEBDAV_SERVER) { WebDavBackup.DEFAULT_SERVER }
    var webdavUsername by configurationStore.string(Key.WEBDAV_USERNAME) { "" }
    var webdavPasswordEncrypted by configurationStore.string(Key.WEBDAV_PASSWORD) { "" }

    // Auto backup (2026-11-10): on by default; lastTime 0 = never ran.
    var autoBackupEnabled by configurationStore.boolean(Key.AUTO_BACKUP_ENABLED) { true }
    var autoBackupLastTime by configurationStore.long(Key.AUTO_BACKUP_LAST_TIME) { 0L }
    var autoBackupLastResult by configurationStore.string(Key.AUTO_BACKUP_LAST_RESULT) { "" }
    // Last manual WebDAV upload (backup page "Backup now" row).
    var webdavLastUploadTime by configurationStore.long(Key.WEBDAV_LAST_UPLOAD_TIME) { 0L }
    var webdavLastUploadResult by configurationStore.string(Key.WEBDAV_LAST_UPLOAD_RESULT) { "" }
    // Restore strategy (2026-11-10): 0 = overwrite, 1 = merge.
    var restoreStrategy by configurationStore.int(Key.RESTORE_STRATEGY) { 0 }
    // Routing mode (2026-11-10): 0 = rule, 1 = global, 2 = direct.
    var routeMode by configurationStore.int(Key.ROUTE_MODE) { 0 }

    // 用量统计（2026-11-12，0.2.21）：每日流量桶 JSON 与结算日，见 TrafficStatsStore。
    var trafficDailyJson by configurationStore.string("trafficDailyJson") { "" }
    var trafficBillingDay by configurationStore.int("trafficBillingDay") { 1 }
    // 清除时间点（2026-11-12，0.2.21 -3）：首页“清除流量统计”写当前毫秒，
    // 后台服务据此丢掉清之前攒的待刷量、把内存累计清零，别又写回来。
    var trafficClearEpoch by configurationStore.long("trafficClearEpoch") { 0L }
    // 波形历史（2026-11-12，0.2.21 -5）：service 每 60 秒把最近 60 个
    // 下行速率（bytes/s）写成 JSON 数组，供首页波形图读出来当初始样本。
    var sparklineHistoryJson by configurationStore.string("sparklineHistoryJson") { "" }
    // 节点国旗缓存（2026-11-13，0.2.23 -10）：serverAddress -> 国家码 JSON，见 NodeFlag。
    // 名字里没地名的节点靠它记 ipwho.is 查到的归属，下次直接读缓存，不再联网。
    var nodeFlagJson by configurationStore.string("nodeFlagJson") { "" }

    //

    var isExpert by configurationStore.boolean(Key.APP_EXPERT)
    var appTheme by configurationStore.int(Key.APP_THEME)
    var nightTheme by configurationStore.stringToInt(Key.NIGHT_THEME)
    var serviceMode by configurationStore.string(Key.SERVICE_MODE) { Key.MODE_VPN }

    var trafficSniffing by configurationStore.stringToInt(Key.TRAFFIC_SNIFFING) { 1 }
    var resolveDestination by configurationStore.boolean(Key.RESOLVE_DESTINATION)

    var mtu by configurationStore.stringToInt(Key.MTU) { 9000 }

    var bypassLan by configurationStore.boolean(Key.BYPASS_LAN)
    var bypassLanInCore by configurationStore.boolean(Key.BYPASS_LAN_IN_CORE)

    var allowAccess by configurationStore.boolean(Key.ALLOW_ACCESS)
    var speedInterval by configurationStore.stringToInt(Key.SPEED_INTERVAL) { 1000 }
    // 2026-11-07：与 settings_general.xml 默认值 1000 对齐（此前代码默认 0）。
    // TrafficLooper.loop() 在 delayMs == 0 时直接 return，流量统计永不更新。
    var showGroupInNotification by configurationStore.boolean("showGroupInNotification")

    var globalCustomConfig by configurationStore.string(Key.GLOBAL_CUSTOM_CONFIG) { "" }

    var remoteDns by configurationStore.string(Key.REMOTE_DNS) { "https://dns.google/dns-query" }
    var directDns by configurationStore.string(Key.DIRECT_DNS) { "https://223.5.5.5/dns-query" }
    var enableDnsRouting by configurationStore.boolean(Key.ENABLE_DNS_ROUTING) { true }
    var enableFakeDns by configurationStore.boolean(Key.ENABLE_FAKEDNS) { true }

    var rulesProvider by configurationStore.stringToInt(Key.RULES_PROVIDER) { 4 }
    var logLevel by configurationStore.stringToInt(Key.LOG_LEVEL)
    var logBufSize by configurationStore.int(Key.LOG_BUF_SIZE) { 0 }
    var acquireWakeLock by configurationStore.boolean(Key.ACQUIRE_WAKE_LOCK)

    // hopefully hashCode = mHandle doesn't change, currently this is true from KitKat to Nougat
    private val userIndex by lazy { Binder.getCallingUserHandle().hashCode() }

    /** 本 profile 的默认 mixed 端口（2080 + userIndex）。副 profile 下它不是 2080， */
    val defaultMixedPort: Int get() = 2080 + userIndex

    var mixedPort: Int
        get() = getLocalPort(Key.MIXED_PORT, 2080)
        set(value) = saveLocalPort(Key.MIXED_PORT, value)

    /** mixedPort 是不是随机分配的。true = ensureInitialized 随机出的（可自动重置）；
     * false = 用户在设置里手动改的（端口冲突时绝不静默改掉）。默认 true：老用户
     * 的随机端口本来就是自动分配的；手动改过端口的用户下次再改时会置 false。 */
    var mixedPortAuto by configurationStore.boolean(Key.MIXED_PORT_AUTO) { true }

    /** 端口被别的进程抢占致服务起不来时，把 mixedPort 打回默认，下次启动重新随机。 */
    fun resetMixedPort() {
        mixedPort = defaultMixedPort
    }

    fun initGlobal() {
        if (configurationStore.getString(Key.MIXED_PORT) == null) {
            mixedPort = mixedPort
        }
    }


    private fun getLocalPort(key: String, default: Int): Int {
        return parsePort(configurationStore.getString(key), default + userIndex)
    }

    private fun saveLocalPort(key: String, value: Int) {
        configurationStore.putString(key, "$value")
    }

    var ipv6Mode by configurationStore.stringToInt(Key.IPV6_MODE) { IPv6Mode.DISABLE }

    var meteredNetwork by configurationStore.boolean(Key.METERED_NETWORK)
    var proxyApps by configurationStore.boolean(Key.PROXY_APPS)
    var bypass by configurationStore.boolean(Key.BYPASS_MODE) { true }
    var individual by configurationStore.string(Key.INDIVIDUAL)
    var showDirectSpeed by configurationStore.boolean(Key.SHOW_DIRECT_SPEED) { true }

    val persistAcrossReboot by configurationStore.boolean(Key.PERSIST_ACROSS_REBOOT) { false }

    var appendHttpProxy by configurationStore.boolean(Key.APPEND_HTTP_PROXY)
    var connectionTestURL by configurationStore.string(Key.CONNECTION_TEST_URL) { CONNECTION_TEST_URL }
    var connectionTestConcurrent by configurationStore.int("connectionTestConcurrent") { 5 }
    var alwaysShowAddress by configurationStore.boolean(Key.ALWAYS_SHOW_ADDRESS)

    var tunImplementation by configurationStore.stringToInt(Key.TUN_IMPLEMENTATION) { TunImplementation.GVISOR }
    var profileTrafficStatistics by configurationStore.boolean(Key.PROFILE_TRAFFIC_STATISTICS) { true }

    var yacdURL by configurationStore.string("yacdURL") { "http://127.0.0.1:9090/ui" }

    // protocol

    var globalAllowInsecure by configurationStore.boolean(Key.GLOBAL_ALLOW_INSECURE) { false }

    // Hysteria2 增强（2026-11-10，规划第 13 项）：全局默认值，全部默认未设。
    var hy2BbrProfile by configurationStore.string(Key.HY2_BBR_PROFILE) { "" }
    var hy2DisableChromeParrot by configurationStore.boolean(Key.HY2_DISABLE_CHROME_PARROT) { false }
    var hy2HopIntervalMax by configurationStore.string(Key.HY2_HOP_INTERVAL_MAX) { "" }
    var hy2BrutalDebug by configurationStore.boolean(Key.HY2_BRUTAL_DEBUG) { false }

    // AnyTLS 会话复用（2026-11-10，规划第 14 项）：空 = 未设，走核心默认。
    var anytlsIdleCheckInterval by configurationStore.string(Key.ANYTLS_IDLE_CHECK_INTERVAL) { "" }
    var anytlsIdleTimeout by configurationStore.string(Key.ANYTLS_IDLE_TIMEOUT) { "" }
    var anytlsMinIdleSession by configurationStore.string(Key.ANYTLS_MIN_IDLE_SESSION) { "" }

    // Optimistic DNS（2026-11-10，规划第 14 项）：默认关；超时空 = 核心默认 72 小时。
    var dnsOptimisticEnabled by configurationStore.boolean(Key.DNS_OPTIMISTIC_ENABLED) { false }
    var dnsOptimisticTimeout by configurationStore.string(Key.DNS_OPTIMISTIC_TIMEOUT) { "" }

    // old cache, DO NOT ADD

    var dirty by profileCacheStore.boolean(Key.PROFILE_DIRTY)
    var editingId by profileCacheStore.long(Key.PROFILE_ID)
    var editingGroup by profileCacheStore.long(Key.PROFILE_GROUP)
    var profileName by profileCacheStore.string(Key.PROFILE_NAME)
    var serverAddress by profileCacheStore.string(Key.SERVER_ADDRESS)
    var serverPort by profileCacheStore.stringToInt(Key.SERVER_PORT)
    var serverPorts by profileCacheStore.string("serverPorts")
    var serverUsername by profileCacheStore.string(Key.SERVER_USERNAME)
    var serverPassword by profileCacheStore.string(Key.SERVER_PASSWORD)
    var serverPassword1 by profileCacheStore.string(Key.SERVER_PASSWORD1)
    var serverMethod by profileCacheStore.string(Key.SERVER_METHOD)

    var sharedStorage by profileCacheStore.string("sharedStorage")

    var serverProtocol by profileCacheStore.string(Key.SERVER_PROTOCOL)
    var serverObfs by profileCacheStore.string(Key.SERVER_OBFS)

    var serverNetwork by profileCacheStore.string(Key.SERVER_NETWORK)
    var serverHost by profileCacheStore.string(Key.SERVER_HOST)
    var serverPath by profileCacheStore.string(Key.SERVER_PATH)
    var serverSNI by profileCacheStore.string(Key.SERVER_SNI)
    var serverEncryption by profileCacheStore.string(Key.SERVER_ENCRYPTION)
    var serverALPN by profileCacheStore.string(Key.SERVER_ALPN)
    var serverCertificates by profileCacheStore.string(Key.SERVER_CERTIFICATES)
    var serverMTU by profileCacheStore.stringToInt(Key.SERVER_MTU)
    var serverHeaders by profileCacheStore.string(Key.SERVER_HEADERS)
    var serverAllowInsecure by profileCacheStore.boolean(Key.SERVER_ALLOW_INSECURE)

    var serverAuthType by profileCacheStore.stringToInt(Key.SERVER_AUTH_TYPE)
    var serverUploadSpeed by profileCacheStore.stringToInt(Key.SERVER_UPLOAD_SPEED)
    var serverDownloadSpeed by profileCacheStore.stringToInt(Key.SERVER_DOWNLOAD_SPEED)
    var serverStreamReceiveWindow by profileCacheStore.stringToIntIfExists(Key.SERVER_STREAM_RECEIVE_WINDOW)
    var serverConnectionReceiveWindow by profileCacheStore.stringToIntIfExists(Key.SERVER_CONNECTION_RECEIVE_WINDOW)
    var serverDisableMtuDiscovery by profileCacheStore.boolean(Key.SERVER_DISABLE_MTU_DISCOVERY)
    var serverHopInterval by profileCacheStore.stringToInt(Key.SERVER_HOP_INTERVAL) { 10 }

    var protocolVersion by profileCacheStore.stringToInt(Key.PROTOCOL_VERSION) { 2 } // default is SOCKS5

    var serverProtocolInt by profileCacheStore.stringToInt(Key.SERVER_PROTOCOL)
    var serverPrivateKey by profileCacheStore.string(Key.SERVER_PRIVATE_KEY)
    var serverInsecureConcurrency by profileCacheStore.stringToInt(Key.SERVER_INSECURE_CONCURRENCY)

    var serverUDPRelayMode by profileCacheStore.string(Key.SERVER_UDP_RELAY_MODE)
    var serverCongestionController by profileCacheStore.string(Key.SERVER_CONGESTION_CONTROLLER)
    var serverDisableSNI by profileCacheStore.boolean(Key.SERVER_DISABLE_SNI)
    var serverReduceRTT by profileCacheStore.boolean(Key.SERVER_REDUCE_RTT)

    var routeName by profileCacheStore.string(Key.ROUTE_NAME)
    var routeDomain by profileCacheStore.string(Key.ROUTE_DOMAIN)
    var routeIP by profileCacheStore.string(Key.ROUTE_IP)
    var routePort by profileCacheStore.string(Key.ROUTE_PORT)
    var routeSourcePort by profileCacheStore.string(Key.ROUTE_SOURCE_PORT)
    var routeNetwork by profileCacheStore.string(Key.ROUTE_NETWORK)
    var routeSource by profileCacheStore.string(Key.ROUTE_SOURCE)
    var routeProtocol by profileCacheStore.string(Key.ROUTE_PROTOCOL)
    var routeOutbound by profileCacheStore.stringToInt(Key.ROUTE_OUTBOUND)
    var routeOutboundRule by profileCacheStore.long(Key.ROUTE_OUTBOUND + "Long")
    var routePackages by profileCacheStore.string(Key.ROUTE_PACKAGES)

    var frontProxy by profileCacheStore.long(Key.GROUP_FRONT_PROXY + "Long")
    var landingProxy by profileCacheStore.long(Key.GROUP_LANDING_PROXY + "Long")
    var frontProxyTmp by profileCacheStore.stringToInt(Key.GROUP_FRONT_PROXY)
    var landingProxyTmp by profileCacheStore.stringToInt(Key.GROUP_LANDING_PROXY)

    var serverConfig by profileCacheStore.string(Key.SERVER_CONFIG)
    var serverCustom by profileCacheStore.string(Key.SERVER_CUSTOM)
    var serverCustomOutbound by profileCacheStore.string(Key.SERVER_CUSTOM_OUTBOUND)

    var groupName by profileCacheStore.string(Key.GROUP_NAME)
    var groupType by profileCacheStore.stringToInt(Key.GROUP_TYPE)
    var groupOrder by profileCacheStore.stringToInt(Key.GROUP_ORDER)
    var groupIsSelector by profileCacheStore.boolean(Key.GROUP_IS_SELECTOR)

    var subscriptionLink by profileCacheStore.string(Key.SUBSCRIPTION_LINK)
    var subscriptionForceResolve by profileCacheStore.boolean(Key.SUBSCRIPTION_FORCE_RESOLVE)
    var subscriptionDeduplication by profileCacheStore.boolean(Key.SUBSCRIPTION_DEDUPLICATION)
    var subscriptionUpdateWhenConnectedOnly by profileCacheStore.boolean(Key.SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY)
    var subscriptionUserAgent by profileCacheStore.string(Key.SUBSCRIPTION_USER_AGENT)
    var subscriptionAutoUpdate by profileCacheStore.boolean(Key.SUBSCRIPTION_AUTO_UPDATE)
    var subscriptionAutoUpdateDelay by profileCacheStore.stringToInt(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY) { 1440 }
    // 2026-11-07：与 group_preferences.xml 默认值 1440 及 SubscriptionBean
    // （autoUpdateDelay == null 时补 1440）对齐，此前代码默认 360 是错的。

    // 2026-11-14，0.2.24 -19，M-12：从 profileCacheStore（TempDatabase，
    // 内存库，进程重启即清零）移到 configurationStore（PublicDatabase，
    // 落盘）。之前用户手动删光规则后，下次进程启动 getRules() 见 flag
    // 已清零，又把 5 条默认规则悄悄种回来，无法长期保持“零规则”。
    // 注意：升级后首次若规则表为空，会按旧逻辑补种一次并持久置 true，
    // 此后删光不再重种；路由页“重置”仍显式置 false 触发补种。
    var rulesFirstCreate by configurationStore.boolean("rulesFirstCreate")

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
    }
}