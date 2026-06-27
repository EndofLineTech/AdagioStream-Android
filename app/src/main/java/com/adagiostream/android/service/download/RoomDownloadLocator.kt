package com.adagiostream.android.service.download

import com.adagiostream.android.service.library.db.DownloadDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [DownloadLocator] (baw.6.3).
 *
 * Keeps an in-memory snapshot of completed-download paths updated from a Room
 * Flow, so the synchronous [MusicPlaybackCoordinator] play path can answer
 * "is this track downloaded?" without suspending on a DB query.
 */
@Singleton
class RoomDownloadLocator @Inject constructor(
    downloadDao: DownloadDao,
) : DownloadLocator {

    private val snapshot = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            downloadDao.observeCompleted().collect { rows ->
                snapshot.clear()
                rows.forEach { row -> row.localPath?.let { snapshot[row.id] = it } }
            }
        }
    }

    override fun localPathFor(trackId: String): String? = snapshot[trackId]
}
