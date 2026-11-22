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
 *
 * 载荷版本头：规则备份现在先写 magic + 版本号，再写 @Parcelize 字段。
 * 以后 RuleEntity 加字段时，读侧按版本号分流，不至于把旧备份静默读错。
 * 旧备份（没有版本头）继续认：第一个 int 是 id（Long）的低 32 位，
 * 几乎不可能恰好等于 magic，读到不是 magic 就倒回开头按旧格式读。
 */
object ParcelizeBridge {

    const val RULE_PAYLOAD_MAGIC = 0x52554C45 // "RULE"
    const val RULE_PAYLOAD_VERSION = 1

    // CREATOR 反射只做一次：之前每次导规则都反射一次，慢。
    private val ruleCreator: Parcelable.Creator<RuleEntity> by lazy {
        @Suppress("UNCHECKED_CAST")
        RuleEntity::class.java.getField("CREATOR").get(null)
            as Parcelable.Creator<RuleEntity>
    }

    /** 写一条规则：先写 magic + 版本头，再写 @Parcelize 字段。 */
    fun writeRule(parcel: Parcel, rule: RuleEntity) {
        parcel.writeInt(RULE_PAYLOAD_MAGIC)
        parcel.writeInt(RULE_PAYLOAD_VERSION)
        rule.writeToParcel(parcel, 0)
    }

    @JvmStatic
    fun createRule(parcel: Parcel): RuleEntity {
        val first = parcel.readInt()
        if (first == RULE_PAYLOAD_MAGIC) {
            val version = parcel.readInt()
            if (version != RULE_PAYLOAD_VERSION) {
                error("Unsupported rule payload version: $version")
            }
        } else {
            // 旧格式：倒回开头，按 @Parcelize 字段直接读。
            parcel.setDataPosition(0)
        }
        return ruleCreator.createFromParcel(parcel)
    }
}
