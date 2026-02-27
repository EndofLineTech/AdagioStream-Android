package com.adagiostream.android.service.provider

import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.ChannelGroup
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.model.Provider
import com.adagiostream.android.model.ProviderType
import com.adagiostream.android.model.SortMode
import com.adagiostream.android.service.parsing.EPGParser
import com.adagiostream.android.service.parsing.M3UParser
import com.adagiostream.android.service.parsing.XtreamCodesApi
import com.adagiostream.android.service.persistence.PersistenceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderManager @Inject constructor(
    private val persistenceService: PersistenceService,
    private val client: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val m3uParser = M3UParser(client)
    private val xtreamApi = XtreamCodesApi(client)
    private val epgParser = EPGParser(client)

    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

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

    private var favoriteIds = mutableSetOf<String>()
    var sortPrefixes: List<String> = listOf("Radio: ", "TV: ")
        private set
    var sortMode: SortMode = SortMode.ALPHABETICAL
        private set

    init {
        scope.launch {
            val settings = persistenceService.loadSettings()
            sortPrefixes = settings.sortPrefixes
            sortMode = settings.sortMode
            _providers.value = persistenceService.loadProviders()
            favoriteIds = persistenceService.loadFavoriteIds().toMutableSet()
            loadAllChannels()
        }
    }

    suspend fun addProvider(provider: Provider) {
        val updated = _providers.value + provider
        _providers.value = updated
        persistenceService.saveProviders(updated)
        loadAllChannels()
    }

    suspend fun updateProvider(provider: Provider) {
        val updated = _providers.value.map { if (it.id == provider.id) provider else it }
        _providers.value = updated
        persistenceService.saveProviders(updated)
        loadAllChannels()
    }

    suspend fun deleteProvider(providerId: String) {
        val updated = _providers.value.filter { it.id != providerId }
        _providers.value = updated
        persistenceService.saveProviders(updated)
        loadAllChannels()
    }

    suspend fun loadAllChannels() {
        _isLoading.value = true
        _error.value = null

        try {
            val allChannels = mutableListOf<Channel>()
            for (provider in _providers.value) {
                try {
                    val channels = when (provider.type) {
                        is ProviderType.M3U -> m3uParser.parse(provider.type.url)
                        is ProviderType.XtreamCodes -> xtreamApi.getChannels(provider.type)
                    }
                    allChannels.addAll(channels)
                } catch (e: Exception) {
                    _error.value = "Failed to load ${provider.name}: ${e.message}"
                }
            }

            val withFavorites = allChannels.map { channel ->
                val favKey = favoriteKey(channel)
                channel.copy(isFavorite = favKey in favoriteIds)
            }

            _channels.value = withFavorites
            rebuildGroups()
            loadEPG()
        } catch (e: Exception) {
            _error.value = e.message ?: "Unknown error"
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
        persistenceService.saveFavoriteIds(favoriteIds.toSet())

        _channels.value = _channels.value.map {
            if (favoriteKey(it) == key) it.copy(isFavorite = key in favoriteIds) else it
        }
        rebuildGroups()
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

    suspend fun loadXtreamEPGForChannel(channel: Channel) {
        val streamId = channel.xtreamStreamId ?: return
        val provider = _providers.value.find {
            it.type is ProviderType.XtreamCodes
        } ?: return
        val config = provider.type as ProviderType.XtreamCodes

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

        for (provider in _providers.value) {
            when (val type = provider.type) {
                is ProviderType.M3U -> {
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
                is ProviderType.XtreamCodes -> {
                    // Xtream EPG is loaded on-demand per channel via loadXtreamEPGForChannel
                }
            }
        }

        _epgEntries.value = allEntries
    }

    private fun rebuildGroups() {
        _groups.value = _channels.value
            .groupBy { it.group }
            .map { (name, channels) ->
                val sorted = when (sortMode) {
                    SortMode.PROVIDER_ORDER -> channels
                    SortMode.NATURAL -> channels.sortedWith(
                        Comparator { a, b -> naturalCompare(strippedName(a.name), strippedName(b.name)) },
                    )
                    SortMode.ALPHABETICAL -> channels.sortedBy { strippedName(it.name) }
                }
                ChannelGroup(name, sorted)
            }
            .sortedBy { it.name }
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
