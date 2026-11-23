package light.rift.ui

import light.rift.R
import light.rift.database.DataStore
import light.rift.ktx.app
import light.rift.core.utils.Util
import libcore.Libcore
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 出口 IP 查询（回退链，HTTPS）。NetworkFragment 的出口自检和
 * 主页底栏的出口 IP 常驻显示共用这里。
 *
 * 2026-11-23 -64：主源换 ipinfo.io/json（HTTPS、无 token，匿名每天 1,000 次），
 * 回退 ipwho.is（现用，保留），最后兜底 Cloudflare trace（只回 IP + 国家码）。
 * 每源 3 秒超时；链本身覆盖了 -56 的重试（首包抖动导致第一源失败时，
 * 下一源接着试），不再整链重试一次。全部失败返回 null／抛错，
 * 界面显示“获取失败”，绝不顶旧 IP（-56/-63 已保证）。
 *
 * 之前用 ip-api.com 免费接口，只支持 HTTP（HTTPS 报 403，SSL 需付费版），
 * 出口 IP 与节点域名都走明文。ipwho.is 免费版支持 HTTPS，字段在这里转成
 * ip-api 的字段名，下游（状态卡、出口自检）不用改。ipinfo.io 的字段
 * 同样转成 ip-api 的字段名，并多一个 "source" 键，标明实际是哪个源答的，
 * 出口自检卡的“数据来源”文案据此显示，不再写死 ip-api.com。
 * 入站走当前节点；未连接时就是直连。连接状态下先探一下 mixed 端口，
 * 探不通直接报错，绝不把直连 IP 误标为出口 IP。
 */
object EgressIp {

    data class EgressInfo(val ip: String, val countryCode: String, val city: String, val source: String)

    /** 回退链里各源在“数据来源”文案里显示的名字。 */
    const val SOURCE_IPINFO = "ipinfo.io"
    const val SOURCE_IPWHO = "ipwho.is"
    const val SOURCE_CLOUDFLARE = "cloudflare.com"

    /** ISO 3166-1 alpha-2 country code → flag emoji (regional indicators).
     * Blank or invalid code returns an empty string. Pure function, no I/O. */
    fun flagEmoji(countryCode: String): String {
        val code = countryCode.trim().uppercase()
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return ""
        val first = Character.toChars(0x1F1E6 + (code[0] - 'A'))
        val second = Character.toChars(0x1F1E6 + (code[1] - 'A'))
        return String(first + second)
    }

    /** Blocking: call off the main thread. Null on any failure. */
    fun fetch(): EgressInfo? {
        val json = try {
            queryJson()
        } catch (_: Exception) {
            return null
        }
        val ip = json.optString("query")
        if (ip.isBlank()) return null
        // city 是 ipwho.is／ipinfo.io 返回的英文城市名（如 Los Angeles），系统没有现成的
        // 城市名中译办法，所以状态卡里直接显示英文（用户 2026-11-12 定：英文也行）。
        // Cloudflare trace 没有城市，city 为空，状态卡只显示国家。
        return EgressInfo(ip, json.optString("countryCode"), json.optString("city"), json.optString("source"))
    }

