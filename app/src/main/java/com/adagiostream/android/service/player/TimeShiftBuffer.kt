package com.adagiostream.android.service.player

import com.adagiostream.android.model.Channel
import com.adagiostream.android.util.DebugLogger
import com.adagiostream.android.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

class TimeShiftBuffer(
    private val cacheDir: File,
    private val client: OkHttpClient,
) {
    private var outputStream: BufferedOutputStream? = null
    private var captureFile: File? = null
    @Volatile
    var capturedBytes: Long = 0L
        private set
    @Volatile
    private var isCapturing = false

    private val maxDurationMs = 30L * 60 * 1000 // 30 minutes
    private val timeshiftDir = File(cacheDir, "timeshift").also { it.mkdirs() }

    suspend fun startCapture(channel: Channel) {
        if (isCapturing) return
        isCapturing = true
        capturedBytes = 0L

        val file = File(timeshiftDir, "buffer_${System.currentTimeMillis()}.ts")
        captureFile = file

        DebugLogger.log("TimeShift: starting capture for ${channel.name} to ${file.name}", DebugLogger.Category.TIMESHIFT)

        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(channel.streamURL)
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body

                val fos = FileOutputStream(file).buffered(65536)
                outputStream = fos
                val buffer = ByteArray(65536)
                val source = body.byteStream()

                while (isCapturing) {
                    val bytesRead = source.read(buffer)
                    if (bytesRead == -1) break
                    fos.write(buffer, 0, bytesRead)
                    capturedBytes += bytesRead
                }

                fos.flush()
                fos.close()
                source.close()
                response.close()
                DebugLogger.log("TimeShift: capture stopped, ${capturedBytes} bytes captured", DebugLogger.Category.TIMESHIFT)
            } catch (e: Exception) {
                DebugLogger.log("TimeShift: capture error: ${UrlSanitizer.redact(e.message ?: "Unknown error")}", DebugLogger.Category.TIMESHIFT)
            }
        }
    }

    fun stopCapture(): File? {
        isCapturing = false
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null

        val file = captureFile
        captureFile = null

        if (file != null && file.exists() && file.length() > 0) {
            DebugLogger.log("TimeShift: returning buffer file ${file.name} (${file.length()} bytes)", DebugLogger.Category.TIMESHIFT)
            return file
        }
        return null
    }

    fun cleanup() {
        isCapturing = false
        try { outputStream?.close() } catch (_: Exception) {}
        outputStream = null
        captureFile = null
        timeshiftDir.listFiles()?.forEach { it.delete() }
    }
}
