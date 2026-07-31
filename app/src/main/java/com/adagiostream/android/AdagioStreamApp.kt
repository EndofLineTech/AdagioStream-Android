package com.adagiostream.android

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.adagiostream.android.service.download.DownloadManager
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

    override fun onCreate() {
        super.onCreate()
        DebugLogger.init(this)
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
