package light.rift.ktx

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import light.rift.R

/** Builds a simple error dialog: title, message and a single OK button. */
fun Context.alert(text: String): AlertDialog =
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.error_title)
        .setMessage(text)
        .setPositiveButton(android.R.string.ok, null)
        .create()

fun Fragment.alert(text: String) = requireContext().alert(text)

/** Shows the dialog unless its host activity is already finishing or gone. */
fun AlertDialog.tryToShow() {
    try {
        val host = context as Activity
        if (!host.isFinishing) show()
    } catch (e: Exception) {
        Logs.e(e)
    }
}
