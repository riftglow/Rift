package light.rift.fmt.shadowsocks

import light.rift.ktx.*
import light.rift.core.SingBoxOptions
import light.rift.core.utils.Util
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

fun ShadowsocksBean.fixPluginName() {
    if (plugin.startsWith("simple-obfs")) {
        plugin = plugin.replaceFirst("simple-obfs", "obfs-local")
    }
}

fun parseShadowsocks(url: String): ShadowsocksBean {
    return if (url.substringBefore("#").contains("@")) {
        parseSsAndroidStyle(url)
    } else {
        parseSsV2rayNStyle(url)
    }
}

private fun parseSsAndroidStyle(url: String): ShadowsocksBean {
    var link = url.replace("ss://", "https://").toHttpUrlOrNull() ?: error(
        "invalid ss-android link $url"
    )

    if (link.username.isBlank()) { // fix justmysocks's shit link
        link = (("https://" + url.substringAfter("ss://")
            .substringBefore("#")
            .decodeBase64UrlSafe()).toHttpUrlOrNull()
            ?: error("invalid jms link $url")
                ).newBuilder().fragment(url.substringAfter("#", "")).build()
    }

    // ss-android style
    if (link.password.isNotBlank()) {
        return ShadowsocksBean().apply {
            serverAddress = link.host
            serverPort = link.port
            method = link.username
            password = link.password
            plugin = link.queryParameter("plugin") ?: ""
            name = link.fragment ?: ""
            fixPluginName()
        }
    }

    val methodAndPswd = link.username.decodeBase64UrlSafe()
    // 无冒号时 substringAfter(":") 返回原串，方法与密码会变成同一值。
    if (!methodAndPswd.contains(":")) error("invalid ss link $url")
    return ShadowsocksBean().apply {
        serverAddress = link.host
        serverPort = link.port
        method = methodAndPswd.substringBefore(":")
        password = methodAndPswd.substringAfter(":")
        plugin = link.queryParameter("plugin") ?: ""
        name = link.fragment ?: ""
        fixPluginName()
    }
}

private fun parseSsV2rayNStyle(url: String): ShadowsocksBean {
    // v2rayN style
    var stripped = url
    if (stripped.contains("#")) stripped = stripped.substringBefore("#")

    val link = ("https://" + stripped.substringAfter("ss://")
        .decodeBase64UrlSafe()).toHttpUrlOrNull() ?: error("invalid v2rayN link $url")

    return ShadowsocksBean().apply {
        serverAddress = link.host
        serverPort = link.port
        method = link.username
        password = link.password
        plugin = ""
        // 无 "#" 时 substringAfter("#") 返回整条链接，节点名会被写成链接本身。
        val remarks = url.substringAfter("#", "").unUrlSafe()
        if (remarks.isNotBlank()) name = remarks
    }
}

fun ShadowsocksBean.toUri(): String {
    val builder = linkBuilder().username(Util.b64EncodeUrlSafe("$method:$password"))
        .host(serverAddress)
        .port(serverPort)

    if (plugin.isNotBlank()) {
        builder.addQueryParameter("plugin", plugin)
    }
    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    return builder.toLink("ss").replace("$serverPort/", "$serverPort")
}

fun JSONObject.parseShadowsocks(): ShadowsocksBean {
    val bean = ShadowsocksBean()
    bean.serverAddress = getStr("server") ?: ""
    bean.serverPort = getIntNya("server_port") ?: 0
    bean.password = getStr("password") ?: ""
    bean.method = getStr("method") ?: ""
    bean.name = optString("remarks", "")

    val pluginId = getStr("plugin")
    if (!pluginId.isNullOrBlank()) {
        bean.plugin = pluginId + ";" + optString("plugin_opts", "")
    }
    return bean
}

fun buildSingBoxOutboundShadowsocksBean(bean: ShadowsocksBean): SingBoxOptions.Outbound_ShadowsocksOptions {
    val outbound = SingBoxOptions.Outbound_ShadowsocksOptions()
    outbound.type = "shadowsocks"
    outbound.server = bean.serverAddress
    outbound.server_port = bean.serverPort
    outbound.password = bean.password
    outbound.method = bean.method
    if (bean.plugin.isNotBlank()) {
        outbound.plugin = bean.plugin.substringBefore(";")
        outbound.plugin_opts = bean.plugin.substringAfter(";")
        if (outbound.plugin == "none") {
            outbound.plugin = null
            outbound.plugin_opts = null
        }
    }
    return outbound
}
