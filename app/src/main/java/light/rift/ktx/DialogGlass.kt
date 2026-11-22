package light.rift.ktx

import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import light.rift.database.DataStore
import java.util.function.Consumer

/**
 * Dialog glass (毛玻璃弹窗), -35.
 *
 * Applies Android 12+ cross-window background blur to a dialog, but only
 * when every gate passes: SDK >= 31, the in-app glass switch is on, the
 * device is not low-RAM, and the system currently allows cross-window
 * blur. Any gate failing leaves the dialog exactly as the system drew it
 * (opaque card), so a failed gate can never produce a see-through dialog.
 *
 * The blur region and its rounded corners follow the dialog's own window
 * background drawable (the system mimics it), so the card is made
 * translucent by lowering that drawable's alpha instead of swapping in
 * a hand-made shape: the corners always match, day and night.
 *
 * Not yet proven on device: whether the alpha trick makes the blur
 * visible through a Material dialog card. If the device test shows no
 * blur, the fallback is an explicit translucent rounded drawable.
 */
private const val DIALOG_GLASS_BLUR_RADIUS = 60
private const val DIALOG_GLASS_BACKGROUND_ALPHA = 192 // 75% opaque

/** Applies the glass effect to an already-shown dialog. Safe to call on any dialog. */
fun Dialog.applyRiftGlass() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    if (!DataStore.glassEffect) return
    val windowManager = context.getSystemService(WindowManager::class.java) ?: return
    val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
    if (activityManager.isLowRamDevice) return
    if (!windowManager.isCrossWindowBlurEnabled) return
    val window = window ?: return
    // The decor view detaches when the dialog is dismissed: unregister
    // the listener there, so no dismiss-listener chaining is needed.
    val listener = Consumer<Boolean> { enabled -> applyDialogGlassBackground(window, enabled) }
    windowManager.addCrossWindowBlurEnabledListener(listener)
    window.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            windowManager.removeCrossWindowBlurEnabledListener(listener)
            v.removeOnAttachStateChangeListener(this)
        }
    })
    applyDialogGlassBackground(window, true)
}

private fun applyDialogGlassBackground(window: Window, enabled: Boolean) {
    val background = window.decorView.background ?: return
    background.mutate().alpha = if (enabled) DIALOG_GLASS_BACKGROUND_ALPHA else 255
    window.setBackgroundBlurRadius(if (enabled) DIALOG_GLASS_BLUR_RADIUS else 0)
}

/**
 * Material dialog builder that applies [Dialog.applyRiftGlass] to every
 * dialog it produces. Use this instead of [MaterialAlertDialogBuilder]
 * for all dialogs; the call sites are a mechanical rename.
 */
class RiftAlertDialogBuilder : MaterialAlertDialogBuilder {
    constructor(context: Context) : super(context)
    constructor(context: Context, themeResId: Int) : super(context, themeResId)

    override fun show(): AlertDialog = super.show().also { it.applyRiftGlass() }

    override fun create(): AlertDialog = super.create().also { dialog ->
        dialog.setOnShowListener { dialog.applyRiftGlass() }
    }
}

/**
 * 框架 Preference 弹窗（EditTextPreference 等）走框架自己的
 * DialogFragment，不是 RiftAlertDialogBuilder 建的，不会自己上玻璃。
 * 弹窗显示后，找到它的 DialogFragment，手动上玻璃。
 * dialog 还没显示时，OnShowListener 会在显示时再上一次，兜底。
 */
fun androidx.fragment.app.FragmentManager.applyGlassToPreferenceDialog() {
    try {
        executePendingTransactions()
    } catch (_: Exception) {
        return
    }
    val frag = findFragmentByTag(
        "androidx.preference.PreferenceFragment.DIALOG"
    ) as? androidx.fragment.app.DialogFragment ?: return
    val dialog = frag.dialog ?: return
    dialog.setOnShowListener { dialog.applyRiftGlass() }
    dialog.applyRiftGlass()
}
