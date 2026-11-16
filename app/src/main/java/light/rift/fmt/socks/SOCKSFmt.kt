package light.rift.fmt.socks

import light.rift.ktx.decodeBase64UrlSafe
import light.rift.ktx.toLink
import light.rift.ktx.unUrlSafe
import light.rift.ktx.urlSafe
import light.rift.core.SingBoxOptions
import light.rift.core.utils.NGUtil
import light.rift.core.utils.Util
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseSOCKS(link: String): SOCKSBean {
    val url = ("http://" + link.substringAfter("://")).toHttpUrlOrNull()
        ?: error("Not supported: $link")

    val bean = SOCKSBean()
    bean.protocol = when {
        link.startsWith("socks4://") -> SOCKSBean.PROTOCOL_SOCKS4
        link.startsWith("socks4a://") -> SOCKSBean.PROTOCOL_SOCKS4A
        else -> SOCKSBean.PROTOCOL_SOCKS5
    }
    bean.name = url.fragment ?: ""
    bean.serverAddress = url.host
    bean.serverPort = url.port
    bean.username = url.username
    bean.password = url.password
    // v2rayN fmt
    if (bean.password.isNullOrBlank() && !bean.username.isNullOrBlank()) {
        try {
            val decoded = bean.username.decodeBase64UrlSafe()
            // 无冒号时 substringAfter(":") 返回原串，用户名与密码会变成同一值。
            if (decoded.contains(":")) {
                bean.username = decoded.substringBefore(":")
                bean.password = decoded.substringAfter(":")
            } else {
                bean.username = decoded
                bean.password = ""
            }
        } catch (_: Exception) {
        }
    }
    return bean
}

fun SOCKSBean.toUri(): String {
    val builder = HttpUrl.Builder().scheme("http").host(serverAddress).port(serverPort)
    if (!username.isNullOrBlank()) builder.username(username)
    if (!password.isNullOrBlank()) builder.password(password)
    if (!name.isNullOrBlank()) builder.encodedFragment(name.urlSafe())
    return builder.toLink("socks${protocolVersion()}")
}

fun SOCKSBean.toV2rayN(): String {
    val auth = if (username.isNotBlank()) {
        username.urlSafe() + ":" + password.urlSafe() + "@"
    } else {
        ""
    }
    val link = "socks://" + NGUtil.encode(auth + "$serverAddress:$serverPort")
    return if (name.isNotBlank()) {
        link + "#" + name.urlSafe()
    } else {
        link
    }
}

fun buildSingBoxOutboundSocksBean(bean: SOCKSBean): SingBoxOptions.Outbound_SocksOptions {
    val outbound = SingBoxOptions.Outbound_SocksOptions()
    outbound.type = "socks"
    outbound.server = bean.serverAddress
    outbound.server_port = bean.serverPort
    outbound.username = bean.username
    outbound.password = bean.password
    outbound.version = bean.protocolVersionName()
    return outbound
}
