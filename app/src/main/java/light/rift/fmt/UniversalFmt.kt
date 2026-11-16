package light.rift.fmt

import light.rift.database.ProxyEntity
import light.rift.database.ProxyGroup
import light.rift.core.utils.Util

fun parseUniversal(link: String): AbstractBean {
    val entity = if (link.contains("?")) {
        val type = link.substringAfter("sn://").substringBefore("?")
        ProxyEntity(type = TypeMap[type] ?: error("Type $type not found")).apply {
            putByteArray(Util.zlibDecompress(Util.b64Decode(link.substringAfter("?"))))
        }
    } else {
        val type = link.substringAfter("sn://").substringBefore(":")
        ProxyEntity(type = TypeMap[type] ?: error("Type $type not found")).apply {
            putByteArray(Util.b64Decode(link.substringAfter(":").substringAfter(":")))
        }
    }
    return entity.requireBean()
}

fun AbstractBean.toUniversalLink(): String {
    // buildString 的 lambda 接收者是 StringBuilder，会遮住外层的 AbstractBean，
    // 故先把 bean 存到局部变量再进 buildString。
    val bean = this
    return buildString {
        append("sn://")
        append(TypeMap.reversed[ProxyEntity().putBean(bean).type])
        append("?")
        append(Util.b64EncodeUrlSafe(Util.zlibCompress(KryoConverters.serialize(bean), 9)))
    }
}

fun ProxyGroup.toUniversalLink(): String {
    export = true
    val link = "sn://subscription?" +
        Util.b64EncodeUrlSafe(Util.zlibCompress(KryoConverters.serialize(this), 9))
    export = false
    return link
}
