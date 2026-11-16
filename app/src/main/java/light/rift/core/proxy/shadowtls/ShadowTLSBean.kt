package light.rift.core.proxy.shadowtls

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import light.rift.fmt.KryoConverters
import light.rift.fmt.v2ray.StandardV2RayBean

class ShadowTLSBean : StandardV2RayBean() {

    var version: Int = 3
    var password: String = ""

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()

        security = "tls"
        if (version == null) version = 3
        if (password == null) password = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeInt(version!!)
        output.writeString(password)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version_ = input.readInt()
        super.deserialize(input)
        version = input.readInt()
        password = input.readString()
    }

    override fun clone(): ShadowTLSBean {
        return KryoConverters.deserialize(ShadowTLSBean(), KryoConverters.serialize(this))
    }

    companion object {
        @JvmField
        val CREATOR = object : CREATOR<ShadowTLSBean>() {
            override fun newInstance(): ShadowTLSBean {
                return ShadowTLSBean()
            }

            override fun newArray(size: Int): Array<ShadowTLSBean?> {
                return arrayOfNulls(size)
            }
        }
    }
}
