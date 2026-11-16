package light.rift.database

import android.os.Parcel

/**
 * see: https://youtrack.jetbrains.com/issue/KT-19853
 *
 * 2026-11-16 修正：之前这里用 parcel.readParcelable()，但备份导出
 * (BackupContent.toBase64Str) 是直接调 writeToParcel() 写字段，
 * parcel 里根本没有 readParcelable 要读的类名头，读到的全是垃圾
 * 字节，导入带规则的备份必崩（ClassNotFoundException: Invalid
 * name: ��）。节点/分组/设置三处一直用 CREATOR.createFromParcel
 * 直接读字段，是对的；规则这里改成同样做法。
 */
object ParcelizeBridge {

    @JvmStatic
    fun createRule(parcel: Parcel): RuleEntity {
        return RuleEntity.CREATOR.createFromParcel(parcel)
    }
}
