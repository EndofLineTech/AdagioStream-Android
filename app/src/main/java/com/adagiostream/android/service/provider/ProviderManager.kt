package com.adagiostream.android.service.provider

import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.ChannelGroup
import com.adagiostream.android.model.Provider
import com.adagiostream.android.model.ProviderType
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

    private var favoriteIds = mutableSetOf<String>()

    init {
        scope.launch {
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
            _groups.value = withFavorites
                .groupBy { it.group }
                .map { (name, channels) -> ChannelGroup(name, channels.sortedBy { it.name }) }
                .sortedBy { it.name }
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
        _groups.value = _channels.value
            .groupBy { it.group }
            .map { (name, channels) -> ChannelGroup(name, channels.sortedBy { it.name }) }
            .sortedBy { it.name }
    }

    private fun favoriteKey(channel: Channel): String =
        "${channel.name}|${channel.streamURL}"
}
