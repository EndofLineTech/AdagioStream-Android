package com.adagiostream.android.service.account

import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.ChannelGroup
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.model.LovedTrack
import com.adagiostream.android.model.ChannelGroupingMode
import com.adagiostream.android.model.SortMode
import com.adagiostream.android.model.TrackMetadata
import com.adagiostream.android.model.ESPNGameInfo
import com.adagiostream.android.service.metadata.ESPNScoreService
import com.adagiostream.android.service.metadata.XMPlaylistApi
import com.adagiostream.android.service.parsing.EPGParser
import com.adagiostream.android.service.parsing.M3UParser
import com.adagiostream.android.service.parsing.XtreamCodesApi
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.util.DebugLogger
import com.adagiostream.android.util.UrlSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountManager @Inject constructor(
    private val persistenceService: PersistenceService,
    private val client: OkHttpClient,
    private val xmPlaylistApi: XMPlaylistApi,
    private val espnScoreService: ESPNScoreService,
) {
    companion object

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val m3uParser = M3UParser(client)
    private val xtreamApi = XtreamCodesApi(client)
    private val epgParser = EPGParser(client)

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

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

    // Feed metadata keyed by XM slug — used for channel list subtitles
    private val _feedMetadata = MutableStateFlow<Map<String, TrackMetadata>>(emptyMap())
    val feedMetadata: StateFlow<Map<String, TrackMetadata>> = _feedMetadata.asStateFlow()
    private var feedJob: Job? = null

    private val _lovedTracks = MutableStateFlow<List<LovedTrack>>(emptyList())
    val lovedTracks: StateFlow<List<LovedTrack>> = _lovedTracks.asStateFlow()

    private var favoriteIds = mutableListOf<String>()
    var sortPrefixes: List<String> = listOf("Radio: ", "TV: ")
        private set
    var sortMode: SortMode = SortMode.ALPHABETICAL
        private set
    var groupSortMode: SortMode = SortMode.ALPHABETICAL
        private set
    var groupingMode: ChannelGroupingMode = ChannelGroupingMode.ALL_GROUPS
        private set

    // Group management
    private var enabledGroups: MutableSet<String>? = null  // null = all enabled
    private var favoriteGroupOrder = mutableListOf<String>()
    private val _allGroupNames = MutableStateFlow<Set<String>>(emptySet())
    val allGroupNames: StateFlow<Set<String>> = _allGroupNames.asStateFlow()

    val espnGames: StateFlow<Map<String, ESPNGameInfo>> = espnScoreService.gamesByChannel

    private var hasSxmChannels = false

    init {
        scope.launch {
            val settings = persistenceService.loadSettings()
            sortPrefixes = settings.sortPrefixes
            sortMode = settings.sortMode
            groupSortMode = settings.groupSortMode
            groupingMode = settings.channelGroupingMode
            enabledGroups = settings.enabledGroups?.toMutableSet()
            favoriteGroupOrder = settings.favoriteGroupOrder.toMutableList()
            _accounts.value = persistenceService.loadAccounts()
            favoriteIds = persistenceService.loadFavoriteIds().toMutableList()
            _lovedTracks.value = persistenceService.loadLovedTracks()
            loadAllChannels()
        }
    }

    private fun startFeedPollingIfNeeded() {
        if (!hasSxmChannels) {
            feedJob?.cancel()
            feedJob = null
            return
        }
        if (feedJob != null) return // already polling
        feedJob = scope.launch {
            while (true) {
                val feed = xmPlaylistApi.getFeed()
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

    suspend fun addAccount(account: Account): AddAccountResult {
        val existingGroups = _allGroupNames.value.toSet()
        val updated = _accounts.value + account
        _accounts.value = updated
        persistenceService.saveAccounts(updated)
        loadAllChannels()

        val newGroups = _allGroupNames.value - existingGroups
        // Auto-disable new groups if there were existing groups
        if (existingGroups.isNotEmpty() && newGroups.isNotEmpty()) {
            for (groupName in newGroups) {
                if (enabledGroups == null) {
                    // Initialize enabledGroups with all existing groups (excluding new ones)
                    enabledGroups = (existingGroups).toMutableSet()
                } else {
                    // New groups are already not in enabledGroups, so they're disabled
                }
            }
            saveGroupSettings()
            rebuildGroups()
        }

        val newChannelCount = _channels.value.count { it.group in _allGroupNames.value }
        return AddAccountResult(
            channelCount = newChannelCount,
            newGroupCount = newGroups.size,
        )
    }

    suspend fun updateAccount(account: Account) {
        val updated = _accounts.value.map { if (it.id == account.id) account else it }
        _accounts.value = updated
        persistenceService.saveAccounts(updated)
        loadAllChannels()
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
        val updated = _accounts.value.filter { it.id != accountId }
        _accounts.value = updated
        persistenceService.saveAccounts(updated)
        loadAllChannels()
    }

    suspend fun loadAllChannels() {
        _isLoading.value = true
        _error.value = null

        try {
            val allChannels = mutableListOf<Channel>()
            for (account in _accounts.value.filter { it.isEnabled }) {
                try {
                    val channels = when (account.type) {
                        is AccountType.M3U -> m3uParser.parse(account.type.url)
                        is AccountType.XtreamCodes -> xtreamApi.getChannels(account.type)
                    }
                    allChannels.addAll(channels.map { it.copy(accountName = account.name) })
                } catch (e: Exception) {
                    _error.value = "Failed to load ${account.name}: ${UrlSanitizer.redact(e.message ?: "Unknown error")}"
                }
            }

            val withFavorites = allChannels.map { channel ->
                val favKey = favoriteKey(channel)
                channel.copy(isFavorite = favKey in favoriteIds)
            }

            _channels.value = withFavorites
            rebuildGroups()
            loadEPG()
            xmPlaylistApi.matchChannels(withFavorites, sortPrefixes)
            hasSxmChannels = xmPlaylistApi.hasMappedChannels()
            startFeedPollingIfNeeded()
            espnScoreService.matchChannels(withFavorites, sortPrefixes)
            espnScoreService.setPollingEnabled(true)
        } catch (e: Exception) {
            _error.value = UrlSanitizer.redact(e.message ?: "Unknown error")
        } finally {
            _isLoading.value = false
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
        persistenceService.clearAllFavorites()
        _channels.value = _channels.value.map { it.copy(isFavorite = false) }
        rebuildGroups()
    }

    fun updateSortPrefixes(prefixes: List<String>) {
        sortPrefixes = prefixes
        rebuildGroups()
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
        saveGroupSettings()
        rebuildGroups()
    }

    suspend fun toggleGroupFavorite(groupName: String) {
        if (groupName in favoriteGroupOrder) {
            favoriteGroupOrder.remove(groupName)
        } else {
            favoriteGroupOrder.add(groupName)
        }
        saveGroupSettings()
        rebuildGroups()
    }

    suspend fun updateFavoriteGroupOrder(order: List<String>) {
        favoriteGroupOrder = order.toMutableList()
        saveGroupSettings()
        rebuildGroups()
    }

    suspend fun setAllGroupsEnabled(enabled: Boolean) {
        enabledGroups = if (enabled) null else mutableSetOf()
        saveGroupSettings()
        rebuildGroups()
    }

    private suspend fun saveGroupSettings() {
        val settings = persistenceService.loadSettings()
        persistenceService.saveSettings(
            settings.copy(
                enabledGroups = enabledGroups?.toSet(),
                favoriteGroupOrder = favoriteGroupOrder.toList(),
            )
        )
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
                    // Xtream EPG is loaded on-demand per channel via loadXtreamEPGForChannel
                }
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
        val deeplink = xmPlaylistApi.deeplinkForChannel(channel.id)
        if (deeplink == null) {
            DebugLogger.log("No XM deeplink for channel: '${channel.name}' (id=${channel.id})", DebugLogger.Category.SXM)
            return
        }
        DebugLogger.log("Starting XM metadata polling for '${channel.name}' → deeplink='$deeplink'", DebugLogger.Category.SXM)
        trackMetadataJob = scope.launch {
            while (true) {
                val track = xmPlaylistApi.getRecentTrack(deeplink)
                if (track != null) {
                    DebugLogger.log("XM track: ${track.artist} - ${track.title}", DebugLogger.Category.SXM)
                    _trackMetadata.value = _trackMetadata.value + (channel.name to track)
                } else {
                    DebugLogger.log("XM returned null for deeplink='$deeplink'", DebugLogger.Category.SXM)
                }
                delay(15_000L)
            }
        }
    }

    fun stopTrackMetadataPolling() {
        trackMetadataJob?.cancel()
        trackMetadataJob = null
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

        // Track all raw group names for group management
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

    private fun favoriteKey(channel: Channel): String =
        "${channel.name}|${channel.streamURL}"
}
