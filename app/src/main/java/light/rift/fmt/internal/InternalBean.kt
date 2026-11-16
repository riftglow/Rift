package light.rift.fmt.internal

import light.rift.fmt.AbstractBean

abstract class InternalBean : AbstractBean() {

    override fun displayAddress(): String {
        return ""
    }

    override fun canICMPing(): Boolean {
        return false
    }

    override fun canTCPing(): Boolean {
        return false
    }

    override fun canMapping(): Boolean {
        return false
    }
}
