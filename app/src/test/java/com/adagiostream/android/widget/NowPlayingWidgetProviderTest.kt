package com.adagiostream.android.widget

import android.content.Intent
import android.view.KeyEvent
import com.adagiostream.android.service.player.AudioPlaybackService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * beads_adagio-2np: the widget's play/pause tap must reach the media session even
 * when the app process is dead. Verifies the toggle intent targets
 * [AudioPlaybackService] directly with a play/pause media-button key event —
 * the same path Media3's MediaSessionService.onStartCommand() already handles for
 * headset/Bluetooth buttons — instead of a broadcast nothing listens for.
 */
@RunWith(RobolectricTestRunner::class)
class NowPlayingWidgetProviderTest {

    @Test
    fun `toggle intent targets AudioPlaybackService with media button action`() {
        val context = RuntimeEnvironment.getApplication()
        val intent = NowPlayingWidgetProvider.buildToggleIntent(context)

        assertEquals(Intent.ACTION_MEDIA_BUTTON, intent.action)
        assertEquals(AudioPlaybackService::class.java.name, intent.component?.className)
    }

    @Test
    fun `toggle intent carries a play-pause key event`() {
        val context = RuntimeEnvironment.getApplication()
        val intent = NowPlayingWidgetProvider.buildToggleIntent(context)

        val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, keyEvent?.keyCode)
        assertEquals(KeyEvent.ACTION_DOWN, keyEvent?.action)
    }
}
