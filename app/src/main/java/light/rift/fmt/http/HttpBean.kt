package light.rift.fmt.http

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import light.rift.fmt.KryoConverters
import light.rift.fmt.v2ray.StandardV2RayBean

class HttpBean : StandardV2RayBean() {

    var username: String = ""
    var password: String = ""

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (username == null) username = ""
        if (password == null) password = ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeString(username)
        output.writeString(password)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        username = input.readString()
        password = input.readString()
    }

    override fun clone(): HttpBean {
        return KryoConverters.deserialize(HttpBean(), KryoConverters.serialize(this))
    }

    companion object {
        @JvmField
        val CREATOR = object : CREATOR<HttpBean>() {
            override fun newInstance(): HttpBean {
                return HttpBean()
            }

            override fun newArray(size: Int): Array<HttpBean?> {
                return arrayOfNulls(size)
            }
        }
    }
}
