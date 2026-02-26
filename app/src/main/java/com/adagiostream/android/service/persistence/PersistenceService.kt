package com.adagiostream.android.service.persistence

import android.content.Context
import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.Provider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class PersistenceService(
    private val context: Context,
    private val json: Json,
) {
    private val mutex = Mutex()

    private val providersFile: File
        get() = File(context.filesDir, "providers.json")

    private val favoritesFile: File
        get() = File(context.filesDir, "favorites.json")

    private val settingsFile: File
        get() = File(context.filesDir, "settings.json")

    suspend fun loadProviders(): List<Provider> = mutex.withLock {
        try {
            if (providersFile.exists()) {
                json.decodeFromString<List<Provider>>(providersFile.readText())
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveProviders(providers: List<Provider>) = mutex.withLock {
        providersFile.writeText(json.encodeToString(providers))
    }

    suspend fun loadFavoriteIds(): Set<String> = mutex.withLock {
        try {
            if (favoritesFile.exists()) {
                json.decodeFromString<Set<String>>(favoritesFile.readText())
            } else {
                emptySet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    suspend fun saveFavoriteIds(ids: Set<String>) = mutex.withLock {
        favoritesFile.writeText(json.encodeToString(ids))
    }

    suspend fun loadSettings(): AppSettings = mutex.withLock {
        try {
            if (settingsFile.exists()) {
                json.decodeFromString<AppSettings>(settingsFile.readText())
            } else {
                AppSettings()
            }
        } catch (_: Exception) {
            AppSettings()
        }
    }

    suspend fun saveSettings(settings: AppSettings) = mutex.withLock {
        settingsFile.writeText(json.encodeToString(settings))
    }

    suspend fun clearAllFavorites() = mutex.withLock {
        favoritesFile.delete()
    }

    private val lastPlayedFile: File
        get() = File(context.filesDir, "last_played.txt")

    suspend fun saveLastPlayed(channelId: String) = mutex.withLock {
        lastPlayedFile.writeText(channelId)
    }

    suspend fun loadLastPlayed(): String? = mutex.withLock {
        try {
            if (lastPlayedFile.exists()) lastPlayedFile.readText().ifBlank { null } else null
        } catch (_: Exception) {
            null
        }
    }
}
