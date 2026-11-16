package light.rift.fmt.http

import light.rift.fmt.v2ray.isTLS
import light.rift.fmt.v2ray.setTLS
import light.rift.ktx.urlSafe
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseHttp(link: String): HttpBean {
    val parsed = link.toHttpUrlOrNull() ?: error("Invalid http(s) link: $link")
    if (parsed.encodedPath != "/") error("Not http proxy")

    val bean = HttpBean()
    bean.serverAddress = parsed.host
    bean.serverPort = parsed.port
    bean.username = parsed.username
    bean.password = parsed.password
    bean.sni = parsed.queryParameter("sni") ?: ""
    bean.name = parsed.fragment ?: ""
    bean.setTLS(parsed.scheme == "https")
    return bean
}

fun HttpBean.toUri(): String {
    val scheme = if (isTLS()) "https" else "http"
    val url = HttpUrl.Builder().scheme(scheme).host(serverAddress)
    if (serverPort in 1..65535) url.port(serverPort)
    if (username.isNotBlank()) url.username(username)
    if (password.isNotBlank()) url.password(password)
    if (sni.isNotBlank()) url.addQueryParameter("sni", sni)
    if (name.isNotBlank()) url.encodedFragment(name.urlSafe())
    return url.toString()
}
