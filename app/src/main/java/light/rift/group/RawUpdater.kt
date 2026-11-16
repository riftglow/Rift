package light.rift.group

import android.annotation.SuppressLint
import light.rift.R
import light.rift.database.*
import light.rift.fmt.AbstractBean
import light.rift.fmt.http.HttpBean
import light.rift.fmt.hysteria.HysteriaBean
import light.rift.fmt.shadowsocks.ShadowsocksBean
import light.rift.fmt.shadowsocks.parseShadowsocks
import light.rift.fmt.socks.SOCKSBean
import light.rift.fmt.trojan.TrojanBean
import light.rift.fmt.tuic.TuicBean
import light.rift.fmt.v2ray.StandardV2RayBean
import light.rift.fmt.v2ray.VMessBean
import light.rift.fmt.v2ray.XhttpExtraConverter
import light.rift.fmt.v2ray.isTLS
import light.rift.fmt.v2ray.setTLS
import light.rift.fmt.wireguard.WireGuardBean
import light.rift.ktx.*
import libcore.Libcore
import light.rift.core.Protocols
import light.rift.core.proxy.anytls.AnyTLSBean
import light.rift.core.proxy.config.ConfigBean
import light.rift.core.utils.Util
import light.rift.ui.AutoBackup
import org.ini4j.Ini
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.yaml.snakeyaml.TypeDescription
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.StringReader
import androidx.core.net.toUri

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    // Batch 2 item 6: entries skipped during the last parseRaw call
    // (unsupported proxy type or transport), in "name (type)" form.
    // doUpdate reads this after parseRaw and passes it to the update
    // UI, so a bad entry is never imported silently.
    var lastSkipped: List<String> = emptyList()
        private set

    @SuppressLint("Recycle")
    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        val link = subscription.link
        var proxies: List<AbstractBean>
        if (link.startsWith("content://")) {
            val contentText = app.contentResolver.openInputStream(link.toUri())
                ?.use { it.bufferedReader().readText() }

            proxies = contentText?.let { parseRaw(contentText) }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
        } else {

            // S-B3: the libcore HTTP client now carries a 30s default
            // timeout (plus SetTimeout for callers that need a
            // different bound); the H3-direct path has its own 10s
            // deadline. The client must still be closed: Close()
            // releases its transport. Close only after the body and
            // headers are read.
            val client = Libcore.newHttpClient().apply {
                trySocks5(DataStore.mixedPort)
                // H10: H3-direct is only a fallback when the VPN is
                // down. When it is up but the mixed port is dead, the
                // update must fail loudly, not silently fetch direct.
                if (!DataStore.serviceState.connected) tryH3Direct()
                when (DataStore.appTLSVersion) {
                    "1.3" -> restrictedTLS()
                }
            }
            try {
                val response = client.newRequest().apply {
                    if (DataStore.allowInsecureOnRequest) {
                        allowInsecure()
                    }
                    setURL(subscription.link)
                    setUserAgent(subscription.customUserAgent.takeIf { it.isNotBlank() } ?: USER_AGENT)
                }.execute()
                proxies = parseRaw(Util.getStringBox(response.contentString))
                    ?: error(app.getString(R.string.no_proxies_found))

                subscription.subscriptionUserinfo =
                    Util.getStringBox(response.getHeader("Subscription-Userinfo"))

                // 修改默认名字
                if (proxyGroup.name?.startsWith("Subscription #") == true) {
                    var remoteName = Util.getStringBox(response.getHeader("content-disposition"))
                    if (remoteName.isNotBlank()) {
                        remoteName = Util.decodeFilename(remoteName)
                        if (remoteName.isNotBlank()) {
                            proxyGroup.name = remoteName
                        }
                    }
                }
            } finally {
                client.close()
            }
        }

        val proxiesMap = LinkedHashMap<String, AbstractBean>()
        for (proxy in proxies) {
            var index = 0
            var name = proxy.displayName()
            while (proxiesMap.containsKey(name)) {
                println("Exists name: $name")
                index++
                name = name.replace(" (${index - 1})", "")
                name = "$name ($index)"
                proxy.name = name
            }
            proxiesMap[proxy.displayName()] = proxy
        }
        proxies = proxiesMap.values.toList()

        // 空订阅当更新失败处理：解析出空列表（订阅返回空、纯 WireGuard 订阅被滤空等）
        // 不得走到后面的删除逻辑，否则一次更新会删光该组全部节点且无确认。
        if (proxies.isEmpty()) {
            error(app.getString(R.string.no_proxies_found))
        }

        if (subscription.forceResolve) forceResolve(proxies, proxyGroup.id)

        val exists = RiftDatabase.proxyDao.getByGroup(proxyGroup.id)
        val duplicate = ArrayList<String>()
        if (subscription.deduplication) {
            Logs.d("Before deduplication: ${proxies.size}")
            val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
            val uniqueNames = HashMap<Protocols.Deduplication, String>()
            for (_proxy in proxies) {
                val proxy = Protocols.Deduplication(_proxy, _proxy.javaClass.toString())
                if (!uniqueProxies.add(proxy)) {
                    val index = uniqueProxies.indexOf(proxy)
                    if (uniqueNames.containsKey(proxy)) {
                        val name = uniqueNames[proxy]!!.replace(" ($index)", "")
                        if (name.isNotBlank()) {
                            duplicate.add("$name ($index)")
                            uniqueNames[proxy] = ""
                        }
                    }
                    duplicate.add(_proxy.displayName() + " ($index)")
                } else {
                    uniqueNames[proxy] = _proxy.displayName()
                }
            }
            uniqueProxies.retainAll(uniqueNames.keys)
            proxies = uniqueProxies.toList().map { it.bean }
        }

        Logs.d("New profiles: ${proxies.size}")

        val nameMap = proxies.associateBy { bean ->
            bean.displayName()
        }

        Logs.d("Unique profiles: ${nameMap.size}")

        // S-B1: a subscription update no longer deletes nodes that
        // are missing from the new list — they are kept (with any
        // custom overrides such as renames), and the user deletes
        // them by hand. toReplace therefore only maps names present
        // in both lists; anything else in `exists` is retained
        // untouched.
        val toReplace = exists.mapNotNull { entity ->
            val name = entity.displayName()
            if (nameMap.contains(name)) name to entity else null
        }.toMap()
        val retained = exists.size - toReplace.size

        Logs.d("toReplace profiles: ${toReplace.size}")
        Logs.d("Retained profiles: $retained")

        val toUpdate = ArrayList<ProxyEntity>()
        val toAdd = ArrayList<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()

        var userOrder = 1L
        var changed = 0
        for ((name, bean) in nameMap.entries) {
            if (toReplace.contains(name)) {
                val entity = toReplace[name]!!
                val existsBean = entity.requireBean()
                // 更新订阅，保留自定义覆写设置
                bean.customOutboundJson = existsBean.customOutboundJson
                bean.customConfigJson = existsBean.customConfigJson
                when {
                    existsBean != bean -> {
                        changed++
                        entity.putBean(bean)
                        toUpdate.add(entity)
                        updated[entity.displayName()] = name

                        Logs.d("Updated profile: $name")
                    }

                    entity.userOrder != userOrder -> {
                        entity.putBean(bean)
                        toUpdate.add(entity)
                        entity.userOrder = userOrder

                        Logs.d("Reordered profile: $name")
                    }

                    else -> {
                        Logs.d("Ignored profile: $name")
                    }
                }
            } else {
                changed++
                toAdd.add(
                    ProxyEntity(
                        groupId = proxyGroup.id, userOrder = userOrder
                    ).apply {
                        putBean(bean)
                    })
                added.add(name)
                Logs.d("Inserted profile: $name")
            }
            userOrder++
        }

        // N4：保留节点仍用旧 userOrder，会与新列表的 1..N 编号撞车，
        // 同一组内 order 重复、列表顺序由 SQLite 平局决定。把保留节
        // 点重排到新列表之后，保持顺序确定。
        for (entity in exists) {
            if (!nameMap.contains(entity.displayName())) {
                entity.userOrder = userOrder++
                toUpdate.add(entity)
            }
        }

        // S-B2: the whole write — inserts, updates and the group's
        // lastUpdated — goes through one Room transaction, so a
        // crash mid-update cannot leave a half-new, half-old group.
        subscription.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
        RiftDatabase.instance.runInTransaction {
            if (toAdd.isNotEmpty()) RiftDatabase.proxyDao.insert(toAdd)
            if (toUpdate.isNotEmpty()) RiftDatabase.proxyDao.updateProxy(toUpdate)
            RiftDatabase.groupDao.updateGroup(proxyGroup)
        }
        Logs.d("Inserted profiles: ${toAdd.size}")
        Logs.d("Updated profiles: ${toUpdate.size}")

        val existCount = RiftDatabase.proxyDao.countByGroup(proxyGroup.id).toInt()

        // Retained nodes stay in the group, so the expected count
        // is the new list plus the retained ones.
        if (existCount != proxies.size + retained) {
            Logs.e("Exist profiles: $existCount, new profiles: ${proxies.size}, retained: $retained")
        }

        finishUpdate(proxyGroup)

        // A subscription change is a config change: schedule an auto backup.
        AutoBackup.schedule()

        userInterface?.onUpdateSuccess(
            proxyGroup, changed, added, updated, emptyList(), duplicate, byUser, lastSkipped
        )
    }

    /**
     * WireGuard was removed from the sing-box 1.15 core, so no import
     * path (subscription, file, clipboard, scan) may create a WireGuard
     * profile any more: a profile that looks valid here would fail the
     * whole service at box start (the core only has a stub). Filter
     * them out centrally, at the one entry point every import path
     * shares, and log how many were skipped.
     */
    suspend fun parseRaw(text: String, fileName: String = ""): List<AbstractBean>? {
        val result = parseRawInternal(text, fileName) ?: return null
        val skipped = result.count { it is WireGuardBean }
        if (skipped > 0) {
            Logs.w("Skipped $skipped WireGuard profiles: unsupported in sing-box 1.15 core")
        }
        return result.filterNot { it is WireGuardBean }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun parseRawInternal(text: String, fileName: String = ""): List<AbstractBean>? {

        lastSkipped = emptyList()
        val proxies = mutableListOf<AbstractBean>()
        // Batch 2 item 6: unsupported entries collected here, reported
        // by doUpdate instead of being imported silently as broken nodes.
        val skipped = mutableListOf<String>()

        if (text.contains("proxies:")) {

            // clash & meta

            try {

                val yaml = Yaml().apply {
                    addTypeDescription(TypeDescription(String::class.java, "str"))
                }.loadAs(text, Map::class.java)

                val globalClientFingerprint = yaml["global-client-fingerprint"]?.toString() ?: ""

                for (proxy in (yaml["proxies"] as? (List<Map<String, Any?>>) ?: error(
                    app.getString(R.string.no_proxies_found_in_file)
                ))) {
                    // Note: YAML numbers parsed as "Long"

                    // S-B4: one bad node must not kill the whole
                    // subscription — a per-node failure is recorded
                    // in the skipped list and parsing continues.
                    try {
                    when (proxy["type"] as String) {
                        "socks5" -> {
                            proxies.add(SOCKSBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                username = proxy["username"]?.toString() ?: ""
                                password = proxy["password"]?.toString() ?: ""
                                name = proxy["name"]?.toString() ?: ""
                            })
                        }

                        "http" -> {
                            proxies.add(HttpBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                username = proxy["username"]?.toString() ?: ""
                                password = proxy["password"]?.toString() ?: ""
                                setTLS(proxy["tls"]?.toString() == "true")
                                sni = proxy["sni"]?.toString() ?: ""
                                name = proxy["name"]?.toString() ?: ""
                                allowInsecure = proxy["skip-cert-verify"]?.toString() == "true"
                            })
                        }

                        "ss" -> {
                            val ssPlugin = mutableListOf<String>()
                            if (proxy.contains("plugin")) {
                                val opts = proxy["plugin-opts"] as Map<String, Any?>
                                when (proxy["plugin"]) {
                                    "obfs" -> {
                                        ssPlugin.apply {
                                            add("obfs-local")
                                            add("obfs=" + (opts["mode"]?.toString() ?: ""))
                                            add("obfs-host=" + (opts["host"]?.toString() ?: ""))
                                        }
                                    }

                                    "v2ray-plugin" -> {
                                        ssPlugin.apply {
                                            add("v2ray-plugin")
                                            add("mode=" + (opts["mode"]?.toString() ?: ""))
                                            if (opts["mode"]?.toString() == "true") add("tls")
                                            add("host=" + (opts["host"]?.toString() ?: ""))
                                            add("path=" + (opts["path"]?.toString() ?: ""))
                                            if (opts["mux"]?.toString() == "true") add("mux=8")
                                        }
                                    }
                                }
                            }
                            proxies.add(ShadowsocksBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                password = proxy["password"]?.toString() ?: ""
                                method = clashCipher(proxy["cipher"] as String)
                                plugin = ssPlugin.joinToString(";")
                                name = proxy["name"]?.toString() ?: ""
                            })
                        }

                        "vmess", "vless", "trojan" -> {
                            val bean = when (proxy["type"] as String) {
                                "vmess" -> VMessBean()
                                "vless" -> VMessBean().apply {
                                    alterId = -1 // make it VLESS
                                    packetEncoding = 2 // clash meta default XUDP
                                }

                                "trojan" -> TrojanBean().apply {
                                    security = "tls"
                                }

                                else -> error("impossible")
                            }

                            bean.serverAddress = proxy["server"]?.toString() ?: continue
                            bean.serverPort = proxy["port"]?.toString()?.toIntOrNull() ?: continue

                            // Batch 2 item 6: a transport this codebase
                            // cannot build (e.g. kcp, quic, trojan-xhttp)
                            // makes the whole entry skipped, never a
                            // silently broken node.
                            var unsupportedNetwork: String? = null
                            for (opt in proxy) {
                                when (opt.key) {
                                    "name" -> bean.name = opt.value?.toString() ?: ""
                                    "password" -> if (bean is TrojanBean) bean.password =
                                        opt.value?.toString() ?: ""

                                    "uuid" -> if (bean is VMessBean) bean.uuid =
                                        opt.value?.toString() ?: ""

                                    "alterId" -> if (bean is VMessBean && !bean.isVLESS) bean.alterId =
                                        opt.value?.toString()?.toIntOrNull() ?: 0

                                    "cipher" -> if (bean is VMessBean && !bean.isVLESS) bean.encryption =
                                        (opt.value as? String) ?: ""

                                    "flow" -> if (bean is VMessBean && bean.isVLESS) {
                                        (opt.value as? String)?.let {
                                            if (it.contains("xtls-rprx-vision")) {
                                                bean.encryption = "xtls-rprx-vision"
                                            }
                                        }
                                    }

                                    "packet-encoding" -> if (bean is VMessBean) {
                                        bean.packetEncoding = when ((opt.value as? String)) {
                                            "packetaddr" -> 1
                                            "xudp" -> 2
                                            else -> 0
                                        }
                                    }

                                    "tls" -> if (bean is VMessBean) {
                                        bean.security =
                                            if (opt.value as? Boolean == true) "tls" else ""
                                    }

                                    "servername", "sni" -> bean.sni = opt.value?.toString() ?: ""

                                    "alpn" -> bean.alpn =
                                        (opt.value as? List<Any>)?.joinToString("\n") ?: ""

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value as? Boolean == true

                                    "client-fingerprint" -> bean.utlsFingerprint =
                                        opt.value as String

                                    "reality-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (realityOpt in it) {
                                            bean.security = "tls"

                                            when (realityOpt.key) {
                                                "public-key" -> bean.realityPubKey =
                                                    realityOpt.value?.toString() ?: ""

                                                "short-id" -> bean.realityShortId =
                                                    realityOpt.value?.toString() ?: ""
                                            }
                                        }
                                    }

                                    "network" -> {
                                        when (opt.value) {
                                            "h2", "http" -> bean.type = "http"
                                            "ws", "grpc" -> bean.type = opt.value as String
                                            "tcp", null -> {
                                                // Plain TCP: bean.type stays default.
                                            }

                                            // Batch 2 item 6: Clash XHTTP.
                                            // Previously unmapped, so an
                                            // XHTTP node imported as plain
                                            // TCP and failed at connect
                                            // time. Now mapped to the
                                            // bean fields that
                                            // buildSingBoxOutbound turns
                                            // into a sing-box xhttp
                                            // transport (graft core).
                                            "xhttp", "splithttp" -> {
                                                // H1: xhttp is only
                                                // wired for VLESS
                                                // outbounds — a
                                                // VMess/Trojan node
                                                // mapped to xhttp
                                                // breaks the whole
                                                // config at parse
                                                // time. Only VLESS
                                                // maps; anything else
                                                // is skipped visibly.
                                                if (bean.isVLESS) {
                                                    bean.type = "xhttp"
                                                } else {
                                                    unsupportedNetwork = "xhttp"
                                                }
                                            }

                                            else -> unsupportedNetwork =
                                                opt.value?.toString() ?: "?"
                                        }
                                    }

                                    // Batch 2 item 6: Clash xhttp-opts.
                                    // path/host/mode go to the bean
                                    // fields; the rest (xmux, download
                                    // settings, padding) goes through
                                    // the existing clashToSingBox
                                    // converter into xhttpExtra JSON.
                                    "xhttp-opts" -> (opt.value as? Map<String, Any?>)?.also { xhttpOpts ->
                                        // H1: same VLESS-only rule as
                                        // "network" above.
                                        if (bean.isVLESS) {
                                            for (xhttpOpt in xhttpOpts) {
                                                when (xhttpOpt.key) {
                                                    "path" -> bean.path =
                                                        xhttpOpt.value?.toString() ?: ""

                                                    "host" -> bean.host =
                                                        when (val h = xhttpOpt.value) {
                                                            is List<*> -> h.joinToString("\n")
                                                            else -> h?.toString() ?: ""
                                                        }

                                                    "mode" -> bean.xhttpMode =
                                                        xhttpOpt.value?.toString() ?: ""
                                                }
                                            }
                                            bean.xhttpExtra = runCatching {
                                                XhttpExtraConverter.clashToSingBox(xhttpOpts)
                                            }.getOrDefault("")
                                        } else {
                                            unsupportedNetwork = "xhttp"
                                        }
                                    }

                                    "ws-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (wsOpt in it) {
                                            when (wsOpt.key) {
                                                "headers" -> (wsOpt.value as? Map<Any, Any?>)?.forEach { (key, value) ->
                                                    when (key.toString().lowercase()) {
                                                        "host" -> {
                                                            bean.host = value?.toString() ?: ""
                                                        }
                                                    }
                                                }

                                                "path" -> {
                                                    bean.path = wsOpt.value?.toString() ?: ""
                                                }

                                                "max-early-data" -> {
                                                    bean.wsMaxEarlyData =
                                                        wsOpt.value?.toString()?.toIntOrNull() ?: 0
                                                }

                                                "early-data-header-name" -> {
                                                    bean.earlyDataHeaderName =
                                                        wsOpt.value?.toString() ?: ""
                                                }

                                                "v2ray-http-upgrade" -> {
                                                    if (wsOpt.value as? Boolean == true) {
                                                        bean.type = "httpupgrade"
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    "h2-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (h2Opt in it) {
                                            when (h2Opt.key) {
                                                "host" -> bean.host =
                                                    (h2Opt.value as? List<Any>)?.joinToString("\n") ?: ""

                                                "path" -> bean.path = h2Opt.value?.toString() ?: ""
                                            }
                                        }
                                    }

                                    "http-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (httpOpt in it) {
                                            when (httpOpt.key) {
                                                "path" -> bean.path =
                                                    (httpOpt.value as? List<Any>)?.joinToString("\n") ?: ""

                                                "headers" -> {
                                                    (httpOpt.value as? Map<Any, List<Any>>)?.forEach { (key, value) ->
                                                        when (key.toString().lowercase()) {
                                                            "host" -> {
                                                                bean.host = value.joinToString("\n")
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    "grpc-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (grpcOpt in it) {
                                            when (grpcOpt.key) {
                                                "grpc-service-name" -> bean.path =
                                                    grpcOpt.value?.toString() ?: ""
                                            }
                                        }
                                    }

                                    "smux" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (smuxOpt in it) {
                                            when (smuxOpt.key) {
                                                "enabled" -> bean.enableMux =
                                                    smuxOpt.value.toString() == "true"

                                                "max-streams" -> bean.muxConcurrency =
                                                    smuxOpt.value.toString().toInt()

                                                "padding" -> bean.muxPadding =
                                                    smuxOpt.value.toString() == "true"
                                            }
                                        }
                                    }

                                    "ech-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (echOpt in it) {
                                            when (echOpt.key) {
                                                "enable" -> bean.enableECH =
                                                    echOpt.value.toString() == "true"
                                            }
                                        }
                                    }
                                }
                            }
                            if (unsupportedNetwork != null) {
                                skipped.add(
                                    "${bean.name ?: proxy["name"]?.toString() ?: "?"} " +
                                        "(network: $unsupportedNetwork)"
                                )
                            } else {
                                proxies.add(bean)
                            }
                        }

                        "anytls" -> {
                            val bean = AnyTLSBean()
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPort = opt.value.toString().toInt()
                                    "password" -> bean.password = opt.value.toString()
                                    "client-fingerprint" -> bean.utlsFingerprint =
                                        opt.value as String

                                    "sni" -> bean.sni = opt.value.toString()
                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "alpn" -> {
                                        val alpn = (opt.value as? (List<String>))
                                        bean.alpn = alpn?.joinToString("\n") ?: ""
                                    }
                                }
                            }
                            proxies.add(bean)
                        }

                        "hysteria2" -> {
                            val bean = HysteriaBean()
                            bean.protocolVersion = 2
                            var hopPorts = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPorts = opt.value.toString()
                                    "ports" -> hopPorts = opt.value.toString()

                                    "obfs-password" -> bean.obfuscation = opt.value.toString()

                                    "password" -> bean.authPayload = opt.value.toString()

                                    "sni" -> bean.sni = opt.value.toString()

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "up" -> bean.uploadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0

                                    "down" -> bean.downloadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0
                                }
                            }
                            if (hopPorts.isNotBlank()) {
                                bean.serverPorts = hopPorts
                            }
                            proxies.add(bean)
                        }

                        "tuic" -> {
                            val bean = TuicBean()
                            var ip = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value.toString()
                                    "ip" -> ip = opt.value.toString()
                                    "port" -> bean.serverPort = opt.value.toString().toInt()

                                    "token" -> {
                                        bean.protocolVersion = 4
                                        bean.token = opt.value.toString()
                                    }

                                    "uuid" -> bean.uuid = opt.value.toString()

                                    "password" -> bean.token = opt.value.toString()

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "disable-sni" -> bean.disableSNI =
                                        opt.value.toString() == "true"

                                    "reduce-rtt" -> bean.reduceRTT =
                                        opt.value.toString() == "true"

                                    "sni" -> bean.sni = opt.value.toString()

                                    "alpn" -> {
                                        val alpn = (opt.value as? (List<String>))
                                        bean.alpn = alpn?.joinToString("\n") ?: ""
                                    }

                                    "congestion-controller" -> bean.congestionController =
                                        opt.value.toString()

                                    "udp-relay-mode" -> bean.udpRelayMode = opt.value.toString()

                                }
                            }
                            if (ip.isNotBlank()) {
                                bean.serverAddress = ip
                                if (bean.sni.isNullOrBlank() && !bean.serverAddress.isNullOrBlank() && !bean.serverAddress.isIpAddress()) {
                                    bean.sni = bean.serverAddress
                                }
                            }
                            proxies.add(bean)
                        }

                        // Batch 2 item 6: an unknown Clash proxy
                        // type used to fall through silently and
                        // vanish. Record it so the update dialog
                        // can say it was not imported, and why.
                        else -> skipped.add(
                            "${proxy["name"]?.toString() ?: "?"} " +
                                "(type: ${proxy["type"]})"
                        )
                    }
                    } catch (e: Exception) {
                        Logs.w(e)
                        skipped.add(
                            "${proxy["name"]?.toString() ?: "?"} " +
                                "(parse failed: ${e.readableMessage})"
                        )
                    }
                }

                // Fix ent
                proxies.forEach {
                    it.initializeDefaultValues()
                    if (it is StandardV2RayBean) {
                        // 1. SNI
                        if (it.isTLS() && it.sni.isNullOrBlank() && !it.host.isNullOrBlank() && !it.host.isIpAddress()) {
                            it.sni = it.host
                        }
                        // 2. globalClientFingerprint
                        if (!it.realityPubKey.isNullOrBlank() && it.utlsFingerprint.isNullOrBlank()) {
                            it.utlsFingerprint = globalClientFingerprint
                            if (it.utlsFingerprint.isNullOrBlank()) it.utlsFingerprint = "chrome"
                        }
                    }
                }
                lastSkipped = skipped
                return proxies
            } catch (e: YAMLException) {
                Logs.w(e)
            }
        } else if (text.contains("[Interface]")) {
            // wireguard
            try {
                val wgBeans = parseWireGuard(text).map {
                    if (fileName.isNotBlank()) it.name = fileName.removeSuffix(".conf")
                    it
                }
                // WireGuard was removed from the sing-box 1.15 core:
                // parseRaw() filters these out below, but dropping
                // them silently hides the loss. Record each one as
                // skipped so the update dialog can say so.
                wgBeans.forEach {
                    skipped.add("${it.name ?: "?"} (type: wireguard)")
                }
                lastSkipped = skipped
                proxies.addAll(wgBeans)
                return proxies
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        try {
            val json = JSONTokener(text).nextValue()
            return parseJSON(json)
        } catch (ignored: Exception) {
        }

        try {
            return parseProxies(text.decodeBase64UrlSafe()).takeIf { it.isNotEmpty() }
                ?: error("Not found")
        } catch (e: Exception) {
            Logs.w(e)
        }

        try {
            return parseProxies(text).takeIf { it.isNotEmpty() } ?: error("Not found")
        } catch (e: SubscriptionFoundException) {
            throw e
        } catch (ignored: Exception) {
        }

        return null
    }

    fun clashCipher(cipher: String): String {
        return when (cipher) {
            "dummy" -> "none"
            else -> cipher
        }
    }

    fun parseWireGuard(conf: String): List<WireGuardBean> {
        val ini = Ini(StringReader(conf))
        val iface = ini["Interface"] ?: error("Missing 'Interface' selection")
        val bean = WireGuardBean().applyDefaultValues()
        val localAddresses = iface.getAll("Address")
        if (localAddresses.isNullOrEmpty()) error("Empty address in 'Interface' selection")
        bean.localAddress = localAddresses.flatMap { it.split(",") }.joinToString("\n")
        bean.privateKey = iface["PrivateKey"] ?: ""
        bean.mtu = iface["MTU"]?.toIntOrNull() ?: 1420
        val peers = ini.getAll("Peer")
        if (peers.isNullOrEmpty()) error("Missing 'Peer' selections")
        val beans = mutableListOf<WireGuardBean>()
        for (peer in peers) {
            val endpoint = peer["Endpoint"]
            if (endpoint.isNullOrBlank() || !endpoint.contains(":")) {
                continue
            }

            val peerBean = bean.clone()
            peerBean.serverAddress = endpoint.substringBeforeLast(":")
            peerBean.serverPort = endpoint.substringAfterLast(":").toIntOrNull() ?: continue
            peerBean.peerPublicKey = peer["PublicKey"] ?: continue
            peerBean.peerPreSharedKey = peer["PresharedKey"] ?: ""
            beans.add(peerBean.applyDefaultValues())
        }
        if (beans.isEmpty()) error("Empty available peer list")
        return beans
    }

    fun parseJSON(json: Any): List<AbstractBean> {
        val proxies = ArrayList<AbstractBean>()

        if (json is JSONObject) {
            when {
                json.has("method") -> {
                    return listOf(json.parseShadowsocks())
                }

                json.has("outbounds") -> {
                    return json.getJSONArray("outbounds")
                        .filterIsInstance<JSONObject>()
                        .mapNotNull {
                            val ty = it.getStr("type")
                            if (ty == null || ty == "" ||
                                ty == "dns" || ty == "block" || ty == "direct" || ty == "selector" || ty == "urltest"
                            ) {
                                null
                            } else {
                                it
                            }
                        }.map {
                            ConfigBean().apply {
                                applyDefaultValues()
                                type = 1
                                config = it.toStringPretty()
                                name = it.getStr("tag") ?: ""
                            }
                        }
                }

                json.has("server") && json.has("server_port") -> {
                    return listOf(ConfigBean().applyDefaultValues().apply {
                        type = 1
                        config = json.toStringPretty()
                    })
                }
            }
        } else {
            json as JSONArray
            json.forEach { _, it ->
                if (isJsonObjectValid(it)) {
                    proxies.addAll(parseJSON(it))
                }
            }
        }

        proxies.forEach { it.initializeDefaultValues() }
        return proxies
    }

}
