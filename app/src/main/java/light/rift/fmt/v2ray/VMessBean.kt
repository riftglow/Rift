package light.rift.fmt.v2ray

import light.rift.core.utils.JavaUtil
import light.rift.fmt.KryoConverters

class VMessBean : StandardV2RayBean() {

    var alterId: Int = 0 // alterID == -1 --> VLESS

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()

        alterId = alterId ?: 0

        if (alterId == -1) {
            encryption = if (JavaUtil.isNotBlank(encryption)) encryption else ""
        } else {
            encryption = if (JavaUtil.isNotBlank(encryption)) encryption else "auto"
        }
    }

    override fun clone(): VMessBean {
        return KryoConverters.deserialize(VMessBean(), KryoConverters.serialize(this))
    }

    companion object {
        @JvmField
        val CREATOR = object : CREATOR<VMessBean>() {
            override fun newInstance(): VMessBean {
                return VMessBean()
            }

            override fun newArray(size: Int): Array<VMessBean?> {
                return arrayOfNulls(size)
            }
        }
    }
}
