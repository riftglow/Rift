package light.rift.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Speed snapshot handed from the background service to the UI.
 *
 * Rates are bytes per second; totals are bytes for the current session.
 * The "bypass" outbound is deliberately excluded from the totals.
 *
 * Field order is part of the parcel layout — do not reorder.
 */
@Parcelize
data class SpeedDisplayData(
    var txRateProxy: Long = 0L,
    var rxRateProxy: Long = 0L,
    var txRateDirect: Long = 0L,
    var rxRateDirect: Long = 0L,
    var txTotal: Long = 0L,
    var rxTotal: Long = 0L,
) : Parcelable
