package light.rift.bg

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.text.isDigitsOnly
import light.rift.ktx.Logs
import java.io.File
import java.io.IOException

/**
 * Kills leftover native plugin processes (Trojan, TUIC) by scanning /proc
 * for their executables and SIGKILL-ing them.
 */
object Executable {

    /** Plugin binaries Rift ships; anything else in /proc is left alone. */
    private val pluginBinaries = setOf(
        "libtrojan.so",
        "libtuic.so",
    )

    /** Kill every running plugin process. With [alsoKillBg], also kill ":bg" helpers. */
    fun killAll(alsoKillBg: Boolean = false) {
        val numericEntries = File("/proc").listFiles { _, name -> name.isDigitsOnly() } ?: return
        for (processDir in numericEntries) {
            val executable = resolveExecutable(processDir) ?: continue
            if (!isTarget(executable, alsoKillBg)) continue
            killProcess(processDir, executable)
        }
    }

    /** Read the first argv entry of a /proc/<pid> entry; null when unreadable. */
    private fun resolveExecutable(processDir: File): File? {
        val rawCmdline = try {
            File(processDir, "cmdline").inputStream().bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            return null
        }
        return File(rawCmdline.split(Character.MIN_VALUE, limit = 2).first())
    }

    private fun isTarget(executable: File, alsoKillBg: Boolean): Boolean =
        pluginBinaries.contains(executable.name) ||
            (alsoKillBg && executable.name.endsWith(":bg"))

    private fun killProcess(processDir: File, executable: File) {
        try {
            Os.kill(processDir.name.toInt(), OsConstants.SIGKILL)
            Logs.w("SIGKILL ${executable.name} (${processDir.name}) succeed")
        } catch (e: ErrnoException) {
            // The process already exited on its own — nothing to do.
            if (e.errno == OsConstants.ESRCH) return
            Logs.w("SIGKILL ${executable.absolutePath} (${processDir.name}) failed")
            Logs.w(e)
        }
    }

}
