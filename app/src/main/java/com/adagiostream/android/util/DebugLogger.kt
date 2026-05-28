package com.adagiostream.android.util

import android.content.Context
import android.util.Log
import com.adagiostream.android.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Persistent file-based logger for debugging player, CarPlay/Android Auto,
 * and SXM metadata issues. Logs are written to the app's files directory
 * and can be exported via share.
 *
 * Mirrors the iOS DebugLogger implementation.
 */
object DebugLogger {
    private const val TAG = "DebugLogger"
    private const val LOG_FILE = "adagiostream-debug.log"
    private const val PREV_LOG_FILE = "adagiostream-debug-prev.log"
    private const val MAX_FILE_SIZE = 2L * 1024 * 1024 // 2 MB

    @Volatile
    var isEnabled: Boolean = false

    private var logDir: File? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        logDir = context.filesDir
    }

    fun log(
        message: String,
        category: Category = Category.GENERAL,
        tag: String? = null,
    ) {
        if (!isEnabled) return
        val timestamp = dateFormat.format(Date())
        val caller = tag ?: inferCaller()
        val redacted = UrlSanitizer.redact(message)
        val entry = "[$timestamp] [${category.label}] [$caller] $redacted\n"

        // Also emit to logcat for real-time viewing
        Log.d("Adagio/${category.label}", redacted)

        executor.execute {
            try {
                val dir = logDir ?: return@execute
                val logFile = File(dir, LOG_FILE)
                rotateIfNeeded(dir, logFile)

                if (!logFile.exists()) {
                    val header = "=== AdagioStream Debug Log === v${BuildConfig.VERSION_NAME}\n\n"
                    logFile.writeText(header)
                }
                logFile.appendText(entry)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write log: ${e.message}")
            }
        }
    }

    fun logCrash(thread: Thread, throwable: Throwable) {
        // Flush pending async writes so context leading up to the crash is preserved.
        try {
            executor.shutdown()
            executor.awaitTermination(500, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }

        val timestamp = dateFormat.format(Date())
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val entry = "[$timestamp] [CRASH] [thread=${thread.name}]\n$stack\n"

        Log.e("Adagio/CRASH", stack)

        try {
            val dir = logDir ?: return
            val logFile = File(dir, LOG_FILE)
            if (!logFile.exists()) {
                val header = "=== AdagioStream Debug Log === v${BuildConfig.VERSION_NAME}\n\n"
                logFile.writeText(header)
            }
            logFile.appendText(entry)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write crash log: ${e.message}")
        }
    }

    fun logFileSize(): String {
        val dir = logDir ?: return "0 KB"
        val file = File(dir, LOG_FILE)
        if (!file.exists()) return "0 KB"
        val size = file.length()
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", size.toDouble() / (1024 * 1024))
        }
    }

    fun logFile(): File? {
        val dir = logDir ?: return null
        val file = File(dir, LOG_FILE)
        return if (file.exists()) file else null
    }

    fun clearLogs() {
        executor.execute {
            val dir = logDir ?: return@execute
            File(dir, LOG_FILE).delete()
            File(dir, PREV_LOG_FILE).delete()
        }
    }

    private fun rotateIfNeeded(dir: File, logFile: File) {
        if (!logFile.exists() || logFile.length() <= MAX_FILE_SIZE) return
        val prevFile = File(dir, PREV_LOG_FILE)
        prevFile.delete()
        logFile.renameTo(prevFile)
    }

    private fun inferCaller(): String {
        val stack = Throwable().stackTrace
        // Skip DebugLogger frames
        val frame = stack.firstOrNull { !it.className.contains("DebugLogger") }
            ?: return "?"
        val className = frame.className.substringAfterLast('.')
        return "$className:${frame.lineNumber}"
    }

    enum class Category(val label: String) {
        GENERAL("GENERAL"),
        PLAYER("PLAYER"),
        AUDIO("AUDIO"),
        AUTO("AUTO"),
        VLC("VLC"),
        INTERRUPT("INTERRUPT"),
        NOWPLAY("NOWPLAY"),
        TIMESHIFT("TIMESHIFT"),
        SXM("SXM"),
        ESPN("ESPN"),
        IMGCACHE("IMGCACHE"),
    }
}
