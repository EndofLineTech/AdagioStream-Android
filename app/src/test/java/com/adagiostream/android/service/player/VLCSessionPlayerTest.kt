package com.adagiostream.android.service.player

import android.os.Looper
import androidx.media3.common.Player
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.PlaybackState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Regression guard for the Play Store crash (beads_adagio-un5):
 * "Empty playlist only allowed in STATE_IDLE or STATE_ENDED". VLC can report
 * Playing while the session has no content (no channel, no queue); any
 * session command then forces SimpleBasePlayer to build state, which used
 * to throw. getState must coerce to STATE_IDLE instead.
 */
@RunWith(RobolectricTestRunner::class)
class VLCSessionPlayerTest {

    @Test
    fun `playing state with no content reports IDLE instead of crashing`() {
        val wrapper = mockk<VLCPlayerWrapper>(relaxed = true) {
            every { playbackSource } returns MutableStateFlow(null)
            every { playbackState } returns MutableStateFlow<PlaybackState>(PlaybackState.Playing)
            every { currentChannel } returns MutableStateFlow<Channel?>(null)
        }

        val player = VLCSessionPlayer(wrapper)
        // Run the Main-dispatcher collectors so currentPlaybackState becomes READY.
        shadowOf(Looper.getMainLooper()).idle()

        // Collector sanity: Playing must have propagated (playWhenReady true).
        assertEquals(true, player.playWhenReady)
        // Reading playbackState forces SimpleBasePlayer to build State —
        // pre-fix this threw IllegalArgumentException.
        assertEquals(Player.STATE_IDLE, player.playbackState)
    }

    /**
     * Regression guard for GH#9 (beads_adagio-268): two channels sharing an id
     * (same tvg-id via SXM matching) used to produce duplicate MediaItemData
     * UIDs — "Duplicate MediaItemData UID in playlist".
     */
    @Test
    fun `duplicate channel ids build a valid playlist instead of crashing`() {
        val dupA = Channel(id = "sxm-1", name = "The Highway", streamURL = "http://a", group = "SXM")
        val dupB = Channel(id = "sxm-1", name = "The Highway", streamURL = "http://b", group = "SXM")
        val wrapper = mockk<VLCPlayerWrapper>(relaxed = true) {
            every { playbackSource } returns MutableStateFlow(null)
            every { playbackState } returns MutableStateFlow<PlaybackState>(PlaybackState.Playing)
            every { currentChannel } returns MutableStateFlow<Channel?>(dupA)
            every { channelList } returns listOf(dupA, dupB)
        }

        val player = VLCSessionPlayer(wrapper)
        shadowOf(Looper.getMainLooper()).idle()

        // Forcing state build used to throw IllegalArgumentException.
        assertEquals(Player.STATE_READY, player.playbackState)
        assertEquals(2, player.mediaItemCount)
    }
}
