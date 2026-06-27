package com.adagiostream.android.service.download

import org.junit.Assert.assertEquals
import org.junit.Test

/** Storage-accounting math for the download-management screen (baw.6.2 TDD). */
class StorageAccountingTest {

    @Test
    fun `totalBytes sums file sizes`() {
        assertEquals(3072L, StorageAccounting.totalBytes(listOf(1024L, 1024L, 1024L)))
    }

    @Test
    fun `totalBytes ignores negative sentinels`() {
        assertEquals(1024L, StorageAccounting.totalBytes(listOf(1024L, -1L)))
    }

    @Test
    fun `totalBytes of empty is zero`() {
        assertEquals(0L, StorageAccounting.totalBytes(emptyList()))
    }

    @Test
    fun `formatSize renders bytes`() {
        assertEquals("512 B", StorageAccounting.formatSize(512))
    }

    @Test
    fun `formatSize renders kilobytes`() {
        assertEquals("1.5 KB", StorageAccounting.formatSize(1536))
    }

    @Test
    fun `formatSize renders megabytes`() {
        assertEquals("1.5 MB", StorageAccounting.formatSize(1_572_864))
    }

    @Test
    fun `formatSize renders gigabytes`() {
        assertEquals("2.0 GB", StorageAccounting.formatSize(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `formatSize zero`() {
        assertEquals("0 B", StorageAccounting.formatSize(0))
    }
}
