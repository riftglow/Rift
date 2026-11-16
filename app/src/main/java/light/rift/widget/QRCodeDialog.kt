package light.rift.widget

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import light.rift.R
import light.rift.ktx.Logs
import light.rift.ktx.readableMessage
import light.rift.ui.MainActivity
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

class QRCodeDialog() : DialogFragment() {

    companion object {
        private const val KEY_URL = "light.rift.QRCodeDialog.KEY_URL"
        private const val KEY_NAME = "light.rift.QRCodeDialog.KEY_NAME"
        private val iso88591 = StandardCharsets.ISO_8859_1.newEncoder()
    }

    constructor(url: String, displayName: String) : this() {
        arguments = bundleOf(
            Pair(KEY_URL, url), Pair(KEY_NAME, displayName)
        )
    }

    /** 二维码边长取屏幕短边的 80%，拿不到屏幕尺寸就用默认大小。 */
    private fun qrSize(): Int {
        var pixelMin = 0
        try {
            val displayMetrics: DisplayMetrics = requireContext().resources.displayMetrics
            val height: Int = displayMetrics.heightPixels
            val width: Int = displayMetrics.widthPixels
            pixelMin = if (height > width) width else height
            pixelMin = (pixelMin * 0.8).roundToInt()
        } catch (e: Exception) {
        }
        return if (pixelMin > 0) pixelMin else resources.getDimensionPixelSize(R.dimen.qrcode_size)
    }

    /** 把链接编码成黑白位图；纯 ISO-8859-1 字符不用额外指定编码。 */
    private fun encodeQrBitmap(url: String, size: Int): Bitmap {
        val hints = mutableMapOf<EncodeHintType, Any>()
        if (!iso88591.canEncode(url)) hints[EncodeHintType.CHARACTER_SET] = StandardCharsets.UTF_8.name()
        val qrBits = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints)
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            for (x in 0 until size) for (y in 0 until size) {
                setPixel(x, y, if (qrBits.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
    }

    /**
     * Based on:
     * https://android.googlesource.com/platform/
    packages/apps/Settings/+/0d706f0/src/com/android/settings/wifi/qrcode/QrCodeGenerator.java
     * https://android.googlesource.com/platform/
    packages/apps/Settings/+/8a9ccfd/src/com/android/settings/wifi/dpp/WifiDppQrCodeGeneratorFragment.java#153
     */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ) = try {
        val size = qrSize()

        // draw QR Code
        val url = arguments?.getString(KEY_URL)!!
        val displayName = arguments?.getString(KEY_NAME)!!
        val qrBitmap = encodeQrBitmap(url, size)

        LinearLayout(context).apply {
            // Layout
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER

            // QR Code Image View
            addView(ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setImageBitmap(qrBitmap)
            })

            // Text View
            addView(TextView(context).apply {
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = displayName
            })
        }
    } catch (e: WriterException) {
        Logs.w(e)
        (activity as MainActivity).snackbar(e.readableMessage).show()
        dismiss()
        null
    }
}
