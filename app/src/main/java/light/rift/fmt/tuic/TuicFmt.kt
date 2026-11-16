package light.rift.fmt.tuic

import light.rift.database.DataStore
import light.rift.ktx.linkBuilder
import light.rift.ktx.toLink
import light.rift.ktx.urlSafe
import light.rift.core.SingBoxOptions
import light.rift.core.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseTuic(url: String): TuicBean {
    // https://github.com/daeuniverse/dae/discussions/182
    val link = url.replace("tuic://", "https://").toHttpUrlOrNull() ?: error(
        "invalid tuic link $url"
    )
    val bean = TuicBean()
    bean.protocolVersion = 5
    bean.name = link.fragment ?: ""
    bean.serverAddress = link.host
    bean.serverPort = link.port

    val rawUser = link.username
    val rawPass = link.password
    if (rawUser.contains(":")) {
        val parts = rawUser.split(":", limit = 2)
        bean.uuid = parts[0]
        bean.token = parts.getOrElse(1) { "" }
    } else {
        bean.uuid = rawUser
        bean.token = rawPass
    }

    link.queryParameter("sni")?.let { bean.sni = it }
    link.queryParameter("congestion_control")?.let { bean.congestionController = it }
    link.queryParameter("udp_relay_mode")?.let { bean.udpRelayMode = it }
    link.queryParameter("alpn")?.let { bean.alpn = it }
    if (link.queryParameter("allow_insecure") == "1") bean.allowInsecure = true
    if (link.queryParameter("disable_sni") == "1") bean.disableSNI = true
    return bean
}

fun TuicBean.toUri(): String {
    val builder = linkBuilder().username(uuid).password(token).host(serverAddress).port(serverPort)

    builder.addQueryParameter("congestion_control", congestionController)
    builder.addQueryParameter("udp_relay_mode", udpRelayMode)

    if (sni.isNotBlank()) builder.addQueryParameter("sni", sni)
    if (alpn.isNotBlank()) builder.addQueryParameter("alpn", alpn)
    if (allowInsecure) builder.addQueryParameter("allow_insecure", "1")
    if (disableSNI) builder.addQueryParameter("disable_sni", "1")
    if (name.isNotBlank()) builder.encodedFragment(name.urlSafe())

    return builder.toLink("tuic")
}

fun buildSingBoxOutboundTuicBean(bean: TuicBean): SingBoxOptions.Outbound_TUICOptions {
    if (bean.protocolVersion == 4) throw Exception("TUIC v4 is no longer supported")
    val outbound = SingBoxOptions.Outbound_TUICOptions()
    outbound.type = "tuic"
    outbound.server = bean.serverAddress
    outbound.server_port = bean.serverPort
    outbound.uuid = bean.uuid
    outbound.password = bean.token
    outbound.congestion_control = bean.congestionController
    if (bean.udpRelayMode == "quic") outbound.udp_relay_mode = "quic"
    outbound.zero_rtt_handshake = bean.reduceRTT
    outbound.tls = SingBoxOptions.OutboundTLSOptions().apply {
        if (bean.sni.isNotBlank()) {
            server_name = bean.sni
        }
        if (bean.alpn.isNotBlank()) {
            alpn = bean.alpn.listByLineOrComma().toMutableList()
        }
        if (bean.caText.isNotBlank()) {
            certificate = bean.caText
        }
        disable_sni = bean.disableSNI
        insecure = bean.allowInsecure || DataStore.globalAllowInsecure
        enabled = true
    }
    return outbound
}
