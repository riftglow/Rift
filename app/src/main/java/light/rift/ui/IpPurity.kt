package light.rift.ui

import light.rift.core.utils.Util
import libcore.Libcore
import org.json.JSONObject

/**
 * IP 纯净度（0.2.29 -26，第二批）：OwnBox LandingIpManager 的两步模型。
 * 第一步拿真实出口 IP（调用方先做出口自检，复用 EgressIp，不自己再查一遍）；
 * 第二步查质量，按 iplocate.io → ip-api.com → ipquery.io 的顺序，第一个答
 * 上来的为准，都失败就返回 null（调用方只显示 IP，不编数据）。
 * 等级（对齐 FlClash）：住宅/移动/商业 → 优（10）；机房 → 普通（45）；
 * Tor/滥用 → 风险（85）。注意“VPN/代理”标识本身不算风险——出口本来就是代理，
 * 只有 Tor、已知滥用、已知攻击者才判风险。
 */
object IpPurity {
    enum class Grade { GOOD, NORMAL, RISK }
    enum class Type { RESIDENTIAL, DATACENTER, MOBILE, BUSINESS, UNKNOWN }

    data class Result(
        val ip: String,
        val country: String,
        val city: String,
        val isp: String,
        val type: Type,
        val grade: Grade,
        val riskScore: Int,
        val source: String,
        val detail: String,
    )

    /** 查质量。ip 为空返回 null；三个源都失败也返回 null。 */
    fun check(ip: String): Result? {
        if (ip.isBlank()) return null
        return checkIplocate(ip) ?: checkIpApi(ip) ?: checkIpQuery(ip)
    }

    private fun fetchJson(url: String): JSONObject? {
        val client = Libcore.newHttpClient().apply { modernTLS() }
        return try {
            val response = client.newRequest().apply { setURL(url) }.execute()
            JSONObject(Util.getStringBox(response.contentString))
        } catch (_: Exception) {
            null
        } finally {
            client.close()
        }
    }

    private fun checkIplocate(ip: String): Result? {
        val json = fetchJson("https://iplocate.io/api/lookup/$ip") ?: return null
        val privacy = json.optJSONObject("privacy")
        val company = json.optJSONObject("company")
        val tor = privacy?.optBoolean("tor") == true
        val hosting = privacy?.optBoolean("hosting") == true ||
            company?.optString("type") == "hosting"
        val type = when (company?.optString("type")) {
            "hosting" -> Type.DATACENTER
            "business" -> Type.BUSINESS
            "isp" -> Type.RESIDENTIAL
            else -> if (hosting) Type.DATACENTER else Type.UNKNOWN
        }
        val (grade, score, detail) = when {
            tor -> Triple(Grade.RISK, 85, "Tor")
            hosting -> Triple(Grade.NORMAL, 45, "hosting")
            else -> Triple(Grade.GOOD, 10, company?.optString("type").orEmpty())
        }
        return Result(
            ip = ip,
            country = json.optString("country"),
            city = json.optString("city"),
            isp = json.optString("org"),
            type = type,
            grade = grade,
            riskScore = score,
            source = "iplocate.io",
            detail = detail,
        )
    }

    private fun checkIpApi(ip: String): Result? {
        val json = fetchJson("http://ip-api.com/json/$ip") ?: return null
        if (json.optString("status") != "success") return null
        val hosting = json.optBoolean("hosting")
        val org = json.optString("org")
        val type = when {
            hosting -> Type.DATACENTER
            "mobile" in org.lowercase() -> Type.MOBILE
            else -> Type.RESIDENTIAL
        }
        val (grade, score, detail) = if (hosting) {
            Triple(Grade.NORMAL, 45, "hosting")
        } else {
            Triple(Grade.GOOD, 10, "")
        }
        return Result(
            ip = ip,
            country = json.optString("country"),
            city = json.optString("city"),
            isp = json.optString("isp"),
            type = type,
            grade = grade,
            riskScore = score,
            source = "ip-api.com",
            detail = detail,
        )
    }

    private fun checkIpQuery(ip: String): Result? {
        val json = fetchJson("https://api.ipquery.io/$ip") ?: return null
        val risk = json.optJSONObject("risk") ?: return null
        val isp = json.optJSONObject("isp")
        val location = json.optJSONObject("location")
        val tor = risk.optBoolean("is_tor")
        val abuser = risk.optBoolean("is_known_abuser") || risk.optBoolean("is_known_attacker") ||
            risk.optBoolean("is_threat")
        val datacenter = risk.optBoolean("is_datacenter")
        val type = when {
            datacenter -> Type.DATACENTER
            risk.optBoolean("is_mobile") -> Type.MOBILE
            else -> Type.RESIDENTIAL
        }
        val (grade, score, detail) = when {
            tor || abuser -> Triple(Grade.RISK, 85, if (tor) "Tor" else "abuser")
            datacenter -> Triple(Grade.NORMAL, 45, "datacenter")
            else -> Triple(Grade.GOOD, risk.optInt("risk_score", 10), "")
        }
        return Result(
            ip = ip,
            country = location?.optString("country").orEmpty(),
            city = location?.optString("city").orEmpty(),
            isp = isp?.optString("isp").orEmpty(),
            type = type,
            grade = grade,
            riskScore = score,
            source = "ipquery.io",
            detail = detail,
        )
    }
}
