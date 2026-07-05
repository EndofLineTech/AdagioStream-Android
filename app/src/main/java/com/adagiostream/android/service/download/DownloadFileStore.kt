package com.adagiostream.android.service.download

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Filesystem seam for downloaded track files (baw.6.1).
 *
 * Storage accounting reads [sizeOf] (File.length) as the source of truth — never
 * a DB byte counter. Production writes to app-private
 * `getExternalFilesDir(DIRECTORY_MUSIC)` (no dangerous permission, no Play Store
 * storage-policy review — the ratified baw.6.4 decision).
 */
interface DownloadFileStore {

    /** Absolute path where [trackId]'s file should live (extension from [suffix]). */
    fun fileFor(trackId: String, suffix: String?): String

    /** Current on-disk size of [path] in bytes, or 0 if it does not exist. */
    fun sizeOf(path: String): Long

    fun exists(path: String): Boolean

    /** Appends [len] bytes from [bytes] to [path], creating parent dirs as needed. */
    fun append(path: String, bytes: ByteArray, len: Int)

    /** Truncates [path] to empty (used when a server ignores a Range request). */
    fun truncate(path: String)

    /** Deletes [path]; returns true if a file was removed. */
    fun delete(path: String): Boolean
}

/**
 * Production [DownloadFileStore] backed by app-private external storage.
 */
class AppFileDownloadStore(private val context: Context) : DownloadFileStore {

    private val baseDir: File
        get() = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: context.filesDir,
            "downloads",
        ).apply { if (!exists()) mkdirs() }

    override fun fileFor(trackId: String, suffix: String?): String {
        val safeId = trackId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val ext = suffix?.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
        return File(baseDir, "$safeId$ext").absolutePath
    }

    override fun sizeOf(path: String): Long {
        val f = File(path)
        return if (f.exists()) f.length() else 0L
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun append(path: String, bytes: ByteArray, len: Int) {
        val f = File(path)
        f.parentFile?.let { if (!it.exists()) it.mkdirs() }
        java.io.FileOutputStream(f, /* append = */ true).use { it.write(bytes, 0, len) }
    }

    override fun truncate(path: String) {
        val f = File(path)
        if (f.exists()) java.io.FileOutputStream(f, /* append = */ false).use { /* opens at 0 */ }
    }

    override fun delete(path: String): Boolean = File(path).let { it.exists() && it.delete() }
}
