package light.rift.database

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import light.rift.fmt.Serializable

class SubscriptionBean : Serializable() {

    var type: Int = 0
    var link: String = ""
    var token: String = ""
    var forceResolve: Boolean = false
    var deduplication: Boolean = false
    var updateWhenConnectedOnly: Boolean = false
    var customUserAgent: String = ""
    var autoUpdate: Boolean = false
    var autoUpdateDelay: Int = 1440
    var lastUpdated: Int = 0

    // SIP008

    var bytesUsed: Long = 0L
    var bytesRemaining: Long = 0L

    // Open Online Config

    var username: String = ""
    var expiryDate: Int = 0
    var protocols: List<String> = ArrayList()


    // https://github.com/crossutility/Quantumult/blob/master/extra-subscription-feature.md

    var subscriptionUserinfo: String = ""

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(1)

        output.writeInt(type!!)

        output.writeString(link)

        output.writeBoolean(forceResolve!!)
        output.writeBoolean(deduplication!!)
        output.writeBoolean(updateWhenConnectedOnly!!)
        output.writeString(customUserAgent)
        output.writeBoolean(autoUpdate!!)
        output.writeInt(autoUpdateDelay!!)
        output.writeInt(lastUpdated!!)

        output.writeString(subscriptionUserinfo)
    }

    fun serializeForShare(output: ByteBufferOutput) {
        output.writeInt(0)

        output.writeInt(type!!)

        output.writeString(link)

        output.writeBoolean(forceResolve!!)
        output.writeBoolean(deduplication!!)
        output.writeBoolean(updateWhenConnectedOnly!!)
        output.writeString(customUserAgent)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        val version = input.readInt()

        type = input.readInt()
        link = input.readString()
        forceResolve = input.readBoolean()
        deduplication = input.readBoolean()
        updateWhenConnectedOnly = input.readBoolean()
        customUserAgent = input.readString()
        autoUpdate = input.readBoolean()
        autoUpdateDelay = input.readInt()
        lastUpdated = input.readInt()
        subscriptionUserinfo = input.readString()
    }

    fun deserializeFromShare(input: ByteBufferInput) {
        val version = input.readInt()

        type = input.readInt()
        link = input.readString()
        forceResolve = input.readBoolean()
        deduplication = input.readBoolean()
        updateWhenConnectedOnly = input.readBoolean()
        customUserAgent = input.readString()
    }

    override fun initializeDefaultValues() {
        if (type == null) type = 0
        if (link == null) link = ""
        if (token == null) token = ""
        if (forceResolve == null) forceResolve = false
        if (deduplication == null) deduplication = false
        if (updateWhenConnectedOnly == null) updateWhenConnectedOnly = false
        if (customUserAgent == null) customUserAgent = ""
        if (autoUpdate == null) autoUpdate = false
        if (autoUpdateDelay == null) autoUpdateDelay = 1440
        if (lastUpdated == null) lastUpdated = 0

        if (bytesUsed == null) bytesUsed = 0L
        if (bytesRemaining == null) bytesRemaining = 0L

        if (username == null) username = ""
        if (expiryDate == null) expiryDate = 0
        if (protocols == null) protocols = ArrayList()
    }

    companion object {
        @JvmField
        val CREATOR = object : CREATOR<SubscriptionBean>() {
            override fun newInstance(): SubscriptionBean {
                return SubscriptionBean()
            }

            override fun newArray(size: Int): Array<SubscriptionBean?> {
                return arrayOfNulls(size)
            }
        }
    }
}
