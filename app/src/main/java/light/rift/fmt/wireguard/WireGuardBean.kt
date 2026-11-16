package light.rift.fmt.wireguard

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import light.rift.fmt.AbstractBean
import light.rift.fmt.KryoConverters

class WireGuardBean : AbstractBean() {

    var localAddress: String = ""
    var privateKey: String = ""
    var peerPublicKey: String = ""
    var peerPreSharedKey: String = ""
    var mtu: Int = 1420
    var reserved: String = ""

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (localAddress == null) localAddress = ""
        if (privateKey == null) privateKey = ""
        if (peerPublicKey == null) peerPublicKey = ""
        if (peerPreSharedKey == null) peerPreSharedKey = ""
        if (mtu == null) mtu = 1420
        if (reserved == null) reserved = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(2)
        super.serialize(output)
        output.writeString(localAddress)
        output.writeString(privateKey)
        output.writeString(peerPublicKey)
        output.writeString(peerPreSharedKey)
        output.writeInt(mtu!!)
        output.writeString(reserved)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        localAddress = input.readString()
        privateKey = input.readString()
        peerPublicKey = input.readString()
        peerPreSharedKey = input.readString()
        mtu = input.readInt()
        reserved = input.readString()
    }

    override fun canTCPing(): Boolean {
        return false
    }

    override fun clone(): WireGuardBean {
        return KryoConverters.deserialize(WireGuardBean(), KryoConverters.serialize(this))
    }

    companion object {
        @JvmField
        val CREATOR = object : CREATOR<WireGuardBean>() {
            override fun newInstance(): WireGuardBean {
                return WireGuardBean()
            }

            override fun newArray(size: Int): Array<WireGuardBean?> {
                return arrayOfNulls(size)
            }
        }
    }
}
