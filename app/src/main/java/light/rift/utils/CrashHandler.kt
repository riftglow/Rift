package light.rift.utils

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jakewharton.processphoenix.ProcessPhoenix
import light.rift.BuildConfig
import light.rift.Key
import light.rift.RiftApp
import light.rift.database.preference.PublicDatabase
import light.rift.ktx.Logs
import light.rift.ktx.app
import light.rift.ui.BlankActivity
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

object CrashHandler : Thread.UncaughtExceptionHandler {

    /** 报告头里绝不能出现的设置键（点名拦，不靠子串匹配）。 */
    private val SENSITIVE_HEADER_KEYS = setOf(
        Key.MIXED_INBOUND_PASS, Key.CLASH_API_SECRET, Key.GLOBAL_CUSTOM_CONFIG
    )

    @Suppress("UNNECESSARY_SAFE_CALL")
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // note: libc / go panic is in android log

        try {
            Log.e(thread.toString(), throwable.stackTraceToString())
        } catch (e: Exception) {
        }

        try {
            Logs.e(thread.toString())
            Logs.e(throwable.stackTraceToString())
        } catch (e: Exception) {
        }

        ProcessPhoenix.triggerRebirth(app, Intent(app, BlankActivity::class.java).apply {
            putExtra("sendLog", "Rift Crash")
        })
    }

    fun formatThrowable(throwable: Throwable): String {
        var format = throwable.javaClass.name
        val message = throwable.message
        if (!message.isNullOrBlank()) {
            format += ": $message"
        }
        format += "\n"

        format += throwable.stackTrace.joinToString("\n") {
            "    at ${it.className}.${it.methodName}(${it.fileName}:${if (it.isNativeMethod) "native" else it.lineNumber})"
        }

        val cause = throwable.cause
        if (cause != null) {
            format += "\n\nCaused by: " + formatThrowable(cause)
        }

        return format
    }

    fun buildReportHeader(): String {
        var report = ""
        report += "Rift for Android ${RiftApp.appVersionNameForDisplay} (${BuildConfig.VERSION_CODE})\n"
        report += "Date: ${getCurrentMilliSecondUTCTimeStamp()}\n\n"
        report += "OS_VERSION: ${getSystemPropertyWithAndroidAPI("os.version")}\n"
        report += "SDK_INT: ${Build.VERSION.SDK_INT}\n"
        report += if ("REL" == Build.VERSION.CODENAME) {
            "RELEASE: ${Build.VERSION.RELEASE}"
        } else {
            "CODENAME: ${Build.VERSION.CODENAME}"
        } + "\n"
        report += "ID: ${Build.ID}\n"
        report += "DISPLAY: ${Build.DISPLAY}\n"
        report += "INCREMENTAL: ${Build.VERSION.INCREMENTAL}\n"

        val systemProperties = getSystemProperties()

        report += "SECURITY_PATCH: ${systemProperties.getProperty("ro.build.version.security_patch")}\n"
        report += "IS_DEBUGGABLE: ${systemProperties.getProperty("ro.debuggable")}\n"
        report += "IS_EMULATOR: ${systemProperties.getProperty("ro.boot.qemu")}\n"
        report += "IS_TREBLE_ENABLED: ${systemProperties.getProperty("ro.treble.enabled")}\n"

        report += "TYPE: ${Build.TYPE}\n"
        report += "TAGS: ${Build.TAGS}\n\n"

        report += "MANUFACTURER: ${Build.MANUFACTURER}\n"
        report += "BRAND: ${Build.BRAND}\n"
        report += "MODEL: ${Build.MODEL}\n"
        report += "PRODUCT: ${Build.PRODUCT}\n"
        report += "BOARD: ${Build.BOARD}\n"
        report += "HARDWARE: ${Build.HARDWARE}\n"
        report += "DEVICE: ${Build.DEVICE}\n"
        report += "SUPPORTED_ABIS: ${
            Build.SUPPORTED_ABIS.filter { it.isNotBlank() }.joinToString(", ")
        }\n\n"


        try {
            report += "Settings: \n"
            // S-P3: this dump goes into exported/crash logs. Never
            // print WebDAV credentials or anything that looks like a
            // password, token or secret — the WebDAV password
            // ciphertext itself lives in here
            // (webdavPasswordEncrypted), and a ciphertext in a
            // shared log is still a credential leak.
            var redacted = 0
            for (pair in PublicDatabase.kvPairDao.all()) {
                val key = pair.key.lowercase()
                // 显式敏感键：子串过滤漏网的必须点名拦。mixedInboundPass
                // 小写后是 mixedinboundpass，只含 "pass" 不含 "password"，
                // 只靠子串会把本地代理密码明文打进导出日志。clashApiSecret
                // 与 globalCustomConfig 目前靠 "secret"/"customconfig"
                // 兜住，也一并点名，免得将来改名改漏。
                if (pair.key in SENSITIVE_HEADER_KEYS ||
                    "webdav" in key || "password" in key ||
                    "token" in key || "secret" in key ||
                    // N8：globalCustomConfig 是用户手写 JSON，理论上
                    // 可含敏感内容，原样打进日志等于漏凭据。
                    "customconfig" in key
                ) {
                    redacted++
                    continue
                }
                report += "\n"
                report += pair.key + ": " + pair.toString()
            }
            if (redacted > 0) {
                report += "\n[$redacted sensitive setting(s) omitted]"
            }
        } catch (e: Exception) {
            report += "Export settings failed: " + formatThrowable(e)
        }

        report += "\n\n"

        return report
    }

    private fun getSystemProperties(): Properties {
        val systemProperties = Properties()

        // getprop commands returns values in the format `[key]: [value]`
        // Regex matches string starting with a literal `[`,
        // followed by one or more characters that do not match a closing square bracket as the key,
        // followed by a literal `]: [`,
        // followed by one or more characters as the value,
        // followed by string ending with literal `]`
        // multiline values will be ignored
        val propertiesPattern = Pattern.compile("^\\[([^]]+)]: \\[(.+)]$")
        try {
            val process = ProcessBuilder().command("/system/bin/getprop")
                .redirectErrorStream(true)
                .start()
            val inputStream = process.inputStream
            val bufferedReader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var key: String
            var value: String
            while (bufferedReader.readLine().also { line = it } != null) {
                val matcher = propertiesPattern.matcher(line)
                if (matcher.matches()) {
                    key = matcher.group(1)
                    value = matcher.group(2)
                    if (key != null && value != null && !key.isEmpty() && !value.isEmpty()) systemProperties[key] =
                        value
                }
            }
            bufferedReader.close()
            process.destroy()
        } catch (e: IOException) {
            Logs.e(
                "Failed to get run \"/system/bin/getprop\" to get system properties.", e
            )
        }

        //for (String key : systemProperties.stringPropertyNames()) {
        //    Logger.logVerbose(key + ": " +  systemProperties.get(key));
        //}
        return systemProperties
    }

    private fun getSystemPropertyWithAndroidAPI(property: String): String? {
        return try {
            System.getProperty(property)
        } catch (e: Exception) {
            Logs.e("Failed to get system property \"" + property + "\":" + e.message)
            null
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun getCurrentMilliSecondUTCTimeStamp(): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z")
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date())
    }

}