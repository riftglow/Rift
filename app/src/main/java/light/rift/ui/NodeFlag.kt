package light.rift.ui

import light.rift.database.DataStore
import light.rift.core.utils.Util
import libcore.Libcore
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 节点卡国旗（2026-11-13，0.2.23 -10）：节点名前放一面国旗（emoji，复用
 * EgressIp.flagEmoji 的区域指示符算法）。国家来源两路：
 * 1. 节点名解析（同步、离线）：名字里带国家/城市关键词（美国/香港/圣何塞……）
 *    直接出国家码，零网络、最快；
 * 2. 查服务器 IP 归属（异步、带缓存）：名字里没地名时（比如主节点
 *    “Vmiss转 Cloudcone-XHTTP”），拿 serverAddress 问 ipwho.is，拿到国家码后
 *    按 serverAddress 存 DataStore.nodeFlagJson，下次直接读缓存，不再联网。
 * 查不到（离线、解析失败、ipwho.is 失败）就不显示旗，不瞎猜。
 * ipwho.is 免费版每月 1 万次；只有名字解析失败的节点才查，且同一
 * server 只在途查一次，日常就一两个请求，不会撞限速。
 */
object NodeFlag {

    // 名字关键词（小写包含匹配）→ ISO 国家码。长词/专名在前，避免短词先命中。
    // 只收常见国家/地区与美国大城市；没收到的走第 2 路查 IP。
    private val NAME_MAP: List<Pair<String, String>> = listOf(
        // 地区（先于国家，避免被别的词抢）
        "香港" to "HK", "澳门" to "MO", "台湾" to "TW", "台北" to "TW",
        // 国家（中文）
        "美国" to "US", "日本" to "JP", "新加坡" to "SG", "韩国" to "KR",
        "英国" to "GB", "德国" to "DE", "法国" to "FR", "加拿大" to "CA",
        "澳大利亚" to "AU", "澳洲" to "AU", "泰国" to "TH", "越南" to "VN",
        "马来西亚" to "MY", "印度" to "IN", "俄罗斯" to "RU", "土耳其" to "TR",
        "荷兰" to "NL", "瑞士" to "CH", "瑞典" to "SE", "挪威" to "NO",
        "芬兰" to "FI", "意大利" to "IT", "西班牙" to "ES", "巴西" to "BR",
        "墨西哥" to "MX", "南非" to "ZA", "以色列" to "IL", "阿联酋" to "AE",
        "印尼" to "ID", "菲律宾" to "PH", "新西兰" to "NZ", "爱尔兰" to "IE",
        "波兰" to "PL", "奥地利" to "AT", "希腊" to "GR", "葡萄牙" to "PT",
        "丹麦" to "DK", "冰岛" to "IS", "乌克兰" to "UA", "阿根廷" to "AR",
        "智利" to "CL", "埃及" to "EG", "沙特" to "SA", "卡塔尔" to "QA",
        // 美国城市（中文）
        "圣何塞" to "US", "洛杉矶" to "US", "纽约" to "US", "西雅图" to "US",
        "芝加哥" to "US", "达拉斯" to "US", "迈阿密" to "US", "旧金山" to "US",
        "拉斯维加斯" to "US", "休斯顿" to "US", "亚特兰大" to "US",
        "波士顿" to "US", "圣地亚哥" to "US", "凤凰城" to "US", "费城" to "US",
        // 英文（小写）
        "hong kong" to "HK", "hongkong" to "HK", "macao" to "MO", "macau" to "MO",
        "taiwan" to "TW", "taipei" to "TW",
        "united states" to "US", "america" to "US", "usa" to "US",
        "japan" to "JP", "tokyo" to "JP", "osaka" to "JP",
        "singapore" to "SG", "korea" to "KR", "seoul" to "KR",
        "britain" to "GB", "england" to "GB", "london" to "GB",
        "germany" to "DE", "frankfurt" to "DE", "france" to "FR", "paris" to "FR",
        "canada" to "CA", "toronto" to "CA", "australia" to "AU", "sydney" to "AU",
        "thailand" to "TH", "bangkok" to "TH", "vietnam" to "VN",
        "malaysia" to "MY", "india" to "IN", "mumbai" to "IN",
        "netherlands" to "NL", "amsterdam" to "NL", "switzerland" to "CH",
        "sweden" to "SE", "norway" to "NO", "finland" to "FI",
        "italy" to "IT", "spain" to "ES", "brazil" to "BR", "mexico" to "MX",
        "los angeles" to "US", "san jose" to "US", "new york" to "US",
        "seattle" to "US", "chicago" to "US", "dallas" to "US",
        "san francisco" to "US", "las vegas" to "US", "miami" to "US",
        "houston" to "US", "atlanta" to "US", "boston" to "US",
        // “ LA”= 洛杉矶缩写（节点名里常见，如 AetherCloud LA）；带前导空格，
        // 避免误伤别的词（Malaysia 之类不含“ LA”）。
        " la" to "US",
    )

    fun countryCodeForName(name: String): String? {
        val lower = name.lowercase()
        for ((keyword, code) in NAME_MAP) {
            if (lower.contains(keyword)) return code
        }
        return null
    }

    // ---- 缓存：serverAddress -> 国家码（DataStore.nodeFlagJson） ----
    private fun readCache(): JSONObject {
        return try {
            val raw = DataStore.nodeFlagJson
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    fun cachedCountryCode(serverAddress: String): String? {
        val code = readCache().optString(serverAddress)
        return code.ifBlank { null }?.takeIf { it.length == 2 }
    }

    fun cacheCountryCode(serverAddress: String, countryCode: String) {
        try {
            val json = readCache()
            json.put(serverAddress, countryCode.uppercase())
            DataStore.nodeFlagJson = json.toString()
        } catch (_: Exception) {
            // 缓存写失败不影响显示，下次再查。
        }
    }

    // ---- 在途去重：同一 server 只查一次 ----
    private val fetching = ConcurrentHashMap.newKeySet<String>()

    fun markFetching(serverAddress: String): Boolean = fetching.add(serverAddress)
    fun unmarkFetching(serverAddress: String) {
        fetching.remove(serverAddress)
    }

    /**
     * 查 serverAddress 的归属国家码。Blocking：必须在后台线程调。
     * ipwho.is 只接受 IP，不接受域名，所以域名先在本地解析成 IP 再查
     * （解析走系统 DNS，只为拿到 IP，归属问的仍是节点 IP 本身）。
     * 问的是这个 IP/域名的归属，不是本机出口，所以直连、不走 VPN。
     * Null = 查不到（离线、域名解析不了、ipwho.is 失败/限速）。
     */
    fun fetchCountryCode(serverAddress: String): String? {
        val host = serverAddress.trim()
        if (host.isBlank()) return null
        // 域名先解析成 IP；已经是 IP 的直接返回原值。
        val ip = try {
            InetAddress.getByName(host).hostAddress ?: return null
        } catch (_: Exception) {
            return null
        }
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            keepAlive()
        }
        return try {
            val response = client.newRequest().apply {
                setURL("https://ipwho.is/$ip")
            }.execute()
            val body = Util.getStringBox(response.contentString)
            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) return null
            json.optString("country_code").ifBlank { null }
        } catch (_: Exception) {
            null
        } finally {
            client.close()
        }
    }
}
