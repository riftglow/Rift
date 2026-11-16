package light.rift.database

import android.os.Parcel
import android.os.Parcelable

/**
 * see: https://youtrack.jetbrains.com/issue/KT-19853
 *
 * RuleEntity 是 @Parcelize 生成 CREATOR 的，编译期 kotlinc 看不到它
 * （-18 直接写 RuleEntity.CREATOR 在 #5 编译失败：Unresolved
 * reference 'CREATOR'），但 CREATOR 字段确实生成在字节码里；这里用
 * 反射取它——读的还是 @Parcelize 自己生成的 CREATOR，wire 格式与导
 * 出侧（直接 writeToParcel 写字段）完全一致。
 *
 * 不能用 parcel.readParcelable()：导出是直接 writeToParcel 写字段，
 * parcel 里没有 readParcelable 要读的类名头，读到的全是垃圾字节，
 * 带规则的备份必崩（ClassNotFoundException: Invalid name）。
 * 节点/分组/设置三处用的是手写 CREATOR，所以能直接写 X.CREATOR。
 */
object ParcelizeBridge {

    @JvmStatic
    fun createRule(parcel: Parcel): RuleEntity {
        @Suppress("UNCHECKED_CAST")
        val creator = RuleEntity::class.java.getField("CREATOR").get(null)
            as Parcelable.Creator<RuleEntity>
        return creator.createFromParcel(parcel)
    }
}
