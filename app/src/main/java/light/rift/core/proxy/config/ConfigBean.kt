package light.rift.core.proxy.config

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import com.google.gson.JsonObject
import light.rift.core.utils.JavaUtil
import light.rift.fmt.KryoConverters
import light.rift.fmt.internal.InternalBean

class ConfigBean : InternalBean() {

    var type: Int = 0 // 0=config 1=outbound
    var config: String = ""

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (type == null) type = 0
        if (config == null) config = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeInt(type!!)
        output.writeString(config)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        type = input.readInt()
        config = input.readString()
    }

    override fun displayName(): String {
        if (JavaUtil.isNotBlank(name)) {
            return name!!
        } else {
            return "Custom " + Math.abs(hashCode())
        }
    }

    fun displayType(): String {
        if (type != null && type == 1 && JavaUtil.isNotBlank(config)) {
            try {
                val json = JavaUtil.gson.fromJson(config, JsonObject::class.java)
                if (json != null && json.has("type")) {
                    return json.get("type").asString + " (sing-box)"
                }
            } catch (ignored: Exception) {
            }
        }
        return if (type != null && type == 0) "sing-box config" else "sing-box outbound"
    }

    override fun clone(): ConfigBean {
        return KryoConverters.deserialize(ConfigBean(), KryoConverters.serialize(this))
    }

    companion object {
        @JvmField
        val CREATOR = object : CREATOR<ConfigBean>() {
            override fun newInstance(): ConfigBean {
                return ConfigBean()
            }

            override fun newArray(size: Int): Array<ConfigBean?> {
                return arrayOfNulls(size)
            }
        }
    }
}
