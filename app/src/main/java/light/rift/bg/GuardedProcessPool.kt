package light.rift.bg

import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.MainThread
import light.rift.RiftApp
import light.rift.ktx.Logs
import light.rift.utils.Commandline
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import libcore.Libcore
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

class GuardedProcessPool(private val onFatal: suspend (IOException) -> Unit) : CoroutineScope {
    companion object {
        private val pid by lazy {
            Class.forName("java.lang.ProcessManager\$ProcessImpl").getDeclaredField("pid")
                .apply { isAccessible = true }
        }
    }

    private inner class Guard(
        private val cmd: List<String>,
        private val env: Map<String, String> = mapOf()
    ) {
        private lateinit var process: Process

        private fun streamLogger(input: InputStream, logger: (String) -> Unit) = try {
            input.bufferedReader().forEachLine(logger)
        } catch (_: IOException) {
        }    // ignore

        fun start() {
            process = ProcessBuilder(cmd).directory(RiftApp.application.noBackupFilesDir).apply {
                environment().putAll(env)
            }.start()
        }

        /** 起两个线程把进程的标准输出/错误流转到核心日志；输出流线程兼当 waitFor 的守候线程。 */
        private fun watchStreams(cmdName: String, exitChannel: Channel<Int>) {
            thread(name = "stderr-$cmdName") {
                streamLogger(process.errorStream) { Libcore.riftLogPrintln("[$cmdName] $it") }
            }
            thread(name = "stdout-$cmdName") {
                streamLogger(process.inputStream) { Libcore.riftLogPrintln("[$cmdName] $it") }
                // this thread also acts as a daemon thread for waitFor
                runBlocking { exitChannel.send(process.waitFor()) }
            }
        }

        /**
         * 进程退出了：1 秒内就死说明根本起不来，抛异常停掉；
         * 被 SIGKILL 杀掉只记一条，被杀之外的非正常退出也只记一条，都会走重启。
         */
        private fun handleExit(cmdName: String, startTime: Long, exitCode: Int) {
            when {
                SystemClock.elapsedRealtime() - startTime < 1000 -> throw IOException(
                    "$cmdName exits too fast (exit code: $exitCode)"
                )

                exitCode == 128 + OsConstants.SIGKILL -> Logs.w("$cmdName was killed")
                else -> Logs.w(IOException("$cmdName unexpectedly exits with code $exitCode"))
            }
        }

        /** 停掉看守时收尾：能等进程自己退就等，等不到再杀，杀了还不退就强杀。 */
        private suspend fun cleanup(exitChannel: Channel<Int>) {
            withContext(NonCancellable) {  // clean-up cannot be cancelled
                if (Build.VERSION.SDK_INT < 24) {
                    try {
                        Os.kill(pid.get(process) as Int, OsConstants.SIGTERM)
                    } catch (e: ErrnoException) {
                        if (e.errno != OsConstants.ESRCH) Logs.w(e)
                    } catch (e: ReflectiveOperationException) {
                        Logs.w(e)
                    }
                    if (withTimeoutOrNull(500) { exitChannel.receive() } != null) return@withContext
                }
                process.destroy()                       // kill the process
                if (Build.VERSION.SDK_INT >= 26) {
                    if (withTimeoutOrNull(1000) { exitChannel.receive() } != null) return@withContext
                    process.destroyForcibly()           // Force to kill the process if it's still alive
                }
                exitChannel.receive()
            }                                           // otherwise process already exited, nothing to be done
        }

        /** 盯着进程跑：死了就重启；启动失败则报致命错误停掉。 */
        @DelicateCoroutinesApi
        suspend fun looper(onRestartCallback: (suspend () -> Unit)?) {
            var running = true
            val cmdName = File(cmd.first()).nameWithoutExtension
            val exitChannel = Channel<Int>()
            try {
                while (true) {
                    watchStreams(cmdName, exitChannel)
                    val startTime = SystemClock.elapsedRealtime()
                    val exitCode = exitChannel.receive()
                    running = false
                    handleExit(cmdName, startTime, exitCode)
                    Logs.i("restart process: ${Commandline.toString(cmd)} (last exit code: $exitCode)")
                    start()
                    running = true
                    onRestartCallback?.invoke()
                }
            } catch (e: IOException) {
                Logs.w("error occurred. stop guard: ${Commandline.toString(cmd)}")
                GlobalScope.launch(Dispatchers.Main) { onFatal(e) }
            } finally {
                if (running) cleanup(exitChannel)
            }
        }
    }

    override val coroutineContext = Dispatchers.Main.immediate + Job()
    var processCount = 0

    @MainThread
    fun start(
        cmd: List<String>,
        env: MutableMap<String, String> = mutableMapOf(),
        onRestartCallback: (suspend () -> Unit)? = null
    ) {
        Logs.i("start process: ${Commandline.toString(cmd)}")
        Guard(cmd, env).apply {
            start() // if start fails, IOException will be thrown directly
            launch { looper(onRestartCallback) }
        }
        processCount += 1
    }

    @MainThread
    fun close(scope: CoroutineScope) {
        cancel()
        coroutineContext[Job]!!.also { job -> scope.launch { job.cancelAndJoin() } }
    }
}
