package com.github.shadowsocks.plugin.fragment

import android.app.Activity
import android.content.DialogInterface
import android.os.Bundle
import android.os.Parcelable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A dialog fragment that reports which button was pressed back to its
 * caller through the fragment-result API.
 *
 * The caller hands an argument in with [arg], picks a result key with
 * [key], and registers for the outcome with [setResultListener]; the
 * dialog answers with the pressed button id plus whatever [ret] returns
 * for it. Dismissing the dialog reports [Activity.RESULT_CANCELED].
 *
 * The result plumbing follows the pattern from AOSP's ExactCalculator
 * sample (AlertDialogFragment).
 */
abstract class AlertDialogFragment<Arg : Parcelable, Ret : Parcelable?> :
    AppCompatDialogFragment(), DialogInterface.OnClickListener {

    companion object {
        private const val KEY_RESULT = "result"
        private const val KEY_ARG = "arg"
        private const val KEY_RET = "ret"
        private const val KEY_WHICH = "which"

        /**
         * Listen for the dialog's outcome under [requestKey]. The listener
         * gets the pressed button id and the [Ret] the dialog produced.
         */
        fun <Ret : Parcelable> setResultListener(
            fragment: Fragment,
            requestKey: String,
            listener: (Int, Ret?) -> Unit,
        ) {
            fragment.setFragmentResultListener(requestKey) { _, bundle ->
                val which = bundle.getInt(KEY_WHICH, Activity.RESULT_CANCELED)
                val result: Ret? = bundle.getParcelable(KEY_RET)
                listener(which, result)
            }
        }

        /**
         * Same as [setResultListener], keyed by the dialog's own class name.
         */
        inline fun <reified T : AlertDialogFragment<*, Ret>, Ret : Parcelable?> setResultListener(
            fragment: Fragment,
            noinline listener: (Int, Ret?) -> Unit,
        ) = setResultListener(fragment, T::class.java.name, listener)
    }

    /** Subclasses build the dialog's content here. */
    protected abstract fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener)

    /** The result key stored in this dialog's arguments. */
    private val resultKey: String?
        get() = requireArguments().getString(KEY_RESULT)

    /** The argument the caller passed in with [arg]. */
    protected val arg: Arg by lazy { requireArguments().getParcelable<Arg>(KEY_ARG)!! }

    /** Produce the result value for the pressed button; null by default. */
    protected open fun ret(which: Int): Ret? = null

    /** This dialog's arguments, creating them on first use. */
    private fun args(): Bundle = arguments ?: Bundle().also { arguments = it }

    /** Store the input argument for this dialog. */
    fun arg(arg: Arg) {
        args().putParcelable(KEY_ARG, arg)
    }

    /** Store the result key; defaults to this dialog's class name. */
    fun key(resultKey: String = javaClass.name) {
        args().putString(KEY_RESULT, resultKey)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.prepare(this)
        return builder.create()
    }

    override fun onClick(dialog: DialogInterface?, which: Int) {
        val key = resultKey ?: return
        val payload = Bundle().apply {
            putInt(KEY_WHICH, which)
            ret(which)?.let { putParcelable(KEY_RET, it) }
        }
        setFragmentResult(key, payload)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onClick(null, Activity.RESULT_CANCELED)
    }

}
