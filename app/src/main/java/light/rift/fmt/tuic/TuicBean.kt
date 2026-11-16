package light.rift.fmt.tuic

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import light.rift.fmt.AbstractBean
import light.rift.fmt.KryoConverters

class TuicBean : AbstractBean() {

    var token: String = ""
    var caText: String = ""
    var udpRelayMode: String = "native"
    var congestionController: String = "cubic"
    var alpn: String = ""
    var disableSNI: Boolean = false
    var reduceRTT: Boolean = false
    var mtu: Int = 1400
    var sni: String = ""

    // TUIC zep

    var fastConnect: Boolean = false
    var allowInsecure: Boolean = false

    // TUIC v5

    var customJSON: String = ""
    var protocolVersion: Int = 5
    var uuid: String = ""

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (token == null) token = ""
        if (caText == null) caText = ""
        if (udpRelayMode == null) udpRelayMode = "native"
        if (congestionController == null) congestionController = "cubic"
        if (alpn == null) alpn = ""
        if (disableSNI == null) disableSNI = false
        if (reduceRTT == null) reduceRTT = false
        if (mtu == null) mtu = 1400
        if (sni == null) sni = ""
        if (fastConnect == null) fastConnect = false
        if (allowInsecure == null) allowInsecure = false
        if (customJSON == null) customJSON = ""
        if (protocolVersion == null) protocolVersion = 5
        if (uuid == null) uuid = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(2)
        super.serialize(output)
        output.writeString(token)
        output.writeString(caText)
        output.writeString(udpRelayMode)
        output.writeString(congestionController)
        output.writeString(alpn)
        output.writeBoolean(disableSNI!!)
        output.writeBoolean(reduceRTT!!)
        output.writeInt(mtu!!)
        output.writeString(sni)
        output.writeBoolean(fastConnect!!)
        output.writeBoolean(allowInsecure!!)
        output.writeString(customJSON)
        output.writeInt(protocolVersion!!)
        output.writeString(uuid)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        token = input.readString()
        caText = input.readString()
        udpRelayMode = input.readString()
        congestionController = input.readString()
        alpn = input.readString()
        disableSNI = input.readBoolean()
        reduceRTT = input.readBoolean()
        mtu = input.readInt()
        sni = input.readString()
        if (version >= 1) {
            fastConnect = input.readBoolean()
            allowInsecure = input.readBoolean()
        }
        if (version >= 2) {
            customJSON = input.readString()
            protocolVersion = input.readInt()
            uuid = input.readString()
        } else {
            protocolVersion = 4
        }
    }

    override fun canTCPing(): Boolean {
        return false
    }

    override fun clone(): TuicBean {
        return KryoConverters.deserialize(TuicBean(), KryoConverters.serialize(this))
    }

    companion object {
        @JvmField
        val CREATOR = object : CREATOR<TuicBean>() {
            override fun newInstance(): TuicBean {
                return TuicBean()
            }

            override fun newArray(size: Int): Array<TuicBean?> {
                return arrayOfNulls(size)
            }
        }
    }
}
