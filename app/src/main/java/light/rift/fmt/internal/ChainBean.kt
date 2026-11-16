package light.rift.fmt.internal

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import light.rift.core.utils.JavaUtil
import light.rift.fmt.KryoConverters

class ChainBean : InternalBean() {

    var proxies: List<Long> = ArrayList()

    override fun displayName(): String {
        if (JavaUtil.isNotBlank(name)) {
            return name!!
        } else {
            return "Chain " + Math.abs(hashCode())
        }
    }

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()

        if (proxies == null) {
            proxies = ArrayList()
        }
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(1)
        output.writeInt(proxies!!.size)
        for (proxy in proxies!!) {
            output.writeLong(proxy)
        }
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        if (version < 1) {
            input.readString()
            input.readInt()
        }
        val length = input.readInt()
        val list = ArrayList<Long>()
        for (i in 0 until length) {
            list.add(input.readLong())
        }
        proxies = list
    }

    override fun clone(): ChainBean {
        return KryoConverters.deserialize(ChainBean(), KryoConverters.serialize(this))
    }

    companion object {
        @JvmField
        val CREATOR = object : CREATOR<ChainBean>() {
            override fun newInstance(): ChainBean {
                return ChainBean()
            }

            override fun newArray(size: Int): Array<ChainBean?> {
                return arrayOfNulls(size)
            }
        }
    }
}
