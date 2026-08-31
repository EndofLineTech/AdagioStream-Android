package com.adagiostream.android.service.account

import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.ChannelGroup
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.model.LovedTrack
import com.adagiostream.android.model.ChannelGroupingMode
import com.adagiostream.android.model.SXMMetadataSource
import com.adagiostream.android.model.SxmChannelGroupPolicy
import com.adagiostream.android.model.SortMode
import com.adagiostream.android.model.TrackMetadata
import com.adagiostream.android.model.ESPNGameInfo
import com.adagiostream.android.service.metadata.ESPNScoreService
import com.adagiostream.android.service.metadata.SXMMetadataService
import com.adagiostream.android.service.parsing.EPGParser
import com.adagiostream.android.service.parsing.M3UParser
import com.adagiostream.android.service.parsing.XtreamCodesApi
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.persistence.SettingsLoadResult
import com.adagiostream.android.service.playlist.CustomPlaylistManager
import com.adagiostream.android.util.DebugLogger
import com.adagiostream.android.util.UrlSanitizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountManager @Inject constructor(
    private val persistenceService: PersistenceService,
    private val client: OkHttpClient,
    private val sxmMetadataService: SXMMetadataService,
    private val espnScoreService: ESPNScoreService,
    private val customPlaylistManager: CustomPlaylistManager,
) : AccountRepository {
    companion object {
        /**
         * Whether [title] is just the stream URL's last path component (e.g.
         * "20998.ts") — beads_adagio-59p.3.5. On Android track titles only
         * enter now-playing metadata from the metadata APIs (libVLC/Media3
         * stream meta is never read into [trackMetadata]), so this leak can't
         * currently happen here — the guard sits at the single choke point
         * anyway because it's cheap and the iOS player did leak exactly this.
         */
        fun isStreamUrlFilename(title: String, streamUrl: String): Boolean {
            val path = streamUrl
                .substringAfter("://", streamUrl)
                .substringAfter('/', missingDelimiterValue = "")
                .substringBefore('?')
                .substringBefore('#')
            val lastComponent = path.trimEnd('/').substringAfterLast('/')
            return lastComponent.isNotEmpty() && title.trim().equals(lastComponent, ignoreCase = true)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val m3uParser = M3UParser(client)
    private val xtreamApi = XtreamCodesApi(client)
    private val epgParser = EPGParser(client)

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    override val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _groups = MutableStateFlow<List<ChannelGroup>>(emptyList())
    val groups: StateFlow<List<ChannelGroup>> = _groups.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _epgEntries = MutableStateFlow<Map<String, List<EPGEntry>>>(emptyMap())
    val epgEntries: StateFlow<Map<String, List<EPGEntry>>> = _epgEntries.asStateFlow()

    private val _trackMetadata = MutableStateFlow<Map<String, TrackMetadata>>(emptyMap())
    val trackMetadata: StateFlow<Map<String, TrackMetadata>> = _trackMetadata.asStateFlow()
    private var trackMetadataJob: Job? = null

    /**
     * The channel now-playing polling was last asked to track — kept even when
     * it has no station mapping yet, so a late station-list success can attach
     * metadata to it retroactively (beads_adagio-59p.3.4).
     */
    private var currentMetadataChannel: Channel? = null

    /**
     * Live app settings, exposed for consumers that compose now-playing
     * metadata (e.g. the prefer-live-scores gate in VLCSessionPlayer,
     * beads_adagio-59p.3.3).
     */
    val appSettings: StateFlow<AppSettings> get() = persistenceService.settings

    // Feed metadata keyed by XM slug — used for channel list subtitles
    private val _feedMetadata = MutableStateFlow<Map<String, TrackMetadata>>(emptyMap())
    val feedMetadata: StateFlow<Map<String, TrackMetadata>> = _feedMetadata.asStateFlow()
    private var feedJob: Job? = null
    private var sxmRuntimeGeneration = 0

    private val _rawChannelGroupInventory =
        MutableStateFlow<RawChannelGroupInventory>(RawChannelGroupInventory.Loading)
    val rawChannelGroupInventory: StateFlow<RawChannelGroupInventory> =
        _rawChannelGroupInventory.asStateFlow()

    private val _sxmChannelGroups = MutableStateFlow<Set<String>?>(null)
    val sxmChannelGroups: StateFlow<Set<String>?> = _sxmChannelGroups.asStateFlow()

    private val _sxmSelectionSaveError = MutableStateFlow<String?>(null)
    val sxmSelectionSaveError: StateFlow<String?> = _sxmSelectionSaveError.asStateFlow()

    private var channelAccountCount = 0
    private var channelLoadErrors: List<String> = emptyList()
    private var rawProviderChannels: List<Channel> = emptyList()
    private var providerInventoryLoaded = false
    private var settingsReadyForMigration = false
    private val channelLoadGeneration = AtomicLong(0)
    private val sxmSelectionMutex = Mutex()
    private var sxmMigrationJob: Job? = null

    private val _lovedTracks = MutableStateFlow<List<LovedTrack>>(emptyList())
    val lovedTracks: StateFlow<List<LovedTrack>> = _lovedTracks.asStateFlow()

    private var favoriteIds = mutableListOf<String>()
    private val _favoriteKeys = MutableStateFlow<Set<String>>(emptySet())
    val favoriteKeys: StateFlow<Set<String>> = _favoriteKeys.asStateFlow()
    var sortPrefixes: List<String> = listOf("Radio: ", "TV: ")
        private set
    var sortMode: SortMode = SortMode.ALPHABETICAL
        private set
    var groupSortMode: SortMode = SortMode.ALPHABETICAL
        private set
    var groupingMode: ChannelGroupingMode = ChannelGroupingMode.ALL_GROUPS
        private set
    var espnPollingIntervalSeconds: Int = 15
    /**
     * SXM now-playing poll interval (beads_adagio-59p.3.2). Set from persisted
     * settings on load and by SettingsViewModel on change; consumed clamped
     * (10–45s) so stored garbage can't produce a runaway timer.
     */
    var sxmPollIntervalSeconds: Int = AppSettings.SXM_POLL_INTERVAL_DEFAULT

    // Group management
    private var enabledGroups: MutableSet<String>? = null  // null = all enabled
    private val _enabledGroupNames = MutableStateFlow<Set<String>?>(null)
    val enabledGroupNames: StateFlow<Set<String>?> = _enabledGroupNames.asStateFlow()
    private var favoriteGroupOrder = mutableListOf<String>()
    private val _allGroupNames = MutableStateFlow<Set<String>>(emptySet())
    val allGroupNames: StateFlow<Set<String>> = _allGroupNames.asStateFlow()
    private val _favoriteGroupNames = MutableStateFlow<Set<String>>(emptySet())
    val favoriteGroupNames: StateFlow<Set<String>> = _favoriteGroupNames.asStateFlow()

    val espnGames: StateFlow<Map<String, ESPNGameInfo>> = espnScoreService.gamesByChannel

    fun resetAll() {
        customPlaylistManager.resetAll()
        _accounts.value = emptyList()
        _channels.value = emptyList()
        _groups.value = emptyList()
        _allGroupNames.value = emptySet()
        _lovedTracks.value = emptyList()
        _trackMetadata.value = emptyMap()
        _feedMetadata.value = emptyMap()
        favoriteIds = mutableListOf()
        _favoriteKeys.value = emptySet()
        enabledGroups = null
        _enabledGroupNames.value = null
        favoriteGroupOrder = mutableListOf()
        sortPrefixes = listOf("Radio: ", "TV: ")
        sortMode = SortMode.ALPHABETICAL
        groupSortMode = SortMode.ALPHABETICAL
        groupingMode = ChannelGroupingMode.ALL_GROUPS
        channelAccountCount = 0
        channelLoadErrors = emptyList()
        rawProviderChannels = emptyList()
        providerInventoryLoaded = true
        settingsReadyForMigration = true
        _sxmChannelGroups.value = emptySet()
        _rawChannelGroupInventory.value = RawChannelGroupInventory.NoAccounts
        invalidateSxmRuntime(keepCurrentChannel = false)
    }

    private var hasSxmChannels = false

    private val _initialLoadComplete = CompletableDeferred<Unit>()

    /** Suspends until the initial channel load from init has completed. */
    suspend fun awaitInitialLoad() = _initialLoadComplete.await()

    init {
        // A matching table landing (possibly minutes after launch thanks to the
        // retry loop, beads_adagio-59p.3.4) drives SXM state: feed polling
        // starts, and the already-playing channel gets its metadata attached
        // late by re-running the polling entry point. Restarting an
        // already-mapped poll is harmless (one extra immediate fetch).
        sxmMetadataService.onMappingBuilt = {
            hasSxmChannels = sxmMetadataService.hasMappedChannels()
            startFeedPollingIfNeeded()
            currentMetadataChannel?.let { startTrackMetadataPolling(it) }
        }
        scope.launch {
            val settingsResult = persistenceService.loadSettingsResult()
            val settings = settingsResult.settings ?: AppSettings(sxmChannelGroups = null)
            settingsReadyForMigration = settingsResult !is SettingsLoadResult.Failure
            if (!settingsReadyForMigration) {
                _sxmSelectionSaveError.value = "Settings could not be loaded. SiriusXM group migration is paused."
            }
            sortPrefixes = settings.sortPrefixes
            sortMode = settings.sortMode
            groupSortMode = settings.groupSortMode
            groupingMode = settings.channelGroupingMode
            espnPollingIntervalSeconds = settings.espnPollingIntervalSeconds
            sxmPollIntervalSeconds = settings.sxmPollIntervalSeconds
            _sxmChannelGroups.value = settings.sxmChannelGroups
            enabledGroups = settings.enabledGroups?.toMutableSet()
            _enabledGroupNames.value = enabledGroups?.toSet()
            favoriteGroupOrder = settings.favoriteGroupOrder.toMutableList()
            _favoriteGroupNames.value = favoriteGroupOrder.toSet()
            _accounts.value = persistenceService.loadAccounts()
            favoriteIds = persistenceService.loadFavoriteIds().toMutableList()
            _favoriteKeys.value = favoriteIds.toSet()
            _lovedTracks.value = persistenceService.loadLovedTracks()
            loadAllChannels()
            _initialLoadComplete.complete(Unit)
        }
        scope.launch {
            combine(
                customPlaylistManager.playlists,
                customPlaylistManager.isLoaded,
                customPlaylistManager.loadError,
            ) { _, _, _ -> Unit }.collect {
                refreshRawInventoryAndSxm()
            }
        }
    }

    private fun startFeedPollingIfNeeded() {
        if (!hasSxmChannels) {
            feedJob?.cancel()
            feedJob = null
            return
        }
        if (feedJob != null) return // already polling
        val generation = sxmRuntimeGeneration
        feedJob = scope.launch {
            while (true) {
                val feed = sxmMetadataService.getFeed()
                if (generation != sxmRuntimeGeneration) return@launch
                if (feed.isNotEmpty()) {
                    _feedMetadata.value = feed
                    DebugLogger.log("Feed loaded: ${feed.size} channels with track data", DebugLogger.Category.SXM)
                }
                delay(30_000L)
            }
        }
    }

    data class AddAccountResult(
        val channelCount: Int,
        val newGroupCount: Int,
    )

    suspend fun addAccount(account: Account, enableAllGroups: Boolean = false): AddAccountResult {
        val existingGroups = _allGroupNames.value.toSet()
        val updated = _accounts.value + account
        _accounts.value = updated
        persistenceService.saveAccounts(updated)
        loadAllChannels()

        val newGroups = _allGroupNames.value - existingGroups
        // When enableAllGroups is true (first-time setup), ensure all groups are enabled
        if (enableAllGroups) {
            enabledGroups = null
            _enabledGroupNames.value = null
            saveGroupSettings()
            rebuildGroups()
        } else if (existingGroups.isNotEmpty() && newGroups.isNotEmpty()) {
            for (groupName in newGroups) {
                if (enabledGroups == null) {
                    // Initialize enabledGroups with all existing groups (excluding new ones)
                    enabledGroups = (existingGroups).toMutableSet()
                } else {
                    // New groups are already not in enabledGroups, so they're disabled
                }
            }
            _enabledGroupNames.value = enabledGroups?.toSet()
            saveGroupSettings()
            rebuildGroups()
        }

        val newChannelCount = _channels.value.count { it.group in _allGroupNames.value }
        return AddAccountResult(
            channelCount = newChannelCount,
            newGroupCount = newGroups.size,
        )
    }

    // -------------------------------------------------------------------------
    // Account-edit listeners (beads_adagio-59p.1.4)
    // -------------------------------------------------------------------------

    /**
     * Invoked synchronously with the account id on every [updateAccount] /
     * [deleteAccount] — NOT on [updateAccountCredentials], which is the
     * token-rotation persistence path. Lets AudiobookshelfApiProvider evict
     * its cached API when an account is edited (fresh login mints new tokens)
     * or deleted, while rotation re-emits keep the same live instance.
     */
    private val accountEditListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** Registers [listener] for account edit/delete events. Never unregistered — callers are singletons. */
    fun addAccountEditListener(listener: (String) -> Unit) {
        accountEditListeners += listener
    }

    private fun notifyAccountEdited(accountId: String) {
        accountEditListeners.forEach { it(accountId) }
    }

    suspend fun updateAccount(account: Account) {
        // Evict caches BEFORE the accounts flow re-emits so collectors never
        // resolve against a stale API instance (beads_adagio-59p.1.4 B1).
        notifyAccountEdited(account.id)
        updateAccountCredentials(account)
        loadAllChannels()
    }

    /**
     * Persist-only account update (beads_adagio-59p.1.5): swaps [account] into
     * the store WITHOUT re-fetching channel lists — for credential/token
     * rotations (e.g. Audiobookshelf JWT refresh) where nothing channel-related
     * changed.
     */
    suspend fun updateAccountCredentials(account: Account) {
        val updated = _accounts.value.map { if (it.id == account.id) account else it }
        _accounts.value = updated
        persistenceService.saveAccounts(updated)
    }

    suspend fun toggleAccountEnabled(accountId: String) {
        val updated = _accounts.value.map {
            if (it.id == accountId) it.copy(isEnabled = !it.isEnabled) else it
        }
        _accounts.value = updated
        persistenceService.saveAccounts(updated)
        loadAllChannels()
    }

    suspend fun deleteAccount(accountId: String) {
        notifyAccountEdited(accountId)
        val updated = _accounts.value.filter { it.id != accountId }
        _accounts.value = updated
        persistenceService.saveAccounts(updated)
        loadAllChannels()
    }

    suspend fun loadAllChannels() {
        val generation = channelLoadGeneration.incrementAndGet()
        _isLoading.value = true
        _error.value = null
        _rawChannelGroupInventory.value = RawChannelGroupInventory.Loading
        providerInventoryLoaded = false
        sxmMigrationJob?.cancel()
        invalidateSxmRuntime(keepCurrentChannel = true)
        sxmMetadataService.matchChannels(emptyList(), _sxmChannelGroups.value.orEmpty(), sortPrefixes)

        try {
            val visibleChannels = mutableListOf<Channel>()
            val inventoryChannels = mutableListOf<Channel>()
            val loadErrors = mutableListOf<String>()
            val channelAccounts = _accounts.value.filter {
                it.type is AccountType.M3U || it.type is AccountType.XtreamCodes
            }
            for (account in channelAccounts) {
                try {
                    val channels = when (account.type) {
                        is AccountType.M3U -> m3uParser.parse(account.type.url)
                        is AccountType.XtreamCodes -> xtreamApi.getChannels(account.type)
                        else -> emptyList()
                    }
                    val strip = (account.type as? AccountType.XtreamCodes)?.stripStreamIDs == true
                    val accountChannels = channels.map {
                        it.copy(
                            id = "${account.id}:${it.id}",
                            accountName = account.name,
                            name = if (strip) it.name.replace(Regex("""^\d+\s*[|:\-.]+\s*"""), "") else it.name,
                        )
                    }
                    inventoryChannels.addAll(accountChannels)
                    if (account.isEnabled) visibleChannels.addAll(accountChannels)
                } catch (e: Exception) {
                    loadErrors.add("Failed to load ${account.name}: ${UrlSanitizer.redact(e.message ?: "Unknown error")}")
                }
            }
            if (generation != channelLoadGeneration.get()) return
            // Only surface errors if no channels loaded at all
            if (visibleChannels.isEmpty() && loadErrors.isNotEmpty()) {
                _error.value = loadErrors.first()
            }

            val withFavorites = visibleChannels.map { channel ->
                val favKey = favoriteKey(channel)
                channel.copy(isFavorite = favKey in favoriteIds)
            }

            _channels.value = withFavorites
            rawProviderChannels = inventoryChannels
            rebuildGroups()
            channelAccountCount = channelAccounts.size
            channelLoadErrors = loadErrors.toList()
            providerInventoryLoaded = true
            refreshRawInventoryAndSxm(generation)
            loadEPG()
            if (generation != channelLoadGeneration.get()) return
            espnScoreService.epgDataProvider = { _epgEntries.value }
            espnScoreService.matchChannels(withFavorites, sortPrefixes)
            if (espnPollingIntervalSeconds > 0) {
                espnScoreService.setPollingEnabled(true)
            }
        } catch (e: Exception) {
            if (generation != channelLoadGeneration.get()) return
            _error.value = UrlSanitizer.redact(e.message ?: "Unknown error")
            channelLoadErrors = listOf(_error.value ?: "Channel inventory failed")
            providerInventoryLoaded = true
            refreshRawInventoryAndSxm(generation)
        } finally {
            if (generation == channelLoadGeneration.get()) _isLoading.value = false
        }
    }

    suspend fun toggleFavorite(channel: Channel) {
        val key = favoriteKey(channel)
        if (key in favoriteIds) {
            favoriteIds.remove(key)
        } else {
            favoriteIds.add(key)
        }
        persistenceService.saveFavoriteIds(favoriteIds.toList())
        _favoriteKeys.value = favoriteIds.toSet()

        _channels.value = _channels.value.map {
            if (favoriteKey(it) == key) it.copy(isFavorite = key in favoriteIds) else it
        }
        rebuildGroups()
    }

    suspend fun reorderFavorites(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val item = favoriteIds.removeAt(fromIndex)
        favoriteIds.add(toIndex, item)
        persistenceService.saveFavoriteIds(favoriteIds.toList())
    }

    fun favoriteIndexOf(channel: Channel): Int {
        return favoriteIds.indexOf(favoriteKey(channel))
    }

    suspend fun clearAllFavorites() {
        favoriteIds.clear()
        _favoriteKeys.value = emptySet()
        persistenceService.clearAllFavorites()
        _channels.value = _channels.value.map { it.copy(isFavorite = false) }
        rebuildGroups()
    }

    fun updateSortPrefixes(prefixes: List<String>) {
        sortPrefixes = prefixes
        rebuildGroups()
        rematchSxmChannels()
    }

    fun updateSortMode(mode: SortMode) {
        sortMode = mode
        rebuildGroups()
    }

    fun updateGroupSortMode(mode: SortMode) {
        groupSortMode = mode
        rebuildGroups()
    }

    fun updateGroupingMode(mode: ChannelGroupingMode) {
        groupingMode = mode
        rebuildGroups()
    }

    fun isGroupEnabled(groupName: String): Boolean {
        return enabledGroups?.contains(groupName) ?: true
    }

    fun isGroupFavorite(groupName: String): Boolean {
        return groupName in favoriteGroupOrder
    }

    suspend fun toggleGroupEnabled(groupName: String) {
        if (enabledGroups == null) {
            // First toggle: enable all groups except this one
            enabledGroups = _allGroupNames.value.toMutableSet().also { it.remove(groupName) }
        } else if (groupName in enabledGroups!!) {
            enabledGroups!!.remove(groupName)
        } else {
            enabledGroups!!.add(groupName)
        }
        _enabledGroupNames.value = enabledGroups?.toSet()
        saveGroupSettings()
        rebuildGroups()
    }

    suspend fun toggleGroupFavorite(groupName: String) {
        if (groupName in favoriteGroupOrder) {
            favoriteGroupOrder.remove(groupName)
        } else {
            favoriteGroupOrder.add(groupName)
        }
        _favoriteGroupNames.value = favoriteGroupOrder.toSet()
        saveGroupSettings()
        rebuildGroups()
    }

    suspend fun updateFavoriteGroupOrder(order: List<String>) {
        favoriteGroupOrder = order.toMutableList()
        _favoriteGroupNames.value = favoriteGroupOrder.toSet()
        saveGroupSettings()
        rebuildGroups()
    }

    suspend fun setAllGroupsEnabled(enabled: Boolean) {
        enabledGroups = if (enabled) null else mutableSetOf()
        _enabledGroupNames.value = enabledGroups?.toSet()
        saveGroupSettings()
        rebuildGroups()
    }

    private suspend fun saveGroupSettings() {
        persistenceService.updateSettings { settings ->
            settings.copy(
                enabledGroups = enabledGroups?.toSet(),
                favoriteGroupOrder = favoriteGroupOrder.toList(),
            )
        }
    }

    suspend fun loadXtreamEPGForChannel(channel: Channel) {
        val streamId = channel.xtreamStreamId ?: return
        val account = _accounts.value.find {
            it.type is AccountType.XtreamCodes
        } ?: return
        val config = account.type as AccountType.XtreamCodes

        try {
            val entries = xtreamApi.getShortEPG(streamId, config)
            if (entries.isNotEmpty()) {
                val updated = _epgEntries.value.toMutableMap()
                val channelId = channel.epgChannelID ?: channel.id
                updated[channelId] = entries
                _epgEntries.value = updated
            }
        } catch (_: Exception) {
            // Silently fail — EPG is best-effort
        }
    }

    private suspend fun loadEPG() {
        val allEntries = mutableMapOf<String, List<EPGEntry>>()

        for (account in _accounts.value) {
            when (val type = account.type) {
                is AccountType.M3U -> {
                    val epgUrl = type.epgUrl
                    if (!epgUrl.isNullOrBlank()) {
                        try {
                            val entries = epgParser.parse(epgUrl)
                            allEntries.putAll(entries)
                        } catch (_: Exception) {
                            // EPG loading is best-effort
                        }
                    }
                }
                is AccountType.XtreamCodes -> {
                    // Load full XMLTV EPG from the standard XC endpoint
                    try {
                        val baseUrl = type.host.trimEnd('/')
                        val xmltvUrl = "$baseUrl/xmltv.php?username=${type.username}&password=${type.password}"
                        val entries = epgParser.parse(xmltvUrl)
                        allEntries.putAll(entries)
                        DebugLogger.log(
                            "Loaded ${entries.size} EPG channels from XC provider ${account.name}",
                            DebugLogger.Category.GENERAL,
                        )
                    } catch (_: Exception) {
                        // EPG loading is best-effort; on-demand fallback still available
                    }
                }
                // Subsonic/Navidrome and Audiobookshelf accounts have no EPG.
                is AccountType.Subsonic -> Unit
                is AccountType.Audiobookshelf -> Unit
            }
        }

        _epgEntries.value = allEntries
    }

    fun isTrackLoved(artist: String, title: String): Boolean {
        return _lovedTracks.value.any { it.artist == artist && it.title == title }
    }

    suspend fun toggleLovedTrack(track: TrackMetadata, channelName: String) {
        val existing = _lovedTracks.value.find { it.artist == track.artist && it.title == track.title }
        val updated = if (existing != null) {
            _lovedTracks.value.filter { it !== existing }
        } else {
            _lovedTracks.value + LovedTrack(
                artist = track.artist,
                title = track.title,
                album = track.album,
                albumArtURL = track.albumArtURL,
                channelName = channelName,
            )
        }
        _lovedTracks.value = updated
        persistenceService.saveLovedTracks(updated)
    }

    suspend fun removeLovedTrack(index: Int) {
        val updated = _lovedTracks.value.toMutableList().also { it.removeAt(index) }
        _lovedTracks.value = updated
        persistenceService.saveLovedTracks(updated)
    }

    suspend fun reorderLovedTracks(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val updated = _lovedTracks.value.toMutableList()
        val item = updated.removeAt(fromIndex)
        updated.add(toIndex, item)
        _lovedTracks.value = updated
        persistenceService.saveLovedTracks(updated)
    }

    fun startTrackMetadataPolling(channel: Channel) {
        trackMetadataJob?.cancel()
        // Keep the tuned channel even while ineligible so selecting its raw
        // group can attach metadata immediately without a retune.
        currentMetadataChannel = channel
        val selectedGroups = _sxmChannelGroups.value.orEmpty()
        if (channel.group !in selectedGroups) {
            _trackMetadata.value = _trackMetadata.value - channel.name
            return
        }
        // Remember the channel even when no mapping exists yet — a late
        // station-list success re-attaches metadata for it via onMappingBuilt
        // (beads_adagio-59p.3.4).
        val stationId = sxmMetadataService.stationIdForChannel(channel.id)
        if (stationId == null) {
            DebugLogger.log("No SXM station mapping for channel: '${channel.name}' (id=${channel.id})", DebugLogger.Category.SXM)
            return
        }
        DebugLogger.log("Starting SXM metadata polling for '${channel.name}' → station='$stationId'", DebugLogger.Category.SXM)
        val generation = sxmRuntimeGeneration
        trackMetadataJob = scope.launch {
            while (true) {
                sxmMetadataService.getRecentTrack(stationId).onSuccess { track ->
                    if (generation != sxmRuntimeGeneration || channel.group !in _sxmChannelGroups.value.orEmpty()) {
                        return@onSuccess
                    }
                    when {
                        track == null -> {
                            // Non-displayable cut / empty response — the source
                            // answered "nothing playing", so clear the display
                            // (ad break) instead of pinning the last song.
                            if (channel.name in _trackMetadata.value) {
                                DebugLogger.log("Track cleared (commercial break?)", DebugLogger.Category.SXM)
                                _trackMetadata.value = _trackMetadata.value - channel.name
                            }
                        }
                        isStreamUrlFilename(track.title, channel.streamURL) -> {
                            // beads_adagio-59p.3.5: never surface the stream
                            // URL's filename (e.g. "20998.ts") as a track title.
                            DebugLogger.log("Rejected stream-URL filename as title: '${track.title}'", DebugLogger.Category.SXM)
                        }
                        else -> {
                            DebugLogger.log("SXM track: ${track.artist} - ${track.title}", DebugLogger.Category.SXM)
                            _trackMetadata.value = _trackMetadata.value + (channel.name to track)
                        }
                    }
                }
                // Fetch failure keeps the last shown track (network blip ≠ ad break).
                delay(AppSettings.clampSxmPollInterval(sxmPollIntervalSeconds) * 1000L)
            }
        }
    }

    fun stopTrackMetadataPolling() {
        trackMetadataJob?.cancel()
        trackMetadataJob = null
        currentMetadataChannel = null
    }

    /**
     * Restart the active now-playing poll so a changed interval applies
     * immediately instead of after one more old-interval tick
     * (beads_adagio-59p.3.2). No-op when nothing is being polled.
     */
    fun sxmPollIntervalChanged() {
        if (trackMetadataJob?.isActive == true) {
            currentMetadataChannel?.let { startTrackMetadataPolling(it) }
        }
    }

    /**
     * Called when the user changes the SXM metadata source in Settings
     * (beads_adagio-59p.3.1): stop polling, drop stale-source metadata and
     * history, re-match against the new source. The live channel resumes via
     * the onMappingBuilt late-attach — currentMetadataChannel is intentionally
     * kept across the switch for exactly that.
     */
    fun sxmSourceChanged(newSource: SXMMetadataSource) {
        sxmRuntimeGeneration += 1
        invalidateSxmRuntime(keepCurrentChannel = true, bumpGeneration = false)
        sxmMetadataService.sourceChanged(newSource)
    }

    suspend fun updateSxmChannelGroups(groupNames: Set<String>): Result<Unit> {
        return sxmSelectionMutex.withLock {
            applyAndSaveSxmChannelGroups(groupNames)
        }
    }

    suspend fun toggleSxmChannelGroup(groupName: String): Result<Unit> = sxmSelectionMutex.withLock {
        val current = _sxmChannelGroups.value.orEmpty()
        applyAndSaveSxmChannelGroups(
            if (groupName in current) current - groupName else current + groupName,
        )
    }

    fun requestToggleSxmChannelGroup(groupName: String) {
        scope.launch { toggleSxmChannelGroup(groupName) }
    }

    private suspend fun applyAndSaveSxmChannelGroups(groupNames: Set<String>): Result<Unit> {
        val previous = _sxmChannelGroups.value
        _sxmSelectionSaveError.value = null
        _sxmChannelGroups.value = groupNames
        rematchSxmChannels()
        return try {
            persistenceService.updateSettings { it.copy(sxmChannelGroups = groupNames) }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _sxmChannelGroups.value = previous
            _sxmSelectionSaveError.value = "Could not save selection. Your previous selection was restored."
            rematchSxmChannels()
            Result.failure(error)
        }
    }

    fun clearSxmSelectionSaveError() {
        _sxmSelectionSaveError.value = null
    }

    fun retrySxmSelectionMigration() {
        _sxmSelectionSaveError.value = null
        scope.launch {
            val settingsResult = persistenceService.loadSettingsResult()
            if (settingsResult is SettingsLoadResult.Failure) {
                settingsReadyForMigration = false
                _sxmSelectionSaveError.value = "Settings could not be loaded. SiriusXM group migration is paused."
                return@launch
            }
            settingsReadyForMigration = true
            _sxmChannelGroups.value = settingsResult.settings?.sxmChannelGroups
            refreshRawInventoryAndSxm()
        }
    }

    suspend fun retrySxmChannelGroupInventory() {
        customPlaylistManager.retryLoad()
        loadAllChannels()
    }

    /**
     * The track that was playing on [channel] at [atEpochMillis] — used during
     * time-shift catch-up so the display track matches the buffered audio
     * instead of the live now-playing track. Null if the channel has no
     * station mapping or no history covers that moment.
     */
    fun historicalTrackMetadata(channel: Channel, atEpochMillis: Long): TrackMetadata? {
        val stationId = sxmMetadataService.stationIdForChannel(channel.id) ?: return null
        return sxmMetadataService.trackAt(stationId, atEpochMillis)
    }

    private fun rebuildGroups() {
        val allGrouped = when (groupingMode) {
            ChannelGroupingMode.ALL_GROUPS -> _channels.value.groupBy { it.group }
            ChannelGroupingMode.BY_PROVIDER -> _channels.value.groupBy { it.accountName ?: "Unknown" }
            ChannelGroupingMode.BY_SOURCE -> _channels.value.groupBy {
                when {
                    it.xtreamStreamId != null -> "Xtream Codes"
                    else -> "M3U"
                }
            }
        }

        _allGroupNames.value = allGrouped.keys

        // Filter by enabled groups
        val filteredGrouped = if (enabledGroups != null) {
            allGrouped.filter { it.key in enabledGroups!! }
        } else {
            allGrouped
        }

        _groups.value = filteredGrouped
            .map { (name, channels) ->
                val sorted = when (sortMode) {
                    SortMode.ACCOUNT_ORDER -> channels
                    SortMode.NATURAL -> channels.sortedWith(
                        Comparator { a, b -> naturalCompare(strippedName(a.name), strippedName(b.name)) },
                    )
                    SortMode.ALPHABETICAL -> channels.sortedBy { strippedName(it.name) }
                }
                ChannelGroup(name, sorted)
            }
            .let { groups ->
                val sorted = when (groupSortMode) {
                    SortMode.ACCOUNT_ORDER -> groups
                    SortMode.NATURAL -> groups.sortedWith(
                        Comparator { a, b -> naturalCompare(a.name.lowercase(), b.name.lowercase()) },
                    )
                    SortMode.ALPHABETICAL -> groups.sortedBy { it.name }
                }
                // Favorite groups always sorted to the top
                if (favoriteGroupOrder.isNotEmpty()) {
                    val (favorites, rest) = sorted.partition { it.name in favoriteGroupOrder }
                    val orderedFavorites = favoriteGroupOrder.mapNotNull { favName ->
                        favorites.find { it.name == favName }
                    }
                    orderedFavorites + rest
                } else {
                    sorted
                }
            }
    }

    private fun customChannels(): List<Channel> = rawCustomChannels(customPlaylistManager.playlists.value)

    private fun refreshRawInventoryAndSxm(generation: Long = channelLoadGeneration.get()) {
        if (generation != channelLoadGeneration.get()) return
        if (!providerInventoryLoaded || !customPlaylistManager.isLoaded.value) {
            _rawChannelGroupInventory.value = RawChannelGroupInventory.Loading
            rematchSxmChannels()
            return
        }
        val customPlaylists = customPlaylistManager.playlists.value
        val counts = rawChannelGroupCounts(rawProviderChannels, customPlaylists)
        val inventoryErrors = channelLoadErrors + listOfNotNull(customPlaylistManager.loadError.value)
        val inventory = when {
            inventoryErrors.isNotEmpty() -> RawChannelGroupInventory.PartialFailure(
                groupCounts = counts,
                message = inventoryErrors.joinToString("\n"),
            )
            channelAccountCount == 0 && counts.isEmpty() -> RawChannelGroupInventory.NoAccounts
            else -> RawChannelGroupInventory.Complete(counts)
        }
        _rawChannelGroupInventory.value = inventory

        if (inventory is RawChannelGroupInventory.Complete &&
            _sxmChannelGroups.value == null &&
            settingsReadyForMigration &&
            sxmMigrationJob?.isActive != true
        ) {
            val migrated = SxmChannelGroupPolicy.legacySelection(inventory.groupCounts.keys)
            sxmMigrationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                sxmSelectionMutex.withLock {
                    if (generation != channelLoadGeneration.get() || _sxmChannelGroups.value != null) return@withLock
                    try {
                        persistenceService.updateSettings { it.copy(sxmChannelGroups = migrated) }
                        if (generation != channelLoadGeneration.get()) return@withLock
                        _sxmChannelGroups.value = migrated
                        rematchSxmChannels()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        _sxmSelectionSaveError.value = "Could not save the migrated SiriusXM group selection."
                    }
                }
            }
        }
        rematchSxmChannels()
    }

    private fun rematchSxmChannels() {
        invalidateSxmRuntime(keepCurrentChannel = true)
        sxmMetadataService.matchChannels(
            channels = _channels.value + customChannels(),
            selectedGroups = _sxmChannelGroups.value.orEmpty(),
            sortPrefixes = sortPrefixes,
        )
    }

    private fun invalidateSxmRuntime(
        keepCurrentChannel: Boolean,
        bumpGeneration: Boolean = true,
    ) {
        if (bumpGeneration) sxmRuntimeGeneration += 1
        trackMetadataJob?.cancel()
        trackMetadataJob = null
        feedJob?.cancel()
        feedJob = null
        hasSxmChannels = false
        _trackMetadata.value = emptyMap()
        _feedMetadata.value = emptyMap()
        if (!keepCurrentChannel) currentMetadataChannel = null
    }

    private fun strippedName(name: String): String {
        var result = name
        for (prefix in sortPrefixes) {
            if (result.startsWith(prefix, ignoreCase = true)) {
                result = result.removePrefix(prefix)
                break
            }
        }
        return result.lowercase()
    }

    private fun naturalCompare(a: String, b: String): Int {
        val chunksA = naturalChunks(a)
        val chunksB = naturalChunks(b)
        for (i in 0 until minOf(chunksA.size, chunksB.size)) {
            val ca = chunksA[i]
            val cb = chunksB[i]
            val numA = ca.toBigIntegerOrNull()
            val numB = cb.toBigIntegerOrNull()
            val cmp = if (numA != null && numB != null) {
                numA.compareTo(numB)
            } else {
                ca.compareTo(cb)
            }
            if (cmp != 0) return cmp
        }
        return chunksA.size - chunksB.size
    }

    private fun naturalChunks(s: String): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var inDigit = false
        for (c in s) {
            val isDigit = c.isDigit()
            if (current.isNotEmpty() && isDigit != inDigit) {
                chunks.add(current.toString())
                current.clear()
            }
            current.append(c)
            inDigit = isDigit
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    fun favoriteKey(channel: Channel): String =
        "${channel.name}|${channel.streamURL}"
}
