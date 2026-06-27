package com.adagiostream.android.ui.screens.music

import com.adagiostream.android.service.library.db.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** Maps stored download status → 5-state button UI state (baw.6.2). */
class DownloadUiStateMappingTest {

    @Test
    fun `queued maps to QUEUED`() {
        assertEquals(DownloadUiState.QUEUED, DownloadStatus.QUEUED.toUiState())
    }

    @Test
    fun `downloading maps to DOWNLOADING`() {
        assertEquals(DownloadUiState.DOWNLOADING, DownloadStatus.DOWNLOADING.toUiState())
    }

    @Test
    fun `completed maps to COMPLETED`() {
        assertEquals(DownloadUiState.COMPLETED, DownloadStatus.COMPLETED.toUiState())
    }

    @Test
    fun `failed and paused both map to FAILED (both offer retry)`() {
        assertEquals(DownloadUiState.FAILED, DownloadStatus.FAILED.toUiState())
        assertEquals(DownloadUiState.FAILED, DownloadStatus.PAUSED.toUiState())
    }

    @Test
    fun `unknown maps to NOT_DOWNLOADED`() {
        assertEquals(DownloadUiState.NOT_DOWNLOADED, "garbage".toUiState())
    }
}
