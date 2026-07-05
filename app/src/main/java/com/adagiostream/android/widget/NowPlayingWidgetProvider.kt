package com.adagiostream.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.RemoteViews
import com.adagiostream.android.MainActivity
import com.adagiostream.android.R
import com.adagiostream.android.service.player.AudioPlaybackService

class NowPlayingWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val PREFS_NAME = "widget_prefs"
        private const val KEY_CHANNEL = "channel_name"
        private const val KEY_STATUS = "status"
        private const val KEY_IS_PLAYING = "is_playing"

        fun updateWidget(
            context: Context,
            channelName: String?,
            status: String?,
            isPlaying: Boolean,
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CHANNEL, channelName)
                .putString(KEY_STATUS, status)
                .putBoolean(KEY_IS_PLAYING, isPlaying)
                .apply()

            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                val views = buildRemoteViews(context, channelName, status, isPlaying)
                manager.updateAppWidget(ids, views)
            }
        }

        private fun buildRemoteViews(
            context: Context,
            channelName: String?,
            status: String?,
            isPlaying: Boolean,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_playing)

            views.setTextViewText(R.id.widget_channel_name, channelName ?: "Not Playing")
            views.setTextViewText(R.id.widget_status, status ?: "")
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            )

            // Tap widget body -> open app
            val openIntent = Intent(context, MainActivity::class.java)
            val openPending = PendingIntent.getActivity(
                context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_channel_name, openPending)
            views.setOnClickPendingIntent(R.id.widget_artwork, openPending)

            // Tap play/pause -> toggle. Send a media-button intent straight to the
            // playback service (same mechanism headset/Bluetooth buttons use); Media3's
            // MediaSessionService.onStartCommand() dispatches it to the session's player.
            // getForegroundService starts the service if the app process is dead.
            val togglePending = PendingIntent.getForegroundService(
                context, 1, buildToggleIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_play_pause, togglePending)

            return views
        }

        /** Visible for testing: the media-button intent used to toggle play/pause. */
        internal fun buildToggleIntent(context: Context): Intent {
            return Intent(Intent.ACTION_MEDIA_BUTTON, null, context, AudioPlaybackService::class.java).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val channelName = prefs.getString(KEY_CHANNEL, null)
        val status = prefs.getString(KEY_STATUS, null)
        val isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false)
        val views = buildRemoteViews(context, channelName, status, isPlaying)
        appWidgetManager.updateAppWidget(appWidgetIds, views)
    }
}
