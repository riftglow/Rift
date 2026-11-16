package light.rift.fmt.v2ray

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import light.rift.core.utils.JavaUtil
import light.rift.fmt.AbstractBean
import light.rift.fmt.trojan.TrojanBean

abstract class StandardV2RayBean : AbstractBean() {

    var uuid: String = ""
    var encryption: String = "" // or VLESS flow

    //////// End of VMess & VLESS ////////

    // "V2Ray Transport" tcp/http/ws/quic/grpc/httpupgrade
    var type: String = "tcp"

    var host: String = ""

    var path: String = ""

    // --------------------------------------- tls?

    var security: String = ""

    var sni: String = ""

    var alpn: String = ""

    var utlsFingerprint: String = ""

    var allowInsecure: Boolean = false

    // --------------------------------------- reality


    var realityPubKey: String = ""

    var realityShortId: String = ""


    // --------------------------------------- //

    var wsMaxEarlyData: Int = 0
    var earlyDataHeaderName: String = ""

    var certificates: String = ""

    // --------------------------------------- xhttp

    var xhttpMode: String = "auto"
    var xhttpExtra: String = ""

    // --------------------------------------- ech

    var enableECH: Boolean = false

    var echConfig: String = ""

    // --------------------------------------- Mux

    var enableMux: Boolean = false
    var muxPadding: Boolean = false
    var muxType: Int = 0
    var muxConcurrency: Int = 1


    // --------------------------------------- //

    var packetEncoding: Int = 0 // 1:packet 2:xudp

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()

        if (JavaUtil.isNullOrBlank(uuid)) uuid = ""

        if (JavaUtil.isNullOrBlank(type)) type = "tcp"
        else if ("h2" == type) type = "http"

        type = type!!.toLowerCase()

        if (JavaUtil.isNullOrBlank(host)) host = ""
        if (JavaUtil.isNullOrBlank(path)) path = ""

        if (JavaUtil.isNullOrBlank(security)) {
            if (this is TrojanBean) {
                security = "tls"
            } else {
                security = "none"
            }
        }
        if (JavaUtil.isNullOrBlank(sni)) sni = ""
        if (JavaUtil.isNullOrBlank(alpn)) alpn = ""

        if (JavaUtil.isNullOrBlank(certificates)) certificates = ""
        if (JavaUtil.isNullOrBlank(earlyDataHeaderName)) earlyDataHeaderName = ""
        if (JavaUtil.isNullOrBlank(utlsFingerprint)) utlsFingerprint = ""

        if (wsMaxEarlyData == null) wsMaxEarlyData = 0
        if (allowInsecure == null) allowInsecure = false
        if (packetEncoding == null) packetEncoding = 0

        if (realityPubKey == null) realityPubKey = ""
        if (realityShortId == null) realityShortId = ""

        if (enableECH == null) enableECH = false
        if (JavaUtil.isNullOrBlank(echConfig)) echConfig = ""

        if (enableMux == null) enableMux = false
        if (muxPadding == null) muxPadding = false
        if (muxType == null) muxType = 0
        if (muxConcurrency == null) muxConcurrency = 1

        if (JavaUtil.isNullOrBlank(xhttpMode)) xhttpMode = "auto"
        if (JavaUtil.isNullOrBlank(xhttpExtra)) xhttpExtra = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(4)
        super.serialize(output)
        output.writeString(uuid)
        output.writeString(encryption)
        if (this is VMessBean) {
            output.writeInt(alterId!!)
        }

        output.writeString(type)
        when (type) {
            "tcp", "quic" -> {
            }
            "ws" -> {
                output.writeString(host)
                output.writeString(path)
                output.writeInt(wsMaxEarlyData!!)
                output.writeString(earlyDataHeaderName)
            }
            "http", "httpupgrade" -> {
                output.writeString(host)
                output.writeString(path)
            }
            "xhttp" -> {
                output.writeString(host)
                output.writeString(path)
                output.writeString(xhttpMode)
                output.writeString(xhttpExtra)
            }
            "grpc" -> {
                output.writeString(path)
            }
        }

        output.writeString(security)
        if ("tls" == security) {
            output.writeString(sni)
            output.writeString(alpn)
            output.writeString(certificates)
            output.writeBoolean(allowInsecure!!)
            output.writeString(utlsFingerprint)
            output.writeString(realityPubKey)
            output.writeString(realityShortId)
        }

        output.writeBoolean(enableECH!!)
        output.writeString(echConfig)

        output.writeInt(packetEncoding!!)

        output.writeBoolean(enableMux!!)
        output.writeBoolean(muxPadding!!)
        output.writeInt(muxType!!)
        output.writeInt(muxConcurrency!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        uuid = input.readString()
        encryption = input.readString()
        if (this is VMessBean) {
            alterId = input.readInt()
        }

        type = input.readString()
        when (type) {
            "tcp", "quic" -> {
            }
            "ws" -> {
                host = input.readString()
                path = input.readString()
                wsMaxEarlyData = input.readInt()
                earlyDataHeaderName = input.readString()
            }
            "http", "httpupgrade" -> {
                host = input.readString()
                path = input.readString()
            }
            "xhttp" -> {
                if (version >= 4) {
                    host = input.readString()
                    path = input.readString()
                    xhttpMode = input.readString()
                    xhttpExtra = input.readString()
                }
            }
            "grpc" -> {
                path = input.readString()
                if (version < 4) {
                    // 解决老版本数据的读取问题
                    input.readString()
                    input.readString()
                }
            }
        }

        security = input.readString()
        if ("tls" == security) {
            sni = input.readString()
            alpn = input.readString()
            certificates = input.readString()
            allowInsecure = input.readBoolean()
            utlsFingerprint = input.readString()
            realityPubKey = input.readString()
            realityShortId = input.readString()
        }

        if (version >= 1) {
            enableECH = input.readBoolean()
            if (version >= 3) {
                echConfig = input.readString()
            } else {
                if (enableECH!!) {
                    input.readBoolean()
                    input.readBoolean()
                    echConfig = input.readString()
                }
            }
        } else if (version == 0) {
            // 从老版本升级上来但是 version == 0, 可能有 enableECH 也可能没有，需要做判断
            val position = input.byteBuffer.position() // 当前位置

            val tmpEnableECH = input.readBoolean()
            val tmpPacketEncoding = input.readInt()

            input.setPosition(position) // 读后归位

            if (tmpPacketEncoding != 1 && tmpPacketEncoding != 2) {
                enableECH = tmpEnableECH
                if (enableECH!!) {
                    input.readBoolean()
                    input.readBoolean()
                    echConfig = input.readString()
                }
            } // 否则后一位就是 packetEncoding
        }

        packetEncoding = input.readInt()

        if (version >= 2) {
            enableMux = input.readBoolean()
            muxPadding = input.readBoolean()
            muxType = input.readInt()
            muxConcurrency = input.readInt()
        }
    }

    val isVLESS: Boolean
        get() {
            if (this is VMessBean) {
                val aid = alterId
                return aid != null && aid == -1
            }
            return false
        }
}
