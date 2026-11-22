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
 * 出口 IP 查询（ipwho.is 免费接口，HTTPS）。NetworkFragment 的出口自检和
 * 主页底栏的出口 IP 常驻显示共用这里。
 *
 * 之前用 ip-api.com 免费接口，只支持 HTTP（HTTPS 报 403，SSL 需付费版），
 * 出口 IP 与节点域名都走明文。ipwho.is 免费版支持 HTTPS，字段在这里转成
 * ip-api 的字段名，下游（状态卡、出口自检）不用改。
 * 入站走当前节点；未连接时就是直连。连接状态下先探一下 mixed 端口，
 * 探不通直接报错，绝不把直连 IP 误标为出口 IP。
 */
object EgressIp {

    data class EgressInfo(val ip: String, val countryCode: String, val city: String)

    /** ISO 3166-1 alpha-2 country code → flag emoji (regional indicators).
     * Blank or invalid code returns an empty string. Pure function, no I/O. */
    fun flagEmoji(countryCode: String): String {
        val code = countryCode.trim().uppercase()
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return ""
        val first = Character.toChars(0x1F1E6 + (code[0] - 'A'))
        val second = Character.toChars(0x1F1E6 + (code[1] - 'A'))
        return String(first + second)
    }

    /** Blocking: call off the main thread. Null on any failure.
     * 2026-11-22 -56: 失败重试一次——慢链子第一次常因 mixed 入站刚起、
     * 首包抖动而失败；两次都失败才返回 null。 */
    fun fetch(): EgressInfo? {
        repeat(2) { attempt ->
            try {
                val json = queryJson()
                val ip = json.optString("query")
                if (ip.isBlank()) return null
                // city 是 ipwho.is 返回的英文城市名（如 Los Angeles），系统没有现成的
                // 城市名中译办法，所以状态卡里直接显示英文（用户 2026-11-12 定：英文也行）。
                return EgressInfo(ip, json.optString("countryCode"), json.optString("city"))
            } catch (_: Exception) {
                if (attempt == 1) return null
            }
        }
        return null
    }

    /**
     * Raw ipwho.is query. Throws on failure (mixed port not ready, bad
     * status, empty IP). The caller decides the message.
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
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            keepAlive()
            // 2026-11-22 -56: 出口查询单次上限 10s；libcore 默认 30s，
            // 慢链子会让界面顶着旧 IP 等半天。失败由 fetch() 重试一次。
            setTimeout(10000)
            if (connected) {
                // 本地代理认证（0.2.29 -26）：mixed 入口开了 users 就得带账号密码。
                if (DataStore.localProxyAuthEnabled) {
                    trySocks5Auth(DataStore.mixedPort, DataStore.mixedInboundUser, DataStore.mixedInboundPass)
                } else {
                    trySocks5(DataStore.mixedPort)
                }
            }
        }
        try {
            val response = client.newRequest().apply {
                setURL("https://ipwho.is/")
            }.execute()
            val body = Util.getStringBox(response.contentString)
            val raw = JSONObject(body)
            if (!raw.optBoolean("success", false)) {
                error(raw.optString("message").ifBlank { "ipwho.is returned no IP" })
            }
            // ipwho.is 的字段名与 ip-api 不同，这里转成 ip-api 的字段名，
            // 下游（状态卡、出口自检）不用改。
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
            }
            if (json.optString("query").isBlank()) error("ipwho.is returned no IP")
            return json
        } finally {
            client.close()
        }
    }
}
