package light.rift.database

import android.os.Parcel

/**
 * see: https://youtrack.jetbrains.com/issue/KT-19853
 */
object ParcelizeBridge {

    @JvmStatic
    fun createRule(parcel: Parcel): RuleEntity {
        return parcel.readParcelable<RuleEntity>(RuleEntity::class.java.classLoader)!!
    }
}
