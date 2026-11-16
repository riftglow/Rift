package light.rift.fmt.trojan

import light.rift.fmt.v2ray.parseDuckSoft
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseTrojan(server: String): TrojanBean {
    val link = server.replace("trojan://", "https://").toHttpUrlOrNull()
        ?: error("invalid trojan link $server")

    val bean = TrojanBean()
    bean.parseDuckSoft(link)
    link.queryParameter("allowInsecure")?.let { flag ->
        if (flag == "1" || flag == "true") bean.allowInsecure = true
    }
    link.queryParameter("peer")?.let { peer ->
        if (peer.isNotBlank()) bean.sni = peer
    }
    return bean
}
