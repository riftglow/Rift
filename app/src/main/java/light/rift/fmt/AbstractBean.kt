package light.rift.fmt

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import light.rift.core.utils.JavaUtil
import light.rift.ktx.unwrapIPV6Host
import light.rift.ktx.wrapIPV6Host

abstract class AbstractBean : Serializable() {

    var serverAddress: String = "127.0.0.1"
    var serverPort: Int = 0

    var name: String = ""

    //

    var customOutboundJson: String = ""
    var customConfigJson: String = ""

    //
    @Transient
    var finalAddress: String = ""
    @Transient
    var finalPort: Int = 0

    open fun displayName(): String {
        if (JavaUtil.isNotBlank(name)) {
            return name!!
        } else {
            return displayAddress()
        }
    }

    open fun displayAddress(): String {
        return serverAddress!!.wrapIPV6Host() + ":" + serverPort
    }

    open fun network(): String {
        return "tcp,udp"
    }

    open fun canICMPing(): Boolean {
        return true
    }

    open fun canTCPing(): Boolean {
        return true
    }

    open fun canMapping(): Boolean {
        return true
    }

    override fun initializeDefaultValues() {
        if (JavaUtil.isNullOrBlank(serverAddress)) {
            serverAddress = "127.0.0.1"
        } else if (serverAddress!!.startsWith("[") && serverAddress!!.endsWith("]")) {
            serverAddress = serverAddress!!.unwrapIPV6Host()
        }
        if (serverPort == 0) {
            serverPort = 1080
        }
        if (name == null) name = ""

        finalAddress = serverAddress
        finalPort = serverPort!!

        if (customOutboundJson == null) customOutboundJson = ""
        if (customConfigJson == null) customConfigJson = ""
    }


    @Transient
    private var serializeWithoutName: Boolean = false

    override fun serializeToBuffer(output: ByteBufferOutput) {
        serialize(output)

        output.writeInt(1)
        if (!serializeWithoutName) {
            output.writeString(name)
        }
        output.writeString(customOutboundJson)
        output.writeString(customConfigJson)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        deserialize(input)

        val extraVersion = input.readInt()

        name = input.readString()
        customOutboundJson = input.readString()
        customConfigJson = input.readString()
    }

    open fun serialize(output: ByteBufferOutput) {
        output.writeString(serverAddress)
        output.writeInt(serverPort!!)
    }

    open fun deserialize(input: ByteBufferInput) {
        serverAddress = input.readString()
        serverPort = input.readInt()
    }

    public abstract fun clone(): AbstractBean

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        try {
            serializeWithoutName = true
            (other as AbstractBean).serializeWithoutName = true
            return KryoConverters.serialize(this).contentEquals(KryoConverters.serialize(other as AbstractBean))
        } finally {
            serializeWithoutName = false
            (other as AbstractBean).serializeWithoutName = false
        }
    }

    override fun hashCode(): Int {
        try {
            serializeWithoutName = true
            return KryoConverters.serialize(this).contentHashCode()
        } finally {
            serializeWithoutName = false
        }
    }

    override fun toString(): String {
        return javaClass.simpleName + " " + JavaUtil.gson.toJson(this)
    }
}
