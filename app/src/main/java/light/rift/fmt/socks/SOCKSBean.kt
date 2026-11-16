package light.rift.fmt.socks

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import light.rift.fmt.AbstractBean
import light.rift.fmt.KryoConverters

class SOCKSBean : AbstractBean() {

    var protocol: Int = PROTOCOL_SOCKS5

    var sUoT: Boolean = false

    fun protocolVersion(): Int {
        when (protocol) {
            0, 1 -> return 4
            else -> return 5
        }
    }

    fun protocolName(): String {
        when (protocol) {
            0 -> return "SOCKS4"
            1 -> return "SOCKS4A"
            else -> return "SOCKS5"
        }
    }

    fun protocolVersionName(): String {
        when (protocol) {
            0 -> return "4"
            1 -> return "4a"
            else -> return "5"
        }
    }

    var username: String = ""
    var password: String = ""

    override fun network(): String {
        if (protocol!! < PROTOCOL_SOCKS5) return "tcp"
        return super.network()
    }

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()

        if (protocol == null) protocol = PROTOCOL_SOCKS5
        if (username == null) username = ""
        if (password == null) password = ""
        if (sUoT == null) sUoT = false
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(2)
        super.serialize(output)
        output.writeInt(protocol!!)
        output.writeString(username)
        output.writeString(password)
        output.writeBoolean(sUoT!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        if (version >= 1) {
            protocol = input.readInt()
        }
        username = input.readString()
        password = input.readString()
        if (version >= 2) {
            sUoT = input.readBoolean()
        }
    }

    override fun clone(): SOCKSBean {
        return KryoConverters.deserialize(SOCKSBean(), KryoConverters.serialize(this))
    }

    companion object {
        const val PROTOCOL_SOCKS4 = 0
        const val PROTOCOL_SOCKS4A = 1
        const val PROTOCOL_SOCKS5 = 2

        @JvmField
        val CREATOR = object : CREATOR<SOCKSBean>() {
            override fun newInstance(): SOCKSBean {
                return SOCKSBean()
            }

            override fun newArray(size: Int): Array<SOCKSBean?> {
                return arrayOfNulls(size)
            }
        }
    }
}
