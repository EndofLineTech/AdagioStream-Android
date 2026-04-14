package com.adagiostream.android

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.adagiostream.android.service.player.CastManager
import com.adagiostream.android.util.DebugLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AdagioStreamApp : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var castManager: CastManager

    override fun onCreate() {
        super.onCreate()
        DebugLogger.init(this)
        castManager.initialize()
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader = imageLoader
}
