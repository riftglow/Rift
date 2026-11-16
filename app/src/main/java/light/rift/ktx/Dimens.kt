package light.rift.ktx

import android.content.res.Resources
import kotlin.math.ceil

/** System screen density, captured once for the dp helpers below. */
private val screenDensity: Float = Resources.getSystem().displayMetrics.density

/** Convert an integer dp value to a float pixel value. */
fun dp2pxf(dpValue: Int): Float = screenDensity * dpValue

/** Convert an integer dp value to an integer pixel value, rounding up. */
fun dp2px(dpValue: Int): Int = ceil(dp2pxf(dpValue)).toInt()
