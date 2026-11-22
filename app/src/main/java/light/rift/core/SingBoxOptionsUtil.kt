package light.rift.core

import android.widget.Toast
import light.rift.RiftApp
import light.rift.database.DataStore
import light.rift.ktx.Logs
import light.rift.core.SingBoxOptions.RuleSet
import java.io.File

// Rule-set tags (2026-10-31 migration): the DB still stores user rules in
// the old "geosite:cn" / "geoip:cn" spelling, and the rule list the user
// sees does not change. Only the generated sing-box config translates them
// to official rule-set tags: "geosite:cn" -> "geosite-cn",
// "geosite:category-ads-all" -> "geosite-category-ads-all", and generally
// "<kind>:<code>" -> "<kind>-<code>". The generated local rule-set then
// points at the matching .srs file in the external assets directory
// (bundled at install, or downloaded from the routing-assets page).
fun ruleSetTag(legacyOrTag: String): String {
    val parts = legacyOrTag.split(":", limit = 2)
    return if (parts.size == 2) "${parts[0]}-${parts[1]}" else legacyOrTag
}

fun ruleSetPath(tag: String): String =
    File(RiftApp.application.externalAssets, "$tag.srs").absolutePath

object SingBoxOptionsUtil {

    fun domainStrategy(tag: String): String {
        fun auto2(key: String, newS: String): String {
            // 键缺失时按 "auto" 算：之前缺失读出来是 ""，一旦用户打开过
            // 设置页框架就把 XML 默认值 "auto" 持久化，同一台机行为取决
            // 于是否点开过该页（server 一支缺失得 ""、打开过得 "prefer_ipv4"）。
            return (DataStore.configurationStore.getString(key) ?: "auto").replace("auto", newS)
        }
        return when (tag) {
            "dns-remote" -> {
                auto2("domain_strategy_for_remote", "")
            }

            "dns-direct" -> {
                auto2("domain_strategy_for_direct", "")
            }

            // server
            else -> {
                auto2("domain_strategy_for_server", "prefer_ipv4")
            }
        }
    }

}

fun SingBoxOptions.DNSRule_DefaultOptions.makeSingBoxRule(list: List<String>) {
    // S-C1 同款保护：只在为空时新建列表，跨调用保留已填内容。
    // 目前每个对象只调一次，改动无行为变化，只防以后二次调用清空。
    if (rule_set == null) rule_set = mutableListOf()
    if (domain == null) domain = mutableListOf()
    if (domain_suffix == null) domain_suffix = mutableListOf()
    if (domain_regex == null) domain_regex = mutableListOf()
    if (domain_keyword == null) domain_keyword = mutableListOf()
    list.forEach {
        if (it.startsWith("geosite:")) {
            rule_set?.plusAssign(ruleSetTag(it))
        } else if (it.startsWith("full:")) {
            domain?.plusAssign(it.removePrefix("full:").lowercase())
        } else if (it.startsWith("domain:")) {
            domain_suffix?.plusAssign(it.removePrefix("domain:").lowercase())
        } else if (it.startsWith("regexp:")) {
            domain_regex?.plusAssign(it.removePrefix("regexp:").lowercase())
        } else if (it.startsWith("keyword:")) {
            domain_keyword?.plusAssign(it.removePrefix("keyword:").lowercase())
        } else {
            domain_suffix?.plusAssign(it.lowercase())
        }
    }
    rule_set?.removeIf { it.isNullOrBlank() }
    domain?.removeIf { it.isNullOrBlank() }
    domain_suffix?.removeIf { it.isNullOrBlank() }
    domain_regex?.removeIf { it.isNullOrBlank() }
    domain_keyword?.removeIf { it.isNullOrBlank() }
    if (rule_set?.isEmpty() == true) rule_set = null
    if (domain?.isEmpty() == true) domain = null
    if (domain_suffix?.isEmpty() == true) domain_suffix = null
    if (domain_regex?.isEmpty() == true) domain_regex = null
    if (domain_keyword?.isEmpty() == true) domain_keyword = null
}

