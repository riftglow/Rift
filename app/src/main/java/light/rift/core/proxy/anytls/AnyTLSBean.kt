package light.rift.core.proxy.anytls

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import light.rift.fmt.AbstractBean
import light.rift.fmt.KryoConverters

class AnyTLSBean : AbstractBean() {

    var password: String = ""
    var sni: String = ""
    var alpn: String = ""
    var certificates: String = ""
    var utlsFingerprint: String = ""
    var allowInsecure: Boolean = false
    // In sing-box, this seemed can be used with REALITY.
    // But even mihomo appended many options, it still not provide REALITY.
    // https://github.com/anytls/anytls-go/blob/4636d90462fa21a510420512d7706a9acf69c7b9/docs/faq.md?plain=1#L25-L37

    var echConfig: String = ""

    // 会话复用（2026-11-10，规划第 14 项）：秒 / 个，0 = 未设，
    // 未设时用全局设置，全未设则不发字段（走核心默认）。
    var idleSessionCheckInterval: Int = 0
    var idleSessionTimeout: Int = 0
    var minIdleSession: Int = 0

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (password == null) password = ""
        if (sni == null) sni = ""
        if (alpn == null) alpn = ""
        if (certificates == null) certificates = ""
        if (utlsFingerprint == null) utlsFingerprint = ""
        if (allowInsecure == null) allowInsecure = false
        if (echConfig == null) echConfig = ""
        if (idleSessionCheckInterval == null) idleSessionCheckInterval = 0
        if (idleSessionTimeout == null) idleSessionTimeout = 0
        if (minIdleSession == null) minIdleSession = 0
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(1)
        super.serialize(output)
        output.writeString(password)
        output.writeString(sni)
        output.writeString(alpn)
        output.writeString(certificates)
        output.writeString(utlsFingerprint)
        output.writeBoolean(allowInsecure!!)
        output.writeString(echConfig)
        output.writeInt(idleSessionCheckInterval!!)
        output.writeInt(idleSessionTimeout!!)
        output.writeInt(minIdleSession!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        password = input.readString()
        sni = input.readString()
        alpn = input.readString()
        certificates = input.readString()
        utlsFingerprint = input.readString()
        allowInsecure = input.readBoolean()
        echConfig = input.readString()
        if (version >= 1) {
            idleSessionCheckInterval = input.readInt()
            idleSessionTimeout = input.readInt()
            minIdleSession = input.readInt()
        } else {
            idleSessionCheckInterval = 0
            idleSessionTimeout = 0
            minIdleSession = 0
        }
    }

    override fun clone(): AnyTLSBean {
        return KryoConverters.deserialize(AnyTLSBean(), KryoConverters.serialize(this))
    }

    companion object {
        @JvmField
        val CREATOR = object : CREATOR<AnyTLSBean>() {
            override fun newInstance(): AnyTLSBean {
                return AnyTLSBean()
            }

            override fun newArray(size: Int): Array<AnyTLSBean?> {
                return arrayOfNulls(size)
            }
        }
    }
}
