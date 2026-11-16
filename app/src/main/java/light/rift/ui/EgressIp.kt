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
 * 出口 IP 查询（ip-api.com 免费接口）。NetworkFragment 的出口自检和
 * 主页底栏的出口 IP 常驻显示共用这里。
 *
 * 注意：ip-api 免费接口只支持 HTTP，HTTPS 会报 403（SSL 需付费版），
 * 所以这里必须用 http://，查的只是本机出口 IP 本身，无敏感内容。
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

    /** Blocking: call off the main thread. Null on any failure. */
    fun fetch(): EgressInfo? {
        return try {
            val json = queryJson()
            val ip = json.optString("query")
            if (ip.isBlank()) return null
            // city 是 ip-api 返回的英文城市名（如 Los Angeles），系统没有现成的
            // 城市名中译办法，所以状态卡里直接显示英文（用户 2026-11-12 定：英文也行）。
            EgressInfo(ip, json.optString("countryCode"), json.optString("city"))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Raw ip-api query. Throws on failure (mixed port not ready, bad
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
            if (connected) trySocks5(DataStore.mixedPort)
        }
        try {
            val response = client.newRequest().apply {
                setURL("http://ip-api.com/json")
            }.execute()
            val body = Util.getStringBox(response.contentString)
            val json = JSONObject(body)
            if (json.optString("status") != "success") {
                error(json.optString("message").ifBlank { "ip-api returned no IP" })
            }
            if (json.optString("query").isBlank()) error("ip-api returned no IP")
            return json
        } finally {
            client.close()
        }
    }
}