    /**
     * 按 ipinfo.io → ipwho.is → Cloudflare trace 顺序各试一次；全部失败才抛错。
     * 返回的 JSON 已转成 ip-api 的字段名，并多一个 "source" 键（见 SOURCE_*）。
     * 调用方按原有约定处理异常（NetworkFragment 捕获后显示错误文案）。
     */
    fun queryJson(): JSONObject {
        val connected = DataStore.serviceState.connected
        if (connected) {
            try {
                Socket().use {
                    it.connect(InetSocketAddress("127.0.0.1", DataStore.mixedPort), 2000)
                }
            } catch (e: Exception) {
                error(app.getString(R.string.egress_check_mixed_unready))
            }
        }
        var lastError: Exception? = null
        for (query in listOf(::queryIpinfo, ::queryIpwho, ::queryCloudflare)) {
            try {
                return query(connected)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: error("egress check failed")
    }

    /** 单次出口查询用的 HTTP 客户端：3 秒超时，连接状态下走 mixed SOCKS5 入站。 */
    private fun newClient(connected: Boolean) = Libcore.newHttpClient().apply {
        modernTLS()
        keepAlive()
        // 2026-11-23 -64：每源单次上限 3 秒；慢源不等，交给下一源。
        setTimeout(3000)
        if (connected) {
            // 本地代理认证（0.2.29 -26）：mixed 入口开了 users 就得带账号密码。
            if (DataStore.localProxyAuthEnabled) {
                trySocks5Auth(DataStore.mixedPort, DataStore.mixedInboundUser, DataStore.mixedInboundPass)
            } else {
                trySocks5(DataStore.mixedPort)
            }
        }
    }

    /** 主源 ipinfo.io/json：HTTPS，无 token 匿名每天 1,000 次。 */
    private fun queryIpinfo(connected: Boolean): JSONObject {
        val client = newClient(connected)
        try {
            val response = client.newRequest().apply {
                setURL("https://ipinfo.io/json")
            }.execute()
            val body = Util.getStringBox(response.contentString)
            val raw = JSONObject(body)
            val ip = raw.optString("ip")
            if (ip.isBlank()) error("ipinfo.io returned no IP")
            // loc 是 "lat,lon"（如 "34.0522,-118.2437"）。
            val latLon = raw.optString("loc").split(",")
            // org 是 "AS12345 ISP Name" 形式，isp／org 都用它。
            val org = raw.optString("org")
            // ipinfo.io 的 country 就是两位国家码（如 "US"），没有完整国名；
            // place 拼出来是 "US · California · Los Angeles"，可读。
            return JSONObject().apply {
                put("status", "success")
                put("query", ip)
                put("country", raw.optString("country"))
                put("countryCode", raw.optString("country"))
                put("regionName", raw.optString("region"))
                put("city", raw.optString("city"))
                put("isp", org)
                put("org", org)
                put("lat", latLon.getOrNull(0)?.toDoubleOrNull() ?: 0.0)
                put("lon", latLon.getOrNull(1)?.toDoubleOrNull() ?: 0.0)
                put("timezone", raw.optString("timezone"))
                put("source", SOURCE_IPINFO)
            }
        } finally {
            client.close()
        }
    }

    /** 回退源 ipwho.is/：HTTPS，字段名与 ip-api 不同，这里转成 ip-api 的字段名。 */
    private fun queryIpwho(connected: Boolean): JSONObject {
        val client = newClient(connected)
        try {
            val response = client.newRequest().apply {
                setURL("https://ipwho.is/")
            }.execute()
            val body = Util.getStringBox(response.contentString)
            val raw = JSONObject(body)
            if (!raw.optBoolean("success", false)) {
                error(raw.optString("message").ifBlank { "ipwho.is returned no IP" })
            }
            val json = JSONObject().apply {
                put("status", "success")
                put("query", raw.optString("ip"))
                put("country", raw.optString("country"))
                put("countryCode", raw.optString("country_code"))
                put("regionName", raw.optString("region"))
                put("city", raw.optString("city"))
                put("isp", raw.optJSONObject("connection")?.optString("isp") ?: "")
                put("org", raw.optJSONObject("connection")?.optString("org") ?: "")
                put("lat", raw.optDouble("latitude"))
                put("lon", raw.optDouble("longitude"))
                put("timezone", raw.optJSONObject("timezone")?.optString("id") ?: "")
                put("source", SOURCE_IPWHO)
            }
            if (json.optString("query").isBlank()) error("ipwho.is returned no IP")
            return json
        } finally {
            client.close()
        }
    }

    /** 兜底 Cloudflare trace：HTTPS，无 key 无配额，只回 IP + 国家码，无 ISP、无城市。
     * 响应是纯文本（"ip=1.2.3.4\nloc=US\n..."），不是 JSON。 */
    private fun queryCloudflare(connected: Boolean): JSONObject {
        val client = newClient(connected)
        try {
            val response = client.newRequest().apply {
                setURL("https://www.cloudflare.com/cdn-cgi/trace")
            }.execute()
            val body = Util.getStringBox(response.contentString)
            val fields = body.lineSequence()
                .mapNotNull { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }.toMap()
            val ip = fields["ip"].orEmpty()
            if (ip.isBlank()) error("cloudflare trace returned no IP")
            val countryCode = fields["loc"].orEmpty()
            return JSONObject().apply {
                put("status", "success")
                put("query", ip)
                put("country", countryCode)
                put("countryCode", countryCode)
                put("regionName", "")
                put("city", "")
                put("isp", "")
                put("org", "")
                put("lat", 0.0)
                put("lon", 0.0)
                put("timezone", "")
                put("source", SOURCE_CLOUDFLARE)
            }
        } finally {
            client.close()
        }
    }
}
