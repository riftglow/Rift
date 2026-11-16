package light.rift.core.proxy.anytls

import light.rift.database.DataStore
import light.rift.ktx.blankAsNull
import light.rift.ktx.linkBuilder
import light.rift.ktx.toLink
import light.rift.ktx.urlSafe
import light.rift.core.SingBoxOptions
import light.rift.core.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun buildSingBoxOutboundAnyTLSBean(bean: AnyTLSBean): SingBoxOptions.Outbound_AnyTLSOptions {
    return SingBoxOptions.Outbound_AnyTLSOptions().apply {
        type = "anytls"
        server = bean.serverAddress
        server_port = bean.serverPort
        password = bean.password

        // AnyTLS 会话复用（2026-11-10，规划第 14 项）：节点字段优先，未设则用
        // 全局设置；全未设则不发字段（走核心默认）。
        val checkInterval = (bean.idleSessionCheckInterval ?: 0).takeIf { it > 0 }
            ?: DataStore.anytlsIdleCheckInterval.toIntOrNull()?.takeIf { it > 0 }
        if (checkInterval != null) idle_session_check_interval = "${checkInterval}s"
        val idleTimeout = (bean.idleSessionTimeout ?: 0).takeIf { it > 0 }
            ?: DataStore.anytlsIdleTimeout.toIntOrNull()?.takeIf { it > 0 }
        if (idleTimeout != null) idle_session_timeout = "${idleTimeout}s"
        val minIdle = (bean.minIdleSession ?: 0).takeIf { it > 0 }
            ?: DataStore.anytlsMinIdleSession.toIntOrNull()?.takeIf { it > 0 }
        if (minIdle != null) min_idle_session = minIdle

        tls = SingBoxOptions.OutboundTLSOptions().apply {
            enabled = true
            server_name = bean.sni.blankAsNull()
            if (bean.allowInsecure) insecure = true
            alpn = bean.alpn.blankAsNull()?.listByLineOrComma()?.toMutableList()
            bean.certificates.blankAsNull()?.let {
                certificate = it
            }
            bean.utlsFingerprint.blankAsNull()?.let {
                utls = SingBoxOptions.OutboundUTLSOptions().apply {
                    enabled = true
                    fingerprint = it
                }
            }
            bean.echConfig.blankAsNull()?.let {
                // In new version, some complex options will be deprecated, so we just do this.
                ech = SingBoxOptions.OutboundECHOptions().apply {
                    enabled = true
                    config = mutableListOf(it)
                }
            }
        }
    }
}

fun AnyTLSBean.toUri(): String {
    val builder = linkBuilder()
        .host(serverAddress)
        .port(serverPort)
        .username(password)
    if (!name.isNullOrBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    if (allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    if (!sni.isNullOrBlank()) {
        builder.addQueryParameter("sni", sni)
    }
    if (!utlsFingerprint.isNullOrBlank()) {
        builder.addQueryParameter("fp", utlsFingerprint)
    }
    return builder.toLink("anytls")
}

fun parseAnytls(url: String): AnyTLSBean {
    // https://github.com/anytls/anytls-go/blob/main/docs/uri_scheme.md
    val link = url.replace("anytls://", "https://").toHttpUrlOrNull() ?: error(
        "invalid anytls link $url"
    )
    return AnyTLSBean().apply {
        serverAddress = link.host
        serverPort = link.port
        name = link.fragment ?: ""
        password = link.username
        sni = link.queryParameter("sni") ?: ""
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
        link.queryParameter("fp")?.let {
            utlsFingerprint = it
        }
    }
}
