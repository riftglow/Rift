package light.rift.fmt

import light.rift.database.ProxyEntity

object TypeMap : HashMap<String, Int>() {
    init {
        arrayOf(
            "socks" to ProxyEntity.TYPE_SOCKS,
            "http" to ProxyEntity.TYPE_HTTP,
            "ss" to ProxyEntity.TYPE_SS,
            "vmess" to ProxyEntity.TYPE_VMESS,
            "trojan" to ProxyEntity.TYPE_TROJAN,
            "hysteria" to ProxyEntity.TYPE_HYSTERIA,
            "ssh" to ProxyEntity.TYPE_SSH,
            "wg" to ProxyEntity.TYPE_WG,
            "tuic" to ProxyEntity.TYPE_TUIC,
            "anytls" to ProxyEntity.TYPE_ANYTLS,
            "config" to ProxyEntity.TYPE_CONFIG
        ).forEach { (scheme, code) -> put(scheme, code) }
    }

    val reversed = HashMap<Int, String>()

    init {
        for ((scheme, code) in this) {
            reversed[code] = scheme
        }
    }

}
