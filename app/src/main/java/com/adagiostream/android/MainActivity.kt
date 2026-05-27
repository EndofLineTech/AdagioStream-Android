package com.adagiostream.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.adagiostream.android.service.player.AudioPlaybackService
import com.adagiostream.android.ui.MainScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ContextCompat.startForegroundService(
            this, Intent(this, AudioPlaybackService::class.java)
        )
        setContent {
            MainScreen()
        }
    }
}
