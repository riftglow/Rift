package light.rift.ktx

import com.google.gson.JsonParser
import light.rift.fmt.AbstractBean
import light.rift.fmt.Serializable
import light.rift.fmt.http.parseHttp
import light.rift.fmt.hysteria.parseHysteria2
import light.rift.fmt.parseUniversal
import light.rift.fmt.shadowsocks.parseShadowsocks
import light.rift.fmt.socks.parseSOCKS
import light.rift.fmt.trojan.parseTrojan
import light.rift.fmt.tuic.parseTuic
import light.rift.fmt.v2ray.parseV2Ray
import light.rift.core.proxy.anytls.parseAnytls
import light.rift.core.utils.JavaUtil.gson
import light.rift.core.utils.Util
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// JSON & Base64

fun JSONObject.toStringPretty(): String {
    return gson.toJson(JsonParser.parseString(this.toString()))
}

inline fun <reified T : Any> JSONArray.filterIsInstance(): List<T> {
    val list = mutableListOf<T>()
    for (i in 0 until this.length()) {
        if (this[i] is T) list.add(this[i] as T)
    }
    return list
}

inline fun JSONArray.forEach(action: (Int, Any) -> Unit) {
    for (i in 0 until this.length()) {
        action(i, this[i])
    }
}

inline fun JSONObject.forEach(action: (String, Any) -> Unit) {
    for (k in this.keys()) {
        action(k, this.get(k))
    }
}

/** Accepts a JSON object, a JSON array, or a string that parses as either. */
fun isJsonObjectValid(j: Any): Boolean {
    if (j is JSONObject) return true
    if (j is JSONArray) return true
    // 非 String 标量（Int / Boolean / Double / JSONObject.NULL）：
    // 旧代码 j as String 抛 ClassCastException，未被只抓 JSONException
    // 的 catch 接住，直接穿出，整个订阅导入中断。
    if (j !is String) return false
    try {
        JSONObject(j)
    } catch (ex: JSONException) {
        try {
            JSONArray(j)
        } catch (ex1: JSONException) {
            return false
        }
    }
    return true
}

/** Returns the string value, or null when missing, blank, or not a string. */
fun JSONObject.getStr(name: String): String? {
    val obj = this.opt(name) ?: return null
    if (obj is String) {
        if (obj.isBlank()) {
            return null
        }
        return obj
    } else {
        return null
    }
}

fun JSONObject.getBool(name: String): Boolean? {
    return try {
        getBoolean(name)
    } catch (ignored: Exception) {
        null
    }
}

fun JSONObject.getIntNya(name: String): Int? {
    return try {
        getInt(name)
    } catch (ignored: Exception) {
        null
    }
}

fun String.decodeBase64UrlSafe(): String {
    return String(Util.b64Decode(this))
}

// Sub

class SubscriptionFoundException(val link: String) : RuntimeException()

suspend fun parseProxies(text: String): List<AbstractBean> {
    val spaceSplitLinks = text.split('\n').flatMap { it.trim().split(' ') }
    val lineSplitLinks = text.split('\n').map { it.trim() }

    val entities = ArrayList<AbstractBean>()
    val entitiesByLine = ArrayList<AbstractBean>()

    fun String.parseLink(entities: ArrayList<AbstractBean>) {
        if (startsWith("clash://install-config?") || startsWith("sn://subscription?")) {
            throw SubscriptionFoundException(this)
        }

        val link = this

        /** Tries one parser and logs the attempt; a failed parse only logs. */
        fun tryParse(
            label: String,
            parse: (String) -> AbstractBean,
            onFailure: (Throwable) -> Unit = { Logs.w(it) }
        ) {
            Logs.d("Try parse $label link: $link")
            runCatching { entities.add(parse(link)) }.onFailure(onFailure)
        }

        // Dispatch order matters: the first matching prefix wins.
        when {
            link.startsWith("sn://") ->
                tryParse("universal", ::parseUniversal)
            link.startsWith("socks://") || link.startsWith("socks4://") ||
                link.startsWith("socks4a://") || link.startsWith("socks5://") ->
                tryParse("socks", ::parseSOCKS)
            link.matches("(http|https)://.*".toRegex()) ->
                tryParse("http", ::parseHttp) { error ->
                    Logs.w(error)
                    val clashUrl = HttpUrl.Builder()
                        .scheme("https")
                        .host("install-config")
                        .addQueryParameter("url", link)
                        .build()
                        .toString()
                        .replaceFirst("https://", "clash://")
                    throw (SubscriptionFoundException(clashUrl))
                }
            link.startsWith("vmess://") ->
                tryParse("v2ray", ::parseV2Ray)
            link.startsWith("vless://") ->
                tryParse("v2ray", ::parseV2Ray)
            link.startsWith("trojan://") ->
                tryParse("trojan", ::parseTrojan)
            link.startsWith("ss://") ->
                tryParse("shadowsocks", ::parseShadowsocks)
            link.startsWith("hysteria2://") || link.startsWith("hy2://") ->
                tryParse("hysteria2", ::parseHysteria2)
            link.startsWith("tuic://") ->
                tryParse("TUIC", ::parseTuic)
            link.startsWith("anytls://") ->
                tryParse("anytls", ::parseAnytls)
        }
    }

    for (link in spaceSplitLinks) {
        link.parseLink(entities)
    }
    for (link in lineSplitLinks) {
        link.parseLink(entitiesByLine)
    }

    entities.forEach { it.initializeDefaultValues() }
    entitiesByLine.forEach { it.initializeDefaultValues() }
    if (entities.size == entitiesByLine.size) {
        // Same node parsed two ways but with a different display name:
        // noted for diagnosis only, it does not change which list is returned.
        hasDisplayNameDrift(entities, entitiesByLine)
    }
    return if (entities.size > entitiesByLine.size) entities else entitiesByLine
}

/**
 * Compares the two parses entry by entry. True when some entry is the same
 * node in both lists but carries a different display name.
 */
private fun hasDisplayNameDrift(first: List<AbstractBean>, second: List<AbstractBean>): Boolean {
    first.forEachIndexed { index, bean ->
        val lineBean = second[index]
        if (bean == lineBean && bean.displayName() != lineBean.displayName()) return true
    }
    return false
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}
