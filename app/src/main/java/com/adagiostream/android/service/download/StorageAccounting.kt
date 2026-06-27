package com.adagiostream.android.service.download

/**
 * Pure storage-accounting math for the download-management screen (baw.6.2).
 *
 * Sizes come from actual files on disk (File.length), summed here — never from a
 * DB byte counter, which can drift from reality after a partial/interrupted write.
 */
object StorageAccounting {

    /** Total bytes across [fileSizes]. */
    fun totalBytes(fileSizes: List<Long>): Long = fileSizes.sumOf { it.coerceAtLeast(0) }

    /**
     * Human-readable size string (binary units, 1 decimal place above KB).
     *
     * 0 → "0 B", 1536 → "1.5 KB", 1_572_864 → "1.5 MB".
     */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return "%.1f %s".format(value, units[unitIndex])
    }
}