fun SingBoxOptions.DNSRule_DefaultOptions.checkEmpty(): Boolean {
    if (rule_set?.isNotEmpty() == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    if (package_name?.isNotEmpty() == true) return false
    // 2026-11-07 实测回退 S-C2 的 DNS 部分：server/action 绝不能算匹配
    // 条件。DNS 规则由路由规则镜像生成，server 是动作、不是匹配项；
    // 一条只有 server 的 DNS 规则在内核里是全匹配（sing-box 1.15
    // route/rule/rule_abstract.go：allItems 为空时 Match 直接返回 true）。
    // IP 专属路由规则（如 geoip-cn 直连、QUIC 屏蔽）本就没有 DNS 作用
    // 域，镜像出的 DNS 规则除 server 外一片空白；S-C2 把 server 计入
    // 后，这类规则成了排在 FakeDNS 之前的全匹配规则，全部 DNS 查询被
    // 截走或拒绝，浏览器报 ERR_NAME_NOT_RESOLVED，只有缓存的 FakeIP
    // 连接还能走（延时正常、能上网的全是缓存）。故只认匹配项，server /
    // action / inbound / query_type / outbound 一律不算；无匹配项的 DNS
    // 规则直接丢弃（与 0.2.9 行为一致），TUN 查询由内置 FakeDNS 规则兜底。
    return true
}

fun generateRuleSet(ruleSetString: List<String>, ruleSet: MutableList<RuleSet>) {
    ruleSetString.forEach {
        // Input is normally already an official tag (makeSingBoxRule maps
        // it above); mapping again is a no-op for official tags and keeps
        // any legacy "kind:code" input working too.
        val tag = ruleSetTag(it)
        if (tag.startsWith("geoip-") || tag.startsWith("geosite-")) {
            // A1: an empty code ("geosite:" -> "geosite-") is not a
            // rule-set, and a rule-set whose .srs file does not exist
            // must not be emitted either. Skipping is deliberately
            // noisy (log + toast) rather than silent: a silently
            // dropped definition would look like "no rules" at runtime.
            // Note: this only drops the *definition*; a rule that still
            // references the tag fails loudly in the kernel instead of
            // routing silently — that loud failure is the safe side.
            val code = tag.substringAfter("-", "")
            if (code.isBlank()) {
                Logs.w("generateRuleSet: skipping empty rule-set tag from '$it'")
                return@forEach
            }
            val path = ruleSetPath(tag)
            if (!File(path).isFile) {
                Logs.w("generateRuleSet: skipping rule-set '$tag', missing file $path; update it from the routing-assets page")
                try {
                    Toast.makeText(
                        RiftApp.application,
                        "Rule-set $tag is missing its .srs file; update it from the routing-assets page",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Throwable) {
                    // Toast is best-effort here; the log above is the record.
                }
                return@forEach
            }
            ruleSet.add(RuleSet().apply {
                type = "local"
                this.tag = tag
                format = "binary"
                this.path = path
            })
        }
    }
}

fun SingBoxOptions.Rule_DefaultOptions.makeSingBoxRule(list: List<String>, isIP: Boolean) {
    // S-C1：同一条规则含域名又含 IP 时，ConfigBuilder 会调两次
    // makeSingBoxRule（域名 pass、IP pass）。旧代码每次无条件新建
    // 列表，第二次调用把第一次填好的 rule_set（geosite）清空。
    // 改为只在为空时新建，两次调用合并累积；纯域名、纯 IP、混合
    // 三类都不再丢 rule_set。
    if (isIP) {
        if (ip_cidr == null) ip_cidr = mutableListOf()
        if (rule_set == null) rule_set = mutableListOf()
    } else {
        if (rule_set == null) rule_set = mutableListOf()
        if (domain == null) domain = mutableListOf()
        if (domain_suffix == null) domain_suffix = mutableListOf()
        if (domain_regex == null) domain_regex = mutableListOf()
        if (domain_keyword == null) domain_keyword = mutableListOf()
    }
    list.forEach {
        if (isIP) {
            if (it.startsWith("geoip:")) {
                if (it == "geoip:private") {
                    ip_is_private = true
                } else {
                    rule_set?.plusAssign(ruleSetTag(it))
                }
            } else {
                ip_cidr?.plusAssign(it)
            }
            return@forEach
        }
        if (it.startsWith("geosite:")) {
            rule_set?.plusAssign(ruleSetTag(it))
        } else if (it.startsWith("full:")) {
            domain?.plusAssign(it.removePrefix("full:").lowercase())
        } else if (it.startsWith("domain:")) {
            domain_suffix?.plusAssign(it.removePrefix("domain:").lowercase())
        } else if (it.startsWith("regexp:")) {
            domain_regex?.plusAssign(it.removePrefix("regexp:").lowercase())
        } else if (it.startsWith("keyword:")) {
            domain_keyword?.plusAssign(it.removePrefix("keyword:").lowercase())
        } else {
            domain_suffix?.plusAssign(it.lowercase())
        }
    }
    ip_cidr?.removeIf { it.isNullOrBlank() }
    rule_set?.removeIf { it.isNullOrBlank() }
    domain?.removeIf { it.isNullOrBlank() }
    domain_suffix?.removeIf { it.isNullOrBlank() }
    domain_regex?.removeIf { it.isNullOrBlank() }
    domain_keyword?.removeIf { it.isNullOrBlank() }
    if (ip_cidr?.isEmpty() == true) ip_cidr = null
    // S-C1 补漏：旧代码漏了 rule_set 的置空（DNS 版有），空列表会原样
    // 序列化成 "rule_set": []；geoip:private 之类不产生 rule_set 条目的
    // 规则此前会带上这个空列表。
    if (rule_set?.isEmpty() == true) rule_set = null
    if (domain?.isEmpty() == true) domain = null
    if (domain_suffix?.isEmpty() == true) domain_suffix = null
    if (domain_regex?.isEmpty() == true) domain_regex = null
    if (domain_keyword?.isEmpty() == true) domain_keyword = null
}

fun SingBoxOptions.Rule_DefaultOptions.checkEmpty(): Boolean {
    if (ip_is_private == true) return false
    if (ip_cidr?.isNotEmpty() == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (rule_set?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    if (package_name?.isNotEmpty() == true) return false
    //
    if (port?.isNotEmpty() == true) return false
    if (port_range?.isNotEmpty() == true) return false
    if (source_ip_cidr?.isNotEmpty() == true) return false
    // S-C2：旧代码漏了 network / protocol / source_port（及同组的
    // source_port_range），只用这些字段的规则会被当成空规则静默丢弃。
    if (network?.isNotEmpty() == true) return false
    if (protocol?.isNotEmpty() == true) return false
    if (source_port?.isNotEmpty() == true) return false
    if (source_port_range?.isNotEmpty() == true) return false
    //
    if (!_hack_custom_config.isNullOrBlank()) return false
    return true
}
