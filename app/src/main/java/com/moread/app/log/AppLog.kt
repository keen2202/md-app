package com.moread.app.log

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 临时排查日志（发布前可整体移除或关闭 [ENABLED]）。
 *
 * 输出到 Logcat，同时写入应用私有目录 files/debug_logs/moread-debug.log，
 * 便于用户无法连接 adb 时仍可导出排查。日志只记录阶段、数量、异常堆栈，
 * 不记录 Markdown 正文、图片内容或用户文件完整路径，避免隐私泄露。
 */
object AppLog {

    /** 临时排查开关；问题定位完成后改为 false 或直接删除本类即可。 */
    const val ENABLED = true

    const val TAG = "MoRead"

    private const val LOG_DIR = "debug_logs"
    private const val LOG_FILE = "moread-debug.log"
    private const val MAX_LOG_BYTES = 512L * 1024L

    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "moread-log").apply { isDaemon = true }
    }
    private val installed = AtomicBoolean(false)
    private val fileLock = Any()

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        if (!ENABLED) return
        val appContext = context.applicationContext
        val dir = File(appContext.filesDir, LOG_DIR)
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, LOG_FILE)
        installUncaughtExceptionHandler()
        i("AppLog", "logger initialized: sdk=${Build.VERSION.SDK_INT}, model=${Build.MODEL}")
    }

    fun d(scope: String, message: String) = log(Log.DEBUG, scope, message, null)

    fun i(scope: String, message: String) = log(Log.INFO, scope, message, null)

    fun w(scope: String, message: String, throwable: Throwable? = null) =
        log(Log.WARN, scope, message, throwable)

    fun e(scope: String, message: String, throwable: Throwable? = null) =
        log(Log.ERROR, scope, message, throwable)

    private fun log(priority: Int, scope: String, message: String, throwable: Throwable?) {
        if (!ENABLED) return
        val line = "${now()} ${priorityName(priority)}/$TAG [$scope] $message"
        Log.println(priority, TAG, if (throwable == null) line else "$line\n${stackTraceOf(throwable)}")

        val file = logFile ?: return
        ioExecutor.execute { append(file, line, throwable) }
    }

    private fun installUncaughtExceptionHandler() {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val line = "${now()} E/$TAG [Crash] uncaught on thread=${thread.name}"
            Log.println(Log.ERROR, TAG, "$line\n${stackTraceOf(throwable)}")
            logFile?.let { append(it, line, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Runtime.getRuntime().exit(2)
            }
        }
    }

    private fun append(file: File, line: String, throwable: Throwable?) {
        synchronized(fileLock) {
            try {
                if (file.length() > MAX_LOG_BYTES) {
                    file.delete()
                    file.createNewFile()
                }
                FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.append(line).append('\n')
                    if (throwable != null) {
                        writer.append(stackTraceOf(throwable)).append('\n')
                    }
                }
            } catch (_: Exception) {
                // 日志写入失败不能影响主流程。
            }
        }
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun now(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun priorityName(priority: Int): String = when (priority) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        else -> "E"
    }
}
