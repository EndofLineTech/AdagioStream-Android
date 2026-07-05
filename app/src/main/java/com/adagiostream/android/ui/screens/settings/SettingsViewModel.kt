package com.adagiostream.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adagiostream.android.BuildConfig
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.AppearanceMode
import com.adagiostream.android.model.ArtworkDisplayMode
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.model.SortMode
import com.adagiostream.android.model.ChannelGroupingMode
import com.adagiostream.android.model.TextSizeMode
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.download.DownloadManager
import com.adagiostream.android.service.library.MusicLibraryRepository
import com.adagiostream.android.service.metadata.ESPNScoreService
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.player.VLCPlayerWrapper
import com.adagiostream.android.util.DebugLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val persistenceService: PersistenceService,
    private val accountManager: AccountManager,
    private val vlcPlayerWrapper: VLCPlayerWrapper,
    private val espnScoreService: ESPNScoreService,
    private val downloadManager: DownloadManager,
    private val musicLibraryRepository: MusicLibraryRepository,
) : ViewModel() {

    private val _settings = MutableStateFlow(persistenceService.loadSettingsSync().let {
        it.copy(bufferDurationSeconds = it.bufferDurationSeconds.coerceIn(5, 15))
    })
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val accountCount: StateFlow<Int> = accountManager.accounts
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val channelCount: StateFlow<Int> = accountManager.channels
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val favoritesCount: StateFlow<Int> = accountManager.channels
        .map { channels -> channels.count { it.isFavorite } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val bitrateKbps: StateFlow<Float> = vlcPlayerWrapper.bitrateKbps
    val playbackState: StateFlow<PlaybackState> = vlcPlayerWrapper.playbackState

    val favoriteChannels: StateFlow<List<Channel>> = accountManager.channels
        .map { channels -> channels.filter { it.isFavorite } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debugLogSize = MutableStateFlow(DebugLogger.logFileSize())

    init {
        viewModelScope.launch {
            val loaded = persistenceService.loadSettings()
            _settings.value = loaded.copy(
                bufferDurationSeconds = loaded.bufferDurationSeconds.coerceIn(5, 15),
            )
            DebugLogger.isEnabled = loaded.debugLoggingEnabled
            if (loaded.espnPollingIntervalSeconds == 0) {
                espnScoreService.setPollingEnabled(false)
            } else {
                espnScoreService.livePollIntervalMs = loaded.espnPollingIntervalSeconds * 1000L
            }
        }
    }

    fun reloadSettings() {
        viewModelScope.launch {
            _settings.value = persistenceService.loadSettings()
        }
    }

    fun updateBufferDuration(seconds: Int) {
        val clamped = seconds.coerceIn(5, 15)
        _settings.value = _settings.value.copy(bufferDurationSeconds = clamped)
        vlcPlayerWrapper.updateBufferDuration(clamped)
        save()
    }

    fun updateAppearanceMode(mode: AppearanceMode) {
        _settings.value = _settings.value.copy(appearanceMode = mode)
        save()
    }

    fun updateTextSizeMode(mode: TextSizeMode) {
        _settings.value = _settings.value.copy(textSizeMode = mode)
        save()
    }

    fun updateArtworkDisplayMode(mode: ArtworkDisplayMode) {
        _settings.value = _settings.value.copy(artworkDisplayMode = mode)
        save()
    }

    fun updateSortMode(mode: SortMode) {
        _settings.value = _settings.value.copy(sortMode = mode)
        accountManager.updateSortMode(mode)
        save()
    }

    fun updateGroupSortMode(mode: SortMode) {
        _settings.value = _settings.value.copy(groupSortMode = mode)
        accountManager.updateGroupSortMode(mode)
        save()
    }

    fun updateSortPrefixes(prefixes: List<String>) {
        _settings.value = _settings.value.copy(sortPrefixes = prefixes)
        accountManager.updateSortPrefixes(prefixes)
        save()
    }

    fun updateEspnPollingInterval(seconds: Int) {
        _settings.value = _settings.value.copy(espnPollingIntervalSeconds = seconds)
        accountManager.espnPollingIntervalSeconds = seconds
        if (seconds == 0) {
            espnScoreService.setPollingEnabled(false)
        } else {
            espnScoreService.livePollIntervalMs = seconds * 1000L
            espnScoreService.setPollingEnabled(true)
        }
        save()
    }

    fun updateChannelGroupingMode(mode: ChannelGroupingMode) {
        _settings.value = _settings.value.copy(channelGroupingMode = mode)
        accountManager.updateGroupingMode(mode)
        save()
    }

    fun updateDebugLogging(enabled: Boolean) {
        _settings.value = _settings.value.copy(debugLoggingEnabled = enabled)
        DebugLogger.isEnabled = enabled
        save()
    }

    /**
     * Toggles the Music tab alpha feature gate (baw.2.6).
     *
     * When enabled, the Music tab appears in the bottom nav and the Navidrome
     * library browse UI is accessible.  Intended for developer/alpha testing
     * before the feature is promoted to general availability.
     */
    fun updateMusicTabEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(musicTabEnabled = enabled)
        save()
    }

    fun clearDebugLogs() {
        DebugLogger.clearLogs()
        debugLogSize.value = DebugLogger.logFileSize()
    }

    fun refreshDebugLogSize() {
        debugLogSize.value = DebugLogger.logFileSize()
    }

    fun updateStartupStream(channelId: String?) {
        _settings.value = _settings.value.copy(startupStreamID = channelId)
        save()
    }

    val channels: StateFlow<List<Channel>> = accountManager.channels

    fun reloadChannels() {
        viewModelScope.launch {
            accountManager.loadAllChannels()
        }
    }

    fun playStartupStream() {
        val streamId = _settings.value.startupStreamID ?: return
        val channel = accountManager.channels.value.find { it.id == streamId } ?: return
        vlcPlayerWrapper.setChannelList(accountManager.channels.value)
        vlcPlayerWrapper.play(channel)
    }

    fun clearAllFavorites() {
        viewModelScope.launch {
            accountManager.clearAllFavorites()
        }
    }

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _exportJson = MutableStateFlow<String?>(null)
    val exportJson: StateFlow<String?> = _exportJson.asStateFlow()

    fun clearExportJson() {
        _exportJson.value = null
    }

    private val _dataDeleted = MutableStateFlow(false)
    val dataDeleted: StateFlow<Boolean> = _dataDeleted.asStateFlow()

    fun clearDataDeleted() {
        _dataDeleted.value = false
    }

    fun exportMyData() {
        _isExporting.value = true
        viewModelScope.launch {
            try {
                val prettyJson = Json { prettyPrint = true }
                val accounts = accountManager.accounts.value
                val favorites = accountManager.channels.value.filter { it.isFavorite }.map { it.id }
                val lovedTracks = persistenceService.loadLovedTracks()
                val playlists = persistenceService.loadCustomPlaylists()
                val settings = persistenceService.loadSettings()

                val export = buildJsonObject {
                    put("exportDate", JsonPrimitive(DateTimeFormatter.ISO_INSTANT.format(Instant.now())))
                    put("appVersion", JsonPrimitive(BuildConfig.VERSION_NAME))
                    put("appBuild", JsonPrimitive(BuildConfig.VERSION_CODE))

                    put("accounts", buildJsonArray {
                        accounts.forEach { account ->
                            add(buildJsonObject {
                                put("id", JsonPrimitive(account.id))
                                put("name", JsonPrimitive(account.name))
                                put("isEnabled", JsonPrimitive(account.isEnabled))
                                when (val type = account.type) {
                                    is AccountType.M3U -> {
                                        put("type", JsonPrimitive("m3u"))
                                        put("url", JsonPrimitive(type.url))
                                        type.epgUrl?.let { put("epgUrl", JsonPrimitive(it)) }
                                    }
                                    is AccountType.XtreamCodes -> {
                                        put("type", JsonPrimitive("xtream"))
                                        put("host", JsonPrimitive(type.host))
                                        put("username", JsonPrimitive(type.username))
                                        // Password intentionally omitted
                                    }
                                    // Subsonic accounts: type only; credentials omitted intentionally.
                                    is AccountType.Subsonic -> {
                                        put("type", JsonPrimitive("subsonic"))
                                        put("host", JsonPrimitive(type.host))
                                        put("username", JsonPrimitive(type.username))
                                        // Password intentionally omitted from diagnostic export
                                    }
                                }
                            })
                        }
                    })

                    put("favorites", buildJsonArray {
                        favorites.forEach { add(JsonPrimitive(it)) }
                    })

                    put("lovedTracks", prettyJson.encodeToJsonElement(lovedTracks))
                    put("customPlaylists", prettyJson.encodeToJsonElement(playlists))

                    put("favoriteGroups", buildJsonArray {
                        settings.favoriteGroupOrder.forEach { add(JsonPrimitive(it)) }
                    })

                    put("enabledGroups", if (settings.enabledGroups != null) {
                        buildJsonArray {
                            settings.enabledGroups.forEach { add(JsonPrimitive(it)) }
                        }
                    } else {
                        JsonPrimitive("all")
                    })

                    put("settings", prettyJson.encodeToJsonElement(settings))
                }

                _exportJson.value = prettyJson.encodeToString(JsonObject.serializer(), export)
            } catch (e: Exception) {
                _exportJson.value = null
            } finally {
                _isExporting.value = false
            }
        }
    }

    fun deleteAllData() {
        viewModelScope.launch {
            vlcPlayerWrapper.stop()
            accountManager.resetAll()
            persistenceService.deleteAllData()
            // E6: also clear the Navidrome cache DB + downloaded files (delete-all path).
            downloadManager.deleteAll()
            musicLibraryRepository.clearLibraryCache()
            DebugLogger.clearLogs()
            _dataDeleted.value = true
        }
    }

    private fun save() {
        viewModelScope.launch {
            // Read-modify-write (baw.16): shuffleEnabled/repeatMode are owned and
            // persisted by MusicPlaybackCoordinator (baw.9.4). This VM's _settings
            // is a snapshot from construction time, so whole-object saving it
            // would clobber playback state persisted since. Preserve the
            // coordinator-owned fields from disk; save everything else from here.
            val persisted = persistenceService.loadSettings()
            persistenceService.saveSettings(
                _settings.value.copy(
                    shuffleEnabled = persisted.shuffleEnabled,
                    repeatMode = persisted.repeatMode,
                ),
            )
        }
    }
}
