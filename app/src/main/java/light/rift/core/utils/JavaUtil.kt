package light.rift.core.utils

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Application
import android.content.Context
import android.os.Build
import android.text.TextUtils
import android.webkit.WebView
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Method
import java.nio.channels.FileLock
import java.util.Arrays
import java.util.regex.Matcher
import java.util.regex.Pattern
import light.rift.BuildConfig
import light.rift.ktx.Logs

object JavaUtil {

    // The encoded character of each character escape.
    // This array functions as the keys of a sorted map, from encoded characters to decoded characters.
    private val ENCODED_ESCAPES = charArrayOf('"', '\'', '\\', 'b', 'f', 'n', 'r', 't')

    // The decoded character of each character escape.
    // This array functions as the values of a sorted map, from encoded characters to decoded characters.
    private val DECODED_ESCAPES = charArrayOf('"', '\'', '\\', '\b', '\u000C', '\n', '\r', '\t')

    // A pattern that matches an escape.
    // What follows the escape indicator is captured by group 1=character 2=octal 3=Unicode.
    private val PATTERN = Pattern.compile("\\\\(?:(b|t|n|f|r|\\\"|\\\'|\\\\)|((?:[0-3]?[0-7])?[0-7])|u+(\\p{XDigit}{4}))")

    // Process the return of webView.evaluateJavascript
    @JvmStatic
    fun unescapeString(encodedString: CharSequence): String {
        val matcher = PATTERN.matcher(encodedString)
        val decodedString = StringBuffer()
        // Find each escape of the encoded string in succession.
        while (matcher.find()) {
            val ch: Char
            if (matcher.start(1) >= 0) {
                // Decode a character escape.
                ch = DECODED_ESCAPES[Arrays.binarySearch(ENCODED_ESCAPES, matcher.group(1)!![0])]
            } else if (matcher.start(2) >= 0) {
                // Decode an octal escape.
                ch = matcher.group(2)!!.toInt(8).toChar()
            } else /* if (matcher.start(3) >= 0) */ {
                // Decode a Unicode escape.
                ch = matcher.group(3)!!.toInt(16).toChar()
            }
            // Replace the escape with the decoded character.
            matcher.appendReplacement(decodedString, Matcher.quoteReplacement(ch.toString()))
        }
        // Append the remainder of the encoded string to the decoded string.
        // The remainder is the longest suffix of the encoded string such that the suffix contains no escapes.
        matcher.appendTail(decodedString)
        return decodedString.toString()
    }

    // Webview Utils

    @JvmStatic
    fun handleWebviewDir(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }
        try {
            val pathSet = HashSet<String>()
            var suffix: String
            val dataPath = context.dataDir.absolutePath
            val webViewDir = "/app_webview"
            val huaweiWebViewDir = "/app_hws_webview"
            val lockFile = "/webview_data.lock"
            val processName = Application.getProcessName()
            if (BuildConfig.APPLICATION_ID != processName) {//判断不等于默认进程名称
                suffix = if (TextUtils.isEmpty(processName)) context.packageName else processName!!
                WebView.setDataDirectorySuffix(suffix)
                suffix = "_" + suffix
                pathSet.add(dataPath + webViewDir + suffix + lockFile)
                if (checkIsHuaweiRom()) {
                    pathSet.add(dataPath + huaweiWebViewDir + suffix + lockFile)
                }
            } else {
                //主进程
                suffix = "_" + processName
                pathSet.add(dataPath + webViewDir + lockFile)//默认未添加进程名后缀
                pathSet.add(dataPath + webViewDir + suffix + lockFile)//系统自动添加了进程名后缀
                if (checkIsHuaweiRom()) {//部分华为手机更改了webview目录名
                    pathSet.add(dataPath + huaweiWebViewDir + lockFile)
                    pathSet.add(dataPath + huaweiWebViewDir + suffix + lockFile)
                }
            }
            for (path in pathSet) {
                val file = File(path)
                if (file.exists()) {
                    tryLockOrRecreateFile(file)
                    break
                }
            }
        } catch (e: Exception) {
            Logs.e(e)
        }
    }

    @TargetApi(Build.VERSION_CODES.P)
    private fun tryLockOrRecreateFile(file: File) {
        try {
            val tryLock: FileLock? = RandomAccessFile(file, "rw").channel.tryLock()
            if (tryLock != null) {
                tryLock.close()
            } else {
                createFile(file, file.delete())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            var deleted = false
            if (file.exists()) {
                deleted = file.delete()
            }
            createFile(file, deleted)
        }
    }

    private fun createFile(file: File, deleted: Boolean) {
        try {
            if (deleted && !file.exists()) {
                file.createNewFile()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkIsHuaweiRom(): Boolean {
        return Build.MANUFACTURER.contains("HUAWEI")
    }

    @SuppressLint("PrivateApi")
    @JvmStatic
    fun getProcessName(): String {
        if (Build.VERSION.SDK_INT >= 28)
            return Application.getProcessName()

        // Using the same technique as Application.getProcessName() for older devices
        // Using reflection since ActivityThread is an internal API

        try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val methodName = "currentProcessName"
            val getProcessName: Method = activityThread.getDeclaredMethod(methodName)
            return getProcessName.invoke(null) as String
        } catch (e: Exception) {
            return BuildConfig.APPLICATION_ID
        }
    }

    // Old hutool Utils

    @JvmStatic
    fun isNullOrBlank(str: String?): Boolean {
        return str == null || str.isBlank()
    }

    @JvmStatic
    fun isNotBlank(str: String?): Boolean {
        return !isNullOrBlank(str)
    }

    private val HEX_ARRAY = "0123456789abcdef".toCharArray()

    @JvmStatic
    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = HEX_ARRAY[v ushr 4]
            hexChars[j * 2 + 1] = HEX_ARRAY[v and 0x0F]
        }
        return String(hexChars)
    }

    @JvmStatic
    fun isEmpty(array: ByteArray?): Boolean {
        return array == null || array.isEmpty()
    }

    // gson

    @JvmField
    val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .setNumberToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .setLenient()
        .disableHtmlEscaping()
        .create()
}
