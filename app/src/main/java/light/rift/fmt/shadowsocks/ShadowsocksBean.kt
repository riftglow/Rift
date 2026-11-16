package light.rift.fmt.shadowsocks

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import light.rift.core.utils.JavaUtil
import light.rift.fmt.AbstractBean
import light.rift.fmt.KryoConverters

class ShadowsocksBean : AbstractBean() {

    var method: String = "aes-256-gcm"
    var password: String = ""
    var plugin: String = ""

    var sUoT: Boolean = false

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()

        if (JavaUtil.isNullOrBlank(method)) method = "aes-256-gcm"
        if (method == null) method = ""
        if (password == null) password = ""
        if (plugin == null) plugin = ""
        if (sUoT == null) sUoT = false
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(2)
        super.serialize(output)
        output.writeString(method)
        output.writeString(password)
        output.writeString(plugin)
        output.writeBoolean(sUoT!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        method = input.readString()
        password = input.readString()
        plugin = input.readString()
        sUoT = input.readBoolean()
    }

    override fun clone(): ShadowsocksBean {
        return KryoConverters.deserialize(ShadowsocksBean(), KryoConverters.serialize(this))
    }

    companion object {
        @JvmField
        val CREATOR = object : CREATOR<ShadowsocksBean>() {
            override fun newInstance(): ShadowsocksBean {
                return ShadowsocksBean()
            }

            override fun newArray(size: Int): Array<ShadowsocksBean?> {
                return arrayOfNulls(size)
            }
        }
    }
}
