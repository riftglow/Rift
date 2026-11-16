package light.rift.fmt

import android.widget.Toast
import light.rift.*
import light.rift.bg.VpnService
import light.rift.database.DataStore
import light.rift.database.ProxyEntity
import light.rift.database.ProxyEntity.Companion.TYPE_CONFIG
import light.rift.database.RiftDatabase
import light.rift.fmt.ConfigBuildResult.IndexEntity
import light.rift.fmt.hysteria.HysteriaBean
import light.rift.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import light.rift.fmt.internal.ChainBean
import light.rift.fmt.shadowsocks.ShadowsocksBean
import light.rift.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import light.rift.fmt.socks.SOCKSBean
import light.rift.fmt.socks.buildSingBoxOutboundSocksBean
import light.rift.fmt.ssh.SSHBean
import light.rift.fmt.ssh.buildSingBoxOutboundSSHBean
import light.rift.fmt.tuic.TuicBean
import light.rift.fmt.tuic.buildSingBoxOutboundTuicBean
import light.rift.fmt.v2ray.StandardV2RayBean
import light.rift.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import light.rift.fmt.wireguard.WireGuardBean
import light.rift.ktx.Logs
import light.rift.ktx.isIpAddress
import light.rift.ktx.mkPort
import light.rift.utils.PackageCache
import light.rift.core.*
import light.rift.core.SingBoxOptions.*
import light.rift.core.proxy.anytls.AnyTLSBean
import light.rift.core.proxy.findField
import light.rift.core.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import light.rift.core.proxy.config.ConfigBean
import light.rift.core.proxy.shadowtls.ShadowTLSBean
import light.rift.core.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import light.rift.core.utils.JavaUtil.gson
import light.rift.core.utils.Util
import light.rift.core.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"

const val LOCALHOST = "127.0.0.1"

// Batch 2 item 5: a rule may point at a whole proxy group instead of one
// profile. Encoding: outbound = GROUP_OUTBOUND_BASE - groupId (always
// <= -10000, so it never collides with 0/-1/-2 or a profile id). No Room
// schema change is needed: the column is just a Long.
const val GROUP_OUTBOUND_BASE = -10000L

fun groupOutboundId(groupId: Long): Long = GROUP_OUTBOUND_BASE - groupId

