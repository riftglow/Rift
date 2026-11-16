package light.rift.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Per-profile traffic counters, parcelled between the service and the UI.
 *
 * Field order is part of the parcel layout — do not reorder.
 */
@Parcelize
data class TrafficData(
    var id: Long = 0L,
    var tx: Long = 0L,
    var rx: Long = 0L,
) : Parcelable
