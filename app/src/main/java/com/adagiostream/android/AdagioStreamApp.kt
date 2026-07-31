package com.adagiostream.android

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.adagiostream.android.service.download.DownloadManager
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.player.CastManager
import com.adagiostream.android.util.DebugLogger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AdagioStreamApp : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var castManager: CastManager

    @Inject
    lateinit var downloadManager: DownloadManager

    @Inject
    lateinit var persistenceService: PersistenceService

    override fun onCreate() {
        super.onCreate()
        DebugLogger.init(this)
        // Enable logging from process start — SettingsViewModel re-applies this
        // later, but anything logged before the UI spins up (startup download
        // kick, service starts) was previously lost (beads_adagio-0wj).
        DebugLogger.isEnabled = persistenceService.loadSettingsSync().debugLoggingEnabled
        installCrashHandler()
        castManager.initialize()
        // Re-kick the download drainer for rows stranded across an app update
        // or process death (beads_adagio-0wj). Fire-and-forget: the drainer
        // no-ops fast when nothing is actually pending.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            downloadManager.resumePendingOnStartup()
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DebugLogger.logCrash(thread, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader = imageLoader
}
