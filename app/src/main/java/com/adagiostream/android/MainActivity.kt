package com.adagiostream.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfOidc
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfOidcCallbackBus
import com.adagiostream.android.service.player.AudioPlaybackService
import com.adagiostream.android.ui.MainScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var oidcCallbacks: AudiobookshelfOidcCallbackBus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ContextCompat.startForegroundService(
            this, Intent(this, AudioPlaybackService::class.java)
        )
        setContent {
            MainScreen()
        }
        handleOidcCallback(intent)
    }

    // launchMode="singleTask" routes the adagiostream://oauth redirect here,
    // clearing the Custom Tab above this activity so the in-progress
    // AddAccount ViewModel (which holds the flow state) is still alive.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOidcCallback(intent)
    }

    /**
     * Forwards an `adagiostream://oauth` OIDC redirect to the flow bus. The
     * URL carries the authorization code — it is never logged.
     */
    private fun handleOidcCallback(intent: Intent?) {
        val url = intent?.dataString ?: return
        if (url.startsWith(AudiobookshelfOidc.REDIRECT_URI)) {
            oidcCallbacks.publish(url)
        }
    }
}
