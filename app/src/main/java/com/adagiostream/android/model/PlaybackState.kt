package com.adagiostream.android.model

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Buffering : PlaybackState
    data object Playing : PlaybackState
    data object Paused : PlaybackState
    data class Error(val message: String) : PlaybackState
}
