package light.rift.fmt.hysteria

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import light.rift.fmt.AbstractBean
import light.rift.fmt.KryoConverters
import light.rift.ktx.wrapIPV6Host

class HysteriaBean : AbstractBean() {
    var protocolVersion: Int = 2

    // Use serverPorts instead of serverPort
    var serverPorts: String = "443"

    // 端口跳跃范围（2026-11-23 -67）：存链接里的 mport，如 "24000-32000"。
    // 以前 mport 直接覆盖 serverPorts，真端口（23962）就丢了，编辑页也只
    // 有一个“服务器端口”在显示跳跃范围。现在端口和跳跃范围分开存、分开填。
    var hopPorts: String = ""

    // HY1 & 2

    var authPayload: String = ""
    var obfuscation: String = ""
    var sni: String = ""
    var caText: String = ""
    var uploadMbps: Int = 0
    var downloadMbps: Int = 0
    var allowInsecure: Boolean = false
    var streamReceiveWindow: Int = 0
    var connectionReceiveWindow: Int = 0
    var disableMtuDiscovery: Boolean = false
    var hopInterval: Int = 10
    // Hysteria2 增强（2026-11-10，规划第 13 项）：0 / 空 / false = 未设，
    // 未设时用全局设置，全未设则不发字段。
    var hopIntervalMax: Int = 0
    var bbrProfile: String = ""
    var disableChromeParrot: Boolean = false
    var brutalDebug: Boolean = false

    // HY1

    var alpn: String = ""

    var authPayloadType: Int = TYPE_NONE

    var protocol: Int = PROTOCOL_UDP

    override fun canMapping(): Boolean {
        return true
    }

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (protocolVersion == null) protocolVersion = 2

        if (authPayloadType == null) authPayloadType = TYPE_NONE
        if (authPayload == null) authPayload = ""
        if (protocol == null) protocol = PROTOCOL_UDP
        if (obfuscation == null) obfuscation = ""
        if (sni == null) sni = ""
        if (alpn == null) alpn = ""
        if (caText == null) caText = ""
        if (allowInsecure == null) allowInsecure = false

        if (uploadMbps == null) uploadMbps = 0
        if (downloadMbps == null) downloadMbps = 0

        if (streamReceiveWindow == null) streamReceiveWindow = 0
        if (connectionReceiveWindow == null) connectionReceiveWindow = 0
        if (disableMtuDiscovery == null) disableMtuDiscovery = false
        if (hopInterval == null) hopInterval = 10
        if (hopIntervalMax == null) hopIntervalMax = 0
        if (bbrProfile == null) bbrProfile = ""
        if (disableChromeParrot == null) disableChromeParrot = false
        if (brutalDebug == null) brutalDebug = false
        if (serverPorts == null) serverPorts = "443"
        if (hopPorts == null) hopPorts = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(9)
        super.serialize(output)

        output.writeInt(protocolVersion!!)

        output.writeInt(authPayloadType!!)
        output.writeString(authPayload)
        output.writeInt(protocol!!)
        output.writeString(obfuscation)
        output.writeString(sni)
        output.writeString(alpn)

        output.writeInt(uploadMbps!!)
        output.writeInt(downloadMbps!!)
        output.writeBoolean(allowInsecure!!)

        output.writeString(caText)
        output.writeInt(streamReceiveWindow!!)
        output.writeInt(connectionReceiveWindow!!)
        output.writeBoolean(disableMtuDiscovery!!)
        output.writeInt(hopInterval!!)
        output.writeString(serverPorts)
        output.writeInt(hopIntervalMax!!)
        output.writeString(bbrProfile)
        output.writeBoolean(disableChromeParrot!!)
        output.writeBoolean(brutalDebug!!)
        output.writeString(hopPorts)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        if (version >= 7) {
            protocolVersion = input.readInt()
        } else {
            protocolVersion = 1
        }
        authPayloadType = input.readInt()
        authPayload = input.readString()
        if (version >= 3) {
            protocol = input.readInt()
        }
        obfuscation = input.readString()
        sni = input.readString()
        if (version >= 2) {
            alpn = input.readString()
        }
        uploadMbps = input.readInt()
        downloadMbps = input.readInt()
        allowInsecure = input.readBoolean()
        if (version >= 1) {
            caText = input.readString()
            streamReceiveWindow = input.readInt()
            connectionReceiveWindow = input.readInt()
            if (version != 4) disableMtuDiscovery = input.readBoolean() // note: skip 4
        }
        if (version >= 5) {
            hopInterval = input.readInt()
        }
        if (version >= 6) {
            serverPorts = input.readString()
        } else {
            // old update to new
            if (isMultiPort(serverAddress!!)) {
                serverPorts = serverAddress!!.substringAfterLast(":", serverAddress!!)
                serverAddress = serverAddress!!.substringBeforeLast(":", serverAddress!!)
            } else {
                serverPorts = serverPort!!.toString()
            }
        }
        if (version >= 8) {
            hopIntervalMax = input.readInt()
            bbrProfile = input.readString()
            disableChromeParrot = input.readBoolean()
            brutalDebug = input.readBoolean()
        } else {
            hopIntervalMax = 0
            bbrProfile = ""
            disableChromeParrot = false
            brutalDebug = false
        }
        if (version >= 9) {
            hopPorts = input.readString()
        } else {
            // 旧数据迁移（2026-11-23 -67）：以前 mport 直接写进 serverPorts，
            // 真端口丢了，serverPorts 里就是跳跃范围。把它挪到 hopPorts，
            // serverPorts 取范围的首端口当主端口（连接行为不变：有 hopPorts
            // 时出站照样走 server_ports 跳跃）。
            if (serverPorts.contains("-") || serverPorts.contains(",")) {
                hopPorts = serverPorts
                serverPorts = serverPorts.substringBefore("-").substringBefore(",").trim()
            } else {
                hopPorts = ""
            }
        }
    }

    override fun displayAddress(): String {
        return serverAddress!!.wrapIPV6Host() + ":" + serverPorts
    }

    override fun canTCPing(): Boolean {
        return false
    }

    override fun clone(): HysteriaBean {
        return KryoConverters.deserialize(HysteriaBean(), KryoConverters.serialize(this))
    }

    companion object {
        const val TYPE_NONE = 0
        const val TYPE_STRING = 1
        const val TYPE_BASE64 = 2

        const val PROTOCOL_UDP = 0

        @JvmField
        val CREATOR = object : CREATOR<HysteriaBean>() {
            override fun newInstance(): HysteriaBean {
                return HysteriaBean()
            }

            override fun newArray(size: Int): Array<HysteriaBean?> {
                return arrayOfNulls(size)
            }
        }
    }
}
