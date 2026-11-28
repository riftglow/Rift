package light.rift.fmt.hysteria

import light.rift.database.DataStore
import light.rift.ktx.*
import light.rift.core.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull


// hysteria2://[auth@]hostname[:port]/?[key=value]&[key=value]...
fun parseHysteria2(url: String): HysteriaBean {
    // 2026-11-23 -68：端口跳跃还有另一种链接写法——范围直接写在 URL
    // 的端口位上（如 host:24000-32000，没有 mport）。OkHttp 的 URL
    // 解析不接受“24000-32000”这种端口，会返回 null 导致导入失败。
    // 所以先手动把范围抠出来，用首端口做占位，再走正常解析。
    var hopPortsFromUrl = ""
    var normalizedUrl = url
    val authorityStart = url.indexOf("://").let { if (it >= 0) it + 3 else 0 }
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        .let { if (it >= 0) it else url.length }
    val authority = url.substring(authorityStart, authorityEnd)
    // host:port 部分在最后一个 "@" 之后（跳过 userinfo 里的冒号）
    val hostPortStart = authority.lastIndexOf('@').let { if (it >= 0) it + 1 else 0 }
    val hostPort = authority.substring(hostPortStart)
    val lastColon = hostPort.lastIndexOf(':')
    if (lastColon >= 0) {
        val portPart = hostPort.substring(lastColon + 1)
        if (portPart.contains("-") || portPart.contains(",")) {
            hopPortsFromUrl = portPart
            val firstPort = portPart.substringBefore("-").substringBefore(",").trim()
            val replaceStart = authorityStart + hostPortStart + lastColon + 1
            normalizedUrl = url.substring(0, replaceStart) + firstPort + url.substring(authorityEnd)
        }
    }
    val link = normalizedUrl
        .replace("hysteria2://", "https://")
        .replace("hy2://", "https://")
        .toHttpUrlOrNull() ?: error("invalid hysteria link $url")
    return HysteriaBean().apply {
        protocolVersion = 2
        serverAddress = link.host
        serverPorts = link.port.toString()
        authPayload = if (link.password.isNotBlank()) {
            link.username + ":" + link.password
        } else {
            link.username
        }
        name = link.fragment ?: ""

        // mport 显式参数优先，没有 mport 时用 URL 端口位上的范围。
        hopPorts = link.queryParameter("mport") ?: hopPortsFromUrl
        link.queryParameter("sni")?.also {
            sni = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
//        link.queryParameter("upmbps")?.also {
//            uploadMbps = it.toIntOrNull() ?: uploadMbps
//        }
//        link.queryParameter("downmbps")?.also {
//            downloadMbps = it.toIntOrNull() ?: downloadMbps
//        }
        link.queryParameter("obfs-password")?.also {
            obfuscation = it
        }
//        link.queryParameter("pinSHA256")?.also {
//            // TODO your box do not support it
//        }
    }
}

fun HysteriaBean.toUri(): String {
    var un = ""
    var pw = ""
    if (authPayload.contains(":")) {
        un = authPayload.substringBefore(":")
        pw = authPayload.substringAfter(":")
    } else {
        un = authPayload
    }
    //
    val builder = linkBuilder()
        .host(serverAddress)
        .port(getFirstPort(serverPorts))
        .username(un)
        .password(pw)
    if (hopPorts.isNotBlank()) {
        builder.addQueryParameter("mport", hopPorts)
    } else if (isMultiPort(displayAddress())) {
        // 兼容：端口栏里直接写了范围的旧数据
        builder.addQueryParameter("mport", serverPorts)
    }
    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    if (allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    if (sni.isNotBlank()) {
        builder.addQueryParameter("sni", sni)
    }
    if (obfuscation.isNotBlank()) {
        builder.addQueryParameter("obfs", "salamander")
        builder.addQueryParameter("obfs-password", obfuscation)
    }
    return builder.toLink("hy2")
}

fun isMultiPort(hyAddr: String): Boolean {
    if (!hyAddr.contains(":")) return false
    val p = hyAddr.substringAfterLast(":")
    if (p.contains("-") || p.contains(",")) return true
    return false
}

fun getFirstPort(portStr: String): Int {
    return portStr.substringBefore(":").substringBefore(",").toIntOrNull() ?: 443
}

fun buildSingBoxOutboundHysteriaBean(bean: HysteriaBean): SingBoxOptions.SingBoxOption {
    return when (bean.protocolVersion) {
        2 -> SingBoxOptions.Outbound_Hysteria2Options().apply {
            type = "hysteria2"
            server = bean.serverAddress
            // 2026-11-23 -67：端口跳跃范围单独走 server_ports（有 hopPorts
            // 时优先），主端口走 server_port；都没跳跃时跟以前一样，
            // 端口栏是单端口就 server_port、是范围就 server_ports。
            if (bean.hopPorts.isNotBlank()) {
                val hopList = hopPortsToSingboxList(bean.hopPorts)
                if (hopList.isNotEmpty()) {
                    server_ports = hopList.toMutableList()
                } else {
                    // -79 C-H-1：hopPorts 填了但解析后是空——不能发空
                    // server_ports（核心会把它当无端口）。回落到 serverPorts
                    // 的单端口 / 范围，跟没有 hopPorts 时一样。
                    val port = bean.serverPorts.toIntOrNull()
                    if (port != null) {
                        server_port = port
                    } else {
                        server_ports = hopPortsToSingboxList(bean.serverPorts).toMutableList()
                    }
                }
            } else {
                val port = bean.serverPorts.toIntOrNull()
                if (port != null) {
                    server_port = port
                } else {
                    server_ports = hopPortsToSingboxList(bean.serverPorts).toMutableList()
                }
            }
            hop_interval = "${bean.hopInterval}s"
            // Hysteria2 增强（2026-11-10，规划第 13 项）：节点字段优先，未设则用
            // 全局设置；全未设则不发字段（走核心默认）。
            val hopIntervalMax = (bean.hopIntervalMax ?: 0).takeIf { it > 0 }
                ?: DataStore.hy2HopIntervalMax.toIntOrNull()?.takeIf { it > 0 }
            if (hopIntervalMax != null) hop_interval_max = "${hopIntervalMax}s"
            val bbrProfile = bean.bbrProfile?.takeIf { it.isNotBlank() }
                ?: DataStore.hy2BbrProfile.takeIf { it.isNotBlank() }
            if (bbrProfile != null) bbr_profile = bbrProfile
            if (bean.disableChromeParrot == true || DataStore.hy2DisableChromeParrot) {
                disable_chrome_parrot = true
            }
            if (bean.brutalDebug == true || DataStore.hy2BrutalDebug) {
                brutal_debug = true
            }
            up_mbps = bean.uploadMbps
            down_mbps = bean.downloadMbps
            if (bean.obfuscation.isNotBlank()) {
                obfs = SingBoxOptions.Hysteria2Obfs().apply {
                    type = "salamander"
                    password = bean.obfuscation
                }
            }
//            disable_mtu_discovery = bean.disableMtuDiscovery
            password = bean.authPayload
//            if (bean.streamReceiveWindow > 0) {
//                recv_window_conn = bean.streamReceiveWindow.toLong()
//            }
//            if (bean.connectionReceiveWindow > 0) {
//                recv_window = bean.connectionReceiveWindow.toLong()
//            }
            tls = SingBoxOptions.OutboundTLSOptions().apply {
                if (bean.sni.isNotBlank()) {
                    server_name = bean.sni
                }
                alpn = mutableListOf("h3")
                if (bean.caText.isNotBlank()) {
                    certificate = bean.caText
                }
                insecure = bean.allowInsecure || DataStore.globalAllowInsecure
                enabled = true
            }
        }

        else -> error("error_version $bean.protocolVersion")
    }
}

// -79 C-H-1：单端口段也要保留。原先只有 "a-b" / "a:b" 这种两段式
// 才进列表，单端口（mport=单端口的链接、或跳跃栏手填单端口）被直接
// 丢掉，server_ports 发成空列表、server_port 也不发，核心无端口
// 可拨。非数字段照旧丢掉（不把脏输入发给核心）。
fun hopPortsToSingboxList(s: String): List<String> {
    return s.split(",").mapNotNull {
        val segment = it.trim()
        val pRange = segment.replace("-", ":")
        val parts = pRange.split(":")
        when {
            parts.size == 2 && parts[0].toIntOrNull() != null && parts[1].toIntOrNull() != null -> pRange
            parts.size == 1 && segment.toIntOrNull() != null -> segment
            else -> null
        }
    }
}