fun outboundGroupId(outbound: Long): Long? =
    if (outbound <= GROUP_OUTBOUND_BASE) GROUP_OUTBOUND_BASE - outbound else null

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false,
    testCachePath: String? = null
): ConfigBuildResult {

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id, //
                mapOf(TAG_PROXY to listOf(proxy)), //
                mapOf(proxy.id to TAG_PROXY), //
                -1L
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val selectorNames = ArrayList<String>()
    val group = RiftDatabase.groupDao.getById(proxy.groupId)

    // 链式引用环检测：visited 只记当前递归路径上的链（菱形共用成员仍可展开两次），
    // 深度上限防无环但极深的嵌套同样栈溢出。成环/超深的成员直接跳过并记日志，
    // 不让一次手误把整个 VPN 启动拖垮。
    // 注意：这里是 buildConfig 内的局部函数，不能加 private 修饰符；
    // 局部函数只能调用之前已声明的同名函数，所以带参的 worker 必须写在前面。
    fun ProxyEntity.resolveChainInternal(
        visited: MutableSet<Long>,
        depth: Int
    ): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (bean is ChainBean) {
            if (depth > 64 || !visited.add(id)) {
                Logs.w("Chain loop or too deep at profile $id, skipped")
                return mutableListOf()
            }
            try {
                val beans = RiftDatabase.proxyDao.getEntities(bean.proxies)
                val beansMap = beans.associateBy { it.id }
                val beanList = ArrayList<ProxyEntity>()
                for (proxyId in bean.proxies) {
                    val item = beansMap[proxyId] ?: continue
                    beanList.addAll(item.resolveChainInternal(visited, depth + 1))
                }
                return beanList.asReversed()
            } finally {
                visited.remove(id)
            }
        }
        return mutableListOf(this)
    }

    fun ProxyEntity.resolveChainInternal(): MutableList<ProxyEntity> {
        return resolveChainInternal(HashSet<Long>(), 0)
    }

    fun selectorName(name_: String): String {
        var name = name_
        var count = 0
        while (selectorNames.contains(name)) {
            count++
            name = "$name_-$count"
        }
        selectorNames.add(name)
        return name
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        val thisGroup = RiftDatabase.groupDao.getById(groupId)
        val frontProxy = thisGroup?.frontProxy?.let { RiftDatabase.proxyDao.getById(it) }
        val landingProxy = thisGroup?.landingProxy?.let { RiftDatabase.proxyDao.getById(it) }
        val list = resolveChainInternal()
        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }

    val extraRules = if (forTest) listOf() else RiftDatabase.rulesDao.enabledRules()
    // 分流模式（2026-11-10）：全局/直连模式下用户规则不参与生成，
    // 流量直接落到 route.final_；规则模式下与原来完全一致。
    val rulesToApply = if (DataStore.routeMode == 0) extraRules else emptyList()
    val extraProxies =
        if (forTest) mapOf() else RiftDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    val needSniff = DataStore.trafficSniffing > 0
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    return MyOptions().apply {
        // O4: persist FakeIP + DNS cache for the main instance only.
        // forTest instances must never get cache_file: they used to share the
        // default cache.db (bbolt) with the main instance and crashed it.
        // Path omitted on purpose: core defaults to cache.db under the
        // process working dir (no_backup), private to the app.
        if (!forTest) experimental = ExperimentalOptions().apply {
            cache_file = CacheFile().apply {
                enabled = true
                store_fakeip = true
                store_dns = true
            }
            if (DataStore.enableClashAPI) clash_api = ClashAPIOptions().apply {
                external_controller = "127.0.0.1:9090"
                external_ui = "../files/yacd"
            }
        } else {
            // 测速临时核心（0.2.29 -14）：单独的缓存文件。
            // sing-box 只要传了 PlatformLogWriter 就强制建 cache-file 服务，
            // 不给路径就默认 cache.db；主实例连着 VPN 时占着它的 bbolt 锁，
            // 临时核心 10 次重试都拿不到就报 initialize cache-file: timeout。
            // 各用各的文件就不再抢；文件由 TestInstance.close() 删除。
            experimental = ExperimentalOptions().apply {
                cache_file = CacheFile().apply {
                    path = testCachePath
                }
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            independent_cache = true
            // Optimistic DNS（2026-11-10，规划第 14 项）：默认关；开后返回过期
            // 缓存并后台刷新。超时未填则走核心默认 72 小时。
            if (DataStore.dnsOptimisticEnabled) {
                optimistic = OptimisticDNSOptions().apply {
                    enabled = true
                    DataStore.dnsOptimisticTimeout.toIntOrNull()?.takeIf { it > 0 }?.let {
                        timeout = "${it}s"
                    }
                }
            }
        }

        fun autoDnsDomainStrategy(s: String, isProxied: Boolean = false): String? {
            if (ipv6Mode == IPv6Mode.DISABLE) {
                return "ipv4_only"
            }
            if (ipv6Mode == IPv6Mode.ONLY) {
                return "ipv6_only"
            }
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> if (isProxied) "prefer_ipv4" else "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        // sing-box 1.14+ DNS server format: type + server + server_port (+ path),
        // the old address string form ("https://...", "local", "rcode://...") is removed.
        // 1.14+ 新 DNS 服务器格式：type + server + server_port（+ path），
        // 旧的 address 字符串形式在 1.14 已移除。
        fun buildDnsServer(
            address: String,
            tag: String,
            detour: String? = null,
            domainResolver: String? = null,
            domainStrategy: String? = null
        ): DNSServerOptions {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) {
                throw Exception("Invalid DNS server address: empty (tag=$tag)")
            }
            // 非法地址/端口不再静默猜测：以前 URI 解析失败就靠字符串
            // 截取补 host、端口写错就默认 53/443/853，用户只会看到
            // 后面 DNS 不通，却不知道是地址本身写错了。
            fun requireHost(uri: java.net.URI?, fallback: String): String {
                val host = uri?.host ?: fallback
                if (host.isBlank()) {
                    throw Exception("Invalid DNS server address: $address (tag=$tag)")
                }
                return host
            }
            fun requirePort(uri: java.net.URI?, defaultPort: Int): Int {
                if (uri == null || uri.port == -1) return defaultPort
                if (uri.port !in 1..65535) {
                    throw Exception("Invalid DNS server port in address: $address (tag=$tag)")
                }
                return uri.port
            }
            if (trimmed == "local" || trimmed == "hosts") {
                return DNSServerOptions().apply {
                    this.type = "local"
                    this.tag = tag
                    this.detour = detour
                }
            }
            if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
                val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
                val host = requireHost(uri, trimmed.removePrefix("https://").removePrefix("http://").substringBefore("/").substringBefore(":"))
                val port = requirePort(uri, 443)
                val path = if (uri != null && !uri.rawPath.isNullOrEmpty()) uri.rawPath else "/dns-query"
                return DNSServerOptions().apply {
                    this.type = "https"
                    this.tag = tag
                    this.server = host
                    this.server_port = port
                    this.path = path
                    this.detour = detour
                    if (!host.isIpAddress()) {
                        this.domain_resolver = domainResolver
                        this.domain_strategy = domainStrategy
                    }
                }
            }
            if (trimmed.startsWith("h3://", ignoreCase = true)) {
                val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
                val host = requireHost(uri, trimmed.removePrefix("h3://").substringBefore("/").substringBefore(":"))
                val port = requirePort(uri, 443)
                val path = if (uri != null && !uri.rawPath.isNullOrEmpty()) uri.rawPath else "/dns-query"
                return DNSServerOptions().apply {
                    this.type = "h3"
                    this.tag = tag
                    this.server = host
                    this.server_port = port
                    this.path = path
                    this.detour = detour
                    if (!host.isIpAddress()) {
                        this.domain_resolver = domainResolver
                        this.domain_strategy = domainStrategy
                    }
                }
            }
            if (trimmed.startsWith("tls://", ignoreCase = true)) {
                val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
                val host = requireHost(uri, trimmed.removePrefix("tls://").substringBefore("/").substringBefore(":"))
                val port = requirePort(uri, 853)
                return DNSServerOptions().apply {
                    this.type = "tls"
                    this.tag = tag
                    this.server = host
                    this.server_port = port
                    this.detour = detour
                    if (!host.isIpAddress()) {
                        this.domain_resolver = domainResolver
                        this.domain_strategy = domainStrategy
                    }
                }
            }
            if (trimmed.startsWith("quic://", ignoreCase = true)) {
                val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
                val host = requireHost(uri, trimmed.removePrefix("quic://").substringBefore("/").substringBefore(":"))
                val port = requirePort(uri, 853)
                return DNSServerOptions().apply {
                    this.type = "quic"
                    this.tag = tag
                    this.server = host
                    this.server_port = port
                    this.detour = detour
                    if (!host.isIpAddress()) {
                        this.domain_resolver = domainResolver
                        this.domain_strategy = domainStrategy
                    }
                }
            }
            if (trimmed.startsWith("tcp://", ignoreCase = true)) {
                val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
                val host = requireHost(uri, trimmed.removePrefix("tcp://").substringBefore("/").substringBefore(":"))
                val port = requirePort(uri, 53)
                return DNSServerOptions().apply {
                    this.type = "tcp"
                    this.tag = tag
                    this.server = host
                    this.server_port = port
                    this.detour = detour
                    if (!host.isIpAddress()) {
                        this.domain_resolver = domainResolver
                        this.domain_strategy = domainStrategy
                    }
                }
            }
            val raw = if (trimmed.startsWith("udp://", ignoreCase = true)) trimmed.removePrefix("udp://") else trimmed
            val host: String
            val port: Int
            if (raw.startsWith("[") && raw.contains("]")) {
                host = raw.substringAfter("[").substringBefore("]")
                val after = raw.substringAfter("]")
                port = if (after.startsWith(":") && after.length > 1) {
                    after.substring(1).toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: throw Exception("Invalid DNS server port in address: $address (tag=$tag)")
                } else 53
            } else if (raw.contains(":") && raw.indexOf(":") == raw.lastIndexOf(":")) {
                host = raw.substringBefore(":")
                port = raw.substringAfter(":").toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: throw Exception("Invalid DNS server port in address: $address (tag=$tag)")
            } else if (raw.contains(":")) {
                throw Exception("Invalid DNS server address: $address (tag=$tag)")
            } else {
                host = raw
                port = 53
            }
            if (host.isBlank()) {
                throw Exception("Invalid DNS server address: $address (tag=$tag)")
            }
            return DNSServerOptions().apply {
                this.type = "udp"
                this.tag = tag
                this.server = host
                this.server_port = port
                this.detour = detour
                if (!host.isIpAddress()) {
                    this.domain_resolver = domainResolver
                    this.domain_strategy = domainStrategy
                }
            }
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN) inbounds?.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = "tun-in"
                // 1.15 废弃 stack 字段，底层统一用 sing-tun，不再指定 gvisor/system/mixed。
                // 1.13 起入站的 sniff/domain_strategy 属于 legacy 字段，非空即报错，
                // 改由下面的路由规则动作 sniff/resolve 实现；故这里不再设置。
                // 注意：Android 走 VpnService 传 fd，不照搬 OwnBox 的 auto_route=true。
                mtu = DataStore.mtu
                // 1.12 起 inet4_address/inet6_address 合并为 address。
                address = when (ipv6Mode) {
                    IPv6Mode.DISABLE -> mutableListOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                    IPv6Mode.ONLY -> mutableListOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    else -> mutableListOf(
                        VpnService.PRIVATE_VLAN4_CLIENT + "/28",
                        VpnService.PRIVATE_VLAN6_CLIENT + "/126"
                    )
                }
            })
            inbounds?.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
            })
        }

        outbounds = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            rules = mutableListOf()
            rule_set = mutableListOf()
            // S-C2：route.final_ 故意不赋值。sing-box 未设 final 时默认用
            // 配置里的第一个出站（box.go 把 route.Final 传给 outbound
            // manager，manager.go:185：defaultTag 为空时首个注册的出站即
            // 默认出站）；本配置首个出站恒为主代理（selector 模式是 proxy
            // selector，非 selector 模式是 tag 为 "proxy" 的主链出站），
            // 与显式 final_=proxy 等价，故不硬赋值。
            // 分流模式（2026-11-10，DataStore.routeMode）：0=规则（默认，
            // 上面这条仍成立）；1=全局：跳过全部用户规则，final=主代理；
            // 2=直连：跳过全部用户规则，final=直连。全局/直连下规则不
            // 生成，DNS 规则也随之为空，FakeDNS 兜底。
            when (DataStore.routeMode) {
                1 -> final_ = TAG_PROXY
                2 -> final_ = TAG_DIRECT
            }
            // 内核配置三件套（2026-11-06），两个路由级选项走 _hack_config_map，
            // 因为生成的 RouteOptions.java 里没有这两个 1.15 字段：
            // 1. 并行拨号回退延迟 300ms（核心默认）→ 100ms：双网并行拨号时
            //    备用网卡更早接手；只影响双网并行拨号，单网无感。
            _hack_config_map["default_fallback_delay"] = "100ms"
            // 3. 节点域名解析改走直连 DNS：出站拨号解析节点域名时直接用
            //    dns-direct 解析，不再只靠 DNS 规则匹配兜底；dns-direct 本身
            //    走直连、其解析器是 dns-local，不成环；冷启动不用先等代理通。
            _hack_config_map["default_domain_resolver"] = mapOf("server" to "dns-direct")
        }

        // returns outbound tag
        fun buildChain(
            chainId: Long, entity: ProxyEntity
        ): String {
            val profileList = entity.resolveChain()
            // 链式流量只记在链本身（entity）名下，不记到各成员名下。
            // 之前把 profileList 全放进 trafficMap，TrafficLooper 会把
            // 同一份 proxy 出站流量加到链 + 每个成员头上（N 个成员就
            // 算 N+1 遍，节点列表里三节点下行同为 322MB 就是这么来的）。
            // 成员只在被单独选中直连时才由自己的 buildChain 记流量。
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            // S-C4：不能用 lateinit——只有 canMapping() 为真的外部成员才会
            // 建 dokodemo-door 映射入站并赋值；Internal Bean 与
            // Hysteria FAKE-TCP（canMapping()==false）不建入站，未初始化
            // 就读会抛 UninitializedPropertyAccessException，旧值残留则会
            // 把流量错指到上一个成员的入站。改可空，配合每轮先置 null。
            var pastInboundTag: String? = null
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    bypassDNSBeans += proxyEntity.requireBean()
                }

                // last profile set as "proxy"
                if (chainId == 0L && index == 0) {
                    tagOut = TAG_PROXY
                }

                // selector human readable name
                if (buildSelector && index == 0) {
                    tagOut = selectorName(bean.displayName())
                }


                // chain rules
                if (index > 0) {
                    // chain route/proxy rules
                    if (pastEntity!!.needExternal()) {
                        // S-C4：pastInboundTag 只在上一成员建了 dokodemo-door
                        // 映射入站时才有值（见下文映射块）。Internal Bean 与
                        // Hysteria FAKE-TCP 的 canMapping() 为 false，不建映射入站，
                        // 此时不得引用旧值：该外部进程直接拨真实服务器地址
                        // （finalAddress 未改写），链上它之后的跳段不被经过。
                        // 无映射时不加这条规则，只记日志——加一条指向不存在
                        // 入站的规则同样是错的。
                        pastInboundTag?.let { tag ->
                            route?.rules?.add(Rule_DefaultOptions().apply {
                                inbound = mutableListOf(tag)
                                outbound = tagOut
                            })
                        } ?: Logs.w(
                            "Chain member '${pastEntity!!.requireBean().displayName()}' " +
                                "is external without a mapping inbound; " +
                                "its traffic dials the real server directly, " +
                                "later chain hops are skipped"
                        )
                    } else {
                        // N1：detour 是 DialerOptions 的字段——selector /
                        // urltest / block 与 CustomSingBoxOption（用户任意
                        // JSON）都没有它。往它们顶层注入 detour，核心会以
                        // DisallowUnknownFields 拒掉整个配置、无法启动，
                        // 与 S-C3 同类问题。按 S-C3 的办法探查生成类是否
                        // 真有 detour 字段再注入；不接受时跳过并记日志警告。
                        // 注意不能静默跳过：跳过意味着上一跳改直拨、链路
                        // 走错路，警告必须留下。
                        val acceptsDetour = findField(pastOutbound.javaClass, "detour") != null
                        if (acceptsDetour) {
                            pastOutbound._hack_config_map["detour"] = tagOut
                        } else {
                            Logs.w(
                                "Chain member '${pastEntity!!.requireBean().displayName()}' " +
                                    "does not accept a detour; skipping detour to '$tagOut', " +
                                    "the previous hop will dial directly and the rest of " +
                                    "the chain is bypassed"
                            )
                        }
                    }
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // now tagOut is determined
                if (needGlobal) {
                    globalOutbounds[proxyEntity.id]?.let {
                        if (index == 0) chainTagOut = it // single, duplicate chain
                        return@forEachIndexed
                    }
                    globalOutbounds[proxyEntity.id] = tagOut
                }

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                    }
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(bean.config)

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        // WireGuard was removed from the sing-box 1.15
                        // core: the core only has a stub that fails box
                        // start. Fail here, with a message naming the
                        // profile, instead of building a stub outbound
                        // that kills the whole service at start. Import
                        // paths already filter WireGuard out; this is
                        // the guard for old profiles and chain members.
                        is WireGuardBean ->
                            throw IllegalStateException(
                                "WireGuard is removed in sing-box 1.15 core, " +
                                    "delete or replace profile '${bean.displayName()}'"
                            )

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        else -> throw IllegalStateException("can't reach")
                    }

                    // internal mux
                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled == true) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = findField(bean.javaClass, "sUoT")?.get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // domain_strategy
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    // S-C3：domain_strategy / tcp_keep_alive 只有带内嵌
                    // DialerOptions 的出站才接受。CustomSingBoxOption 包的是
                    // 用户任意 JSON（可能是 selector / urltest / direct……），
                    // 而 selector / urltest 没有 DialerOptions；往它们顶层
                    // 注入这两个键，核心会以 DisallowUnknownFields 拒掉整个
                    // 配置、无法启动（option/outbound.go 经 badjson 以
                    // UnmarshalContextDisallowUnknownFields 解析各类型出站）。
                    // 改为探查生成类是否真有 domain_strategy 字段再注入，
                    // 不再按类型白名单写死；tag 注入不受影响（所有出站都有）。
                    // 注意：外部 Socks 出站有 DialerOptions，仍正常注入。
                    val hasDialerOptions = findField(javaClass, "domain_strategy") != null
                    if (hasDialerOptions) {
                        _hack_config_map["domain_strategy"] =
                            if (forTest) "" else defaultServerDomainStrategy

                        // 内核配置三件套（2026-11-06）：2. 出站 TCP 空闲 45 秒发一次
                        // KeepAlive 探测（核心默认 5 分钟），移动网 NAT 超时短，
                        // 切网/亮屏后节点长连接不易被误断；只对节点出站生效，
                        // 直连/绕过出站不动。探测间隔沿用核心默认 75 秒。
                        _hack_config_map["tcp_keep_alive"] = "45s"
                    }

                    _hack_config_map["tag"] = tagOut

                    _hack_custom_config = bean.customOutboundJson
                }

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                // 映射入站已随外挂删除（2026-11-16），不再新建；
                // pastInboundTag 恒为 null，下一跳不会拿到旧值。
                pastInboundTag = null

                outbounds?.add(currentOutbound)
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // build outbounds
        if (buildSelector) {
            val list = group.id.let { RiftDatabase.proxyDao.getByGroup(it) }
            list.forEach {
                tagMap[it.id] = buildChain(it.id, it)
            }
            outbounds?.add(0, Outbound_SelectorOptions().apply {
                type = "selector"
                tag = TAG_PROXY
                default_ = tagMap[proxy.id]
                outbounds = tagMap.values.toMutableList()
            })
        } else {
            // S-C6：非 selector 模式也要把主链 tag 登记进 tagMap。
            // 否则下面组选择器的 default_ 回落（tagMap[proxy.id] 为空 →
            // memberTags[0]），且主节点作为组成员时会被重复 build 一次。
            tagMap[proxy.id] = buildChain(0, proxy)
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildChain(key, p)
        }
        // Batch 2 item 5: build one outbound per group referenced by a rule.
        // Members are built exactly like route-item profiles (guarded by tagMap
        // so a profile is never built twice). Batch 3 (2026-11-17): the outbound
        // type follows the group's outbound mode — "selector" (manual, default)
        // or "urltest" (core auto-picks the lowest-latency member). Manual mode
        // defaults to the group's designated primary when it is a member, else
        // the current main profile when it is a member, else the first member.
        // No per-group switch UI at runtime yet: switching a group selector at
        // runtime is a follow-up, the rule itself already tracks group membership.
        val groupSelectorTags = HashMap<Long, String>()
        rulesToApply.mapNotNull { outboundGroupId(it.outbound) }.toSet().forEach { groupId ->
            val ruleGroup = RiftDatabase.groupDao.getById(groupId) ?: return@forEach
            val memberTags = ArrayList<String>()
            RiftDatabase.proxyDao.getByGroup(groupId).forEach { member ->
                val tag = tagMap[member.id] ?: buildChain(member.id, member).also {
                    tagMap[member.id] = it
                }
                memberTags.add(tag)
            }
            if (memberTags.isEmpty()) return@forEach
            val selectorTag = selectorName("group-" + ruleGroup.displayName())
            groupSelectorTags[groupId] = selectorTag
            if (DataStore.groupOutboundMode(groupId) == "urltest") {
                outbounds?.add(Outbound_URLTestOptions().apply {
                    type = "urltest"
                    tag = selectorTag
                    outbounds = memberTags
                    url = DataStore.connectionTestURL
                })
            } else {
                outbounds?.add(Outbound_SelectorOptions().apply {
                    type = "selector"
                    tag = selectorTag
                    default_ = tagMap[DataStore.groupPrimaryProfile(groupId)]
                        ?.takeIf { memberTags.contains(it) }
                        ?: tagMap[proxy.id]?.takeIf { memberTags.contains(it) }
                        ?: memberTags[0]
                    outbounds = memberTags
                })
            }
        }

        // apply user rules
        for (rule in rulesToApply) {
            if (rule.packages.isNotEmpty()) {
                PackageCache.awaitLoadSync()
            }
            val uidList = rule.packages.map {
                if (!isVPN) {
                    Toast.makeText(
                        RiftApp.application,
                        RiftApp.application.getString(R.string.route_need_vpn, rule.displayName()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                PackageCache[it]?.takeIf { uid -> uid >= 1000 }
            }.toHashSet().filterNotNull()
            val ruleSets = mutableListOf<RuleSet>()

            val ruleObj = Rule_DefaultOptions().apply {
                // App rules: emit package_name, not just user_id. The
                // user_id (UID) path alone did not match LINE traffic on
                // device (app-only rule fell through to final, while the
                // same rule with a domain matched), so the Android-native
                // package_name item is the one that must carry the match.
                // Keep user_id too: when the core does resolve a process,
                // both items agree; when it does not, neither matches, so
                // keeping it costs nothing and preserves old behaviour.
                if (rule.packages.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                    package_name = rule.packages.toMutableList()
                    if (uidList.isNotEmpty()) {
                        user_id = uidList.toMutableList()
                    }
                }
                var domainList: List<String>? = null
                if (rule.domains.isNotBlank()) {
                    domainList = rule.domains.listByLineOrComma()
                    makeSingBoxRule(domainList, false)
                }
                if (rule.ip.isNotBlank()) {
                    makeSingBoxRule(rule.ip.listByLineOrComma(), true)
                }

                val rs = rule_set
                if (rs != null) generateRuleSet(rs, ruleSets)

                if (rule.port.isNotBlank()) {
                    port = mutableListOf<Int>()
                    port_range = mutableListOf<String>()
                    rule.port.listByLineOrComma().map {
                        if (it.contains(":")) {
                            port_range?.add(it)
                        } else {
                            it.toIntOrNull()?.apply { port?.add(this) }
                        }
                    }
                }
                if (rule.sourcePort.isNotBlank()) {
                    source_port = mutableListOf<Int>()
                    source_port_range = mutableListOf<String>()
                    rule.sourcePort.listByLineOrComma().map {
                        if (it.contains(":")) {
                            source_port_range?.add(it)
                        } else {
                            it.toIntOrNull()?.apply { source_port?.add(this) }
                        }
                    }
                }
                if (rule.network.isNotBlank()) {
                    network = mutableListOf(rule.network)
                }
                if (rule.source.isNotBlank()) {
                    source_ip_cidr = rule.source.listByLineOrComma().toMutableList()
                }
                if (rule.protocol.isNotBlank()) {
                    protocol = rule.protocol.listByLineOrComma().toMutableList()
                }

                fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                    return DNSRule_DefaultOptions().apply {
                        if (rule.packages.isNotEmpty()) {
                            package_name = rule.packages.toMutableList()
                        }
                        if (uidList.isNotEmpty()) user_id = uidList.toMutableList()
                        domainList?.let { makeSingBoxRule(it) }
                    }
                }

                // Batch 2 item 5: a group rule resolves DNS like a proxy
                // rule (remote), it is not a bypass/block rule.
                val ruleGroupId = outboundGroupId(rule.outbound)
                when (rule.outbound) {
                    -1L -> {
                        userDNSRuleList += makeDnsRuleObj().apply { server = "dns-direct" }
                    }

                    0L -> {
                        if (useFakeDns) userDNSRuleList += makeDnsRuleObj().apply {
                            server = "dns-fake"
                            inbound = mutableListOf("tun-in")
                        }
                        userDNSRuleList += makeDnsRuleObj().apply {
                            server = "dns-remote"
                        }
                    }

                    -2L -> {
                        userDNSRuleList += makeDnsRuleObj().apply {
                            server = "dns-block"
                            disable_cache = true
                        }
                    }

                    else -> {
                        if (ruleGroupId != null) {
                            if (useFakeDns) userDNSRuleList += makeDnsRuleObj().apply {
                                server = "dns-fake"
                                inbound = mutableListOf("tun-in")
                            }
                            userDNSRuleList += makeDnsRuleObj().apply {
                                server = "dns-remote"
                            }
                        }
                    }
                }

                outbound = when (val outId = rule.outbound) {
                    0L -> TAG_PROXY
                    -1L -> TAG_BYPASS
                    -2L -> TAG_BLOCK
                    else -> if (outId == proxy.id) TAG_PROXY
                    else tagMap[outId]
                        ?: ruleGroupId?.let { groupSelectorTags[it] } ?: ""
                }

                _hack_custom_config = rule.config
            }

            if (!ruleObj.checkEmpty()) {
                if (ruleObj.outbound.isNullOrBlank()) {
                    Toast.makeText(
                        RiftApp.application,
                        "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // block 改用新的写法
                    if (ruleObj.outbound == TAG_BLOCK) {
                        ruleObj.outbound = null
                        ruleObj.action = "reject"
                    }
                    route?.rules?.add(ruleObj)
                    route?.rule_set?.addAll(ruleSets)
                }
            }
        }

        // 对 rule_set tag 去重
        val routeObj = route
        if (routeObj?.rule_set != null) {
            routeObj.rule_set = routeObj.rule_set?.distinctBy { it.tag }?.toMutableList()
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds?.add(Outbound().apply {
            tag = freedom
            type = "direct"
            // 1.15 的 detour 校验：detour 到一个“空”的 direct 出站会直接报错
            // （dns-direct 的 detour=direct 正撞上这一点）。给 direct/bypass 出站
            // 显式指定 network_strategy（绑定安卓默认物理网卡），使其不再是空出站，
            // detour 校验才能通过；domain_strategy 同理按 IPv6 模式设置。
            _hack_config_map["network_strategy"] = "default"
            if (ipv6Mode == IPv6Mode.DISABLE) {
                _hack_config_map["domain_strategy"] = "ipv4_only"
            } else if (ipv6Mode == IPv6Mode.ONLY) {
                _hack_config_map["domain_strategy"] = "ipv6_only"
            } else if (ipv6Mode == IPv6Mode.PREFER) {
                _hack_config_map["domain_strategy"] = "prefer_ipv6"
            }
        })

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                domainListDNSDirectForce.add("full:${serverAddr}")
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        // 1.14+ 没有 rcode 服务器了：原来 dns-block 服务器已删，屏蔽规则在下面转成 action=reject。
        dns?.servers?.add(DNSServerOptions().apply {
            type = "local"
            tag = "dns-local"
            detour = TAG_DIRECT
        })

        directDNS.firstOrNull().let {
            dns?.servers?.add(
                buildDnsServer(
                    address = it ?: throw Exception("No direct DNS, check your settings!"),
                    tag = "dns-direct",
                    detour = TAG_DIRECT,
                    domainResolver = "dns-local",
                    domainStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-direct"))
                )
            )
        }

        remoteDns.firstOrNull().let {
            // Always use direct DNS for urlTest
            if (!forTest) dns?.servers?.add(
                buildDnsServer(
                    address = it ?: throw Exception("No remote DNS, check your settings!"),
                    tag = "dns-remote",
                    detour = TAG_PROXY,
                    domainResolver = "dns-direct",
                    domainStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-remote"), isProxied = true)
                )
            )
        }

        // 直连模式下 DNS 也走直连（dns-direct），否则 DNS 仍经代理
        // 解析，与“全部直连”不一致。
        dns!!.final_ = if (forTest || DataStore.routeMode == 2) "dns-direct" else "dns-remote"

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (it.server == "dns-block") {
                    // 1.14+ 无 dns-block 服务器，屏蔽改走 reject 动作。
                    it.server = null
                    it.action = "reject"
                }
                if (!it.checkEmpty()) dns?.rules?.add(it)
            }
        }

        if (forTest) {
            dns!!.rules = mutableListOf()
        } else {
            // 1.13 起嗅探改由路由规则动作实现（入站 sniff 字段已是 legacy，非空即报错）。
            // 两条都用 add(0)，后加的排前面，故先加 resolve、再加 sniff，
            // 最终顺序是 sniff、resolve：先嗅探出域名，后面的域名分流规则才能匹配。
            if (DataStore.resolveDestination) {
                route?.rules?.add(0, Rule_DefaultOptions().apply {
                    action = "resolve"
                    strategy = genDomainStrategy(true)
                })
            }
            if (needSniff) {
                route?.rules?.add(0, Rule_DefaultOptions().apply {
                    action = "sniff"
                    timeout = "100ms"
                    sniffer = mutableListOf("tls", "http", "quic")
                })
            }
            // built-in DNS rules
            route?.rules?.add(0, Rule_DefaultOptions().apply {
                protocol = mutableListOf("dns")
                action = "hijack-dns"
            })
            route?.rules?.add(0, Rule_DefaultOptions().apply {
                port = mutableListOf(53)
                action = "hijack-dns"
            })
            if (DataStore.bypassLanInCore) {
                route?.rules?.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }
            // block mcast
            route?.rules?.add(Rule_DefaultOptions().apply {
                ip_cidr = mutableListOf("224.0.0.0/3", "ff00::/8")
                source_ip_cidr = mutableListOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // FakeDNS obj (sing-box 1.14: fakeip configured as a server in dns.servers)
            if (useFakeDns) {
                dns?.servers?.add(DNSServerOptions().apply {
                    type = "fakeip"
                    tag = "dns-fake"
                    inet4_range = "198.18.0.0/15"
                    if (ipv6Mode != IPv6Mode.DISABLE) {
                        inet6_range = "fc00::/18"
                    }
                })
                dns?.rules?.add(DNSRule_DefaultOptions().apply {
                    inbound = mutableListOf("tun-in")
                    server = "dns-fake"
                    disable_cache = true
                    query_type = when (ipv6Mode) {
                        IPv6Mode.DISABLE -> mutableListOf("A")
                        IPv6Mode.ONLY -> mutableListOf("AAAA")
                        else -> mutableListOf("A", "AAAA")
                    }
                })
            }
            // S-C5（2026-11-07 已按 sing-box 1.15.0-alpha.13 源码核实，保留）：
            // "outbound": ["any"] 只匹配 metadata.Outbound 非空的查询
            // （route/rule/rule_item_outbound.go：matchAny ⟺ Outbound != ""）。
            // 客户端经入站劫持的 DNS 查询（dns/router.go prepareExchange）
            // 从不设 Outbound，故这条规则不会盖掉下面的用户 DNS 规则；
            // 只有出站拨号解析自身域名时（各出站 DialContext 前置
            // metadata.Outbound = 本出站 tag，如 protocol/direct 与
            // protocol/shadowsocks 的 outbound.go），查询才命中本规则、
            // 强制走 dns-direct，避免经 dns-remote→proxy 绕回自身成环。
            // 该字段在 1.12 已废弃（仅记一条 deprecation 日志，不报错；
            // option/rule_dns.go 仍保留本字段），迁移指南建议的新做法
            // domain_resolver 本配置也已设置（route.default_domain_resolver
            // = dns-direct，三件套第 3 项）；本规则是其兜底，不得删。
            // 注意顺序：它插在 index 0，但随后 force-bypass 规则又插到它
            // 前面，两条都指向 dns-direct，顺序互不影响。
            dns?.rules?.add(0, DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
            })
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns?.rules?.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                })
            }
        }

        if (!forTest) _hack_custom_config = DataStore.globalCustomConfig
    }.let {
        val configMap = it.asMap().toMutableMap()
        Util.mergeJSON(configMap, proxy.requireBean().customConfigJson)
        ConfigBuildResult(
            gson.toJson(configMap),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) group.id else -1L
        )
    }

}
