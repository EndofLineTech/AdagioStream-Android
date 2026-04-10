package com.adagiostream.android.service.persistence

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.CustomPlaylist
import com.adagiostream.android.model.LovedTrack
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

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val accountsFile: File
        get() = File(context.filesDir, "accounts.json")

    private val encryptedAccountsFile: File
        get() = File(context.filesDir, "accounts_encrypted.json")

    private val legacyProvidersFile: File
        get() = File(context.filesDir, "providers.json")

    private val favoritesFile: File
        get() = File(context.filesDir, "favorites.json")

    private val settingsFile: File
        get() = File(context.filesDir, "settings.json")

    private fun migrateProvidersFile() {
        if (!accountsFile.exists() && legacyProvidersFile.exists()) {
            legacyProvidersFile.renameTo(accountsFile)
        }
    }

    private fun buildEncryptedFile(): EncryptedFile {
        return EncryptedFile.Builder(
            context,
            encryptedAccountsFile,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
    }

    private fun readEncrypted(): String {
        return buildEncryptedFile().openFileInput().bufferedReader().use { it.readText() }
    }

    private fun writeEncrypted(content: String) {
        // EncryptedFile doesn't support overwriting — delete first
        if (encryptedAccountsFile.exists()) {
            encryptedAccountsFile.delete()
        }
        buildEncryptedFile().openFileOutput().bufferedWriter().use { it.write(content) }
    }

    private fun secureDelete(file: File) {
        try {
            val length = file.length()
            file.outputStream().use { out ->
                val zeros = ByteArray(minOf(length, 8192L).toInt())
                var remaining = length
                while (remaining > 0) {
                    val chunk = minOf(remaining, zeros.size.toLong()).toInt()
                    out.write(zeros, 0, chunk)
                    remaining -= chunk
                }
                out.fd.sync()
            }
        } catch (_: Exception) {
            // Best-effort; proceed to delete regardless
        }
        file.delete()
    }

    suspend fun loadAccounts(): List<Account> = mutex.withLock {
        try {
            migrateProvidersFile()

            // Try plaintext first (migration path)
            if (accountsFile.exists()) {
                val plaintext = accountsFile.readText()
                val accounts = json.decodeFromString<List<Account>>(plaintext)
                // Migrate to encrypted
                writeEncrypted(plaintext)
                // Overwrite plaintext with zeros before deleting (defense-in-depth)
                secureDelete(accountsFile)
                return@withLock accounts
            }

            // Try encrypted
            if (encryptedAccountsFile.exists()) {
                return@withLock json.decodeFromString<List<Account>>(readEncrypted())
            }

            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveAccounts(accounts: List<Account>) = mutex.withLock {
        writeEncrypted(json.encodeToString(accounts))
    }

    suspend fun loadFavoriteIds(): List<String> = mutex.withLock {
        try {
            if (favoritesFile.exists()) {
                json.decodeFromString<List<String>>(favoritesFile.readText())
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveFavoriteIds(ids: List<String>) = mutex.withLock {
        favoritesFile.writeText(json.encodeToString(ids))
    }

    fun loadSettingsSync(): AppSettings {
        return try {
            if (settingsFile.exists()) {
                json.decodeFromString<AppSettings>(settingsFile.readText())
            } else {
                AppSettings()
            }
        } catch (_: Exception) {
            AppSettings()
        }
    }

    suspend fun loadSettings(): AppSettings = mutex.withLock {
        loadSettingsSync()
    }

    suspend fun saveSettings(settings: AppSettings) = mutex.withLock {
        settingsFile.writeText(json.encodeToString(settings))
    }

    suspend fun clearAllFavorites() = mutex.withLock {
        favoritesFile.delete()
    }

    private val lovedTracksFile: File
        get() = File(context.filesDir, "loved_tracks.json")

    suspend fun loadLovedTracks(): List<LovedTrack> = mutex.withLock {
        try {
            if (lovedTracksFile.exists()) {
                json.decodeFromString<List<LovedTrack>>(lovedTracksFile.readText())
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveLovedTracks(tracks: List<LovedTrack>) = mutex.withLock {
        lovedTracksFile.writeText(json.encodeToString(tracks))
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

    private val customPlaylistsFile: File
        get() = File(context.filesDir, "custom_playlists.json")

    suspend fun loadCustomPlaylists(): List<CustomPlaylist> = mutex.withLock {
        try {
            if (customPlaylistsFile.exists()) {
                json.decodeFromString<List<CustomPlaylist>>(customPlaylistsFile.readText())
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveCustomPlaylists(playlists: List<CustomPlaylist>) = mutex.withLock {
        customPlaylistsFile.writeText(json.encodeToString(playlists))
    }
}
