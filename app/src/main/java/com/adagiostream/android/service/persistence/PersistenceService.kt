package com.adagiostream.android.service.persistence

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AppSettings
import com.adagiostream.android.model.CustomPlaylist
import com.adagiostream.android.model.LovedTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PersistenceService(
    private val context: Context,
    private val json: Json,
) {
    private val mutex = Mutex()

    companion object {
        private const val KEYSTORE_ALIAS = "adagio_master_key"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
    }

    // ponytail: lazy, not eager — AndroidKeyStore is only needed by the
    // encrypted-accounts path (encrypt/decrypt below). Eager init made this
    // whole class impossible to construct under Robolectric (no AndroidKeyStore
    // provider) even for callers that never touch accounts, e.g. last-played
    // persistence (baw.10). Note this relocates any keystore failure from
    // DI-construction time to first encrypted-accounts use — a scoping
    // improvement, not identical failure timing.
    private val keyStore: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    private fun getOrCreateKey(): SecretKey {
        keyStore.getEntry(KEYSTORE_ALIAS, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private val encryptedAccountsFile: File
        get() = File(context.filesDir, "accounts_v2.enc")

    // Legacy files for cleanup
    private val legacyEncryptedFile: File
        get() = File(context.filesDir, "accounts_encrypted.json")
    private val legacyAccountsFile: File
        get() = File(context.filesDir, "accounts.json")
    private val legacyProvidersFile: File
        get() = File(context.filesDir, "providers.json")

    private val favoritesFile: File
        get() = File(context.filesDir, "favorites.json")

    private val settingsFile: File
        get() = File(context.filesDir, "settings.json")

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv // GCM generates a random IV
        val ciphertext = cipher.doFinal(plaintext)
        // Format: [IV (12 bytes)] [ciphertext + GCM tag]
        return iv + ciphertext
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = data.copyOfRange(GCM_IV_LENGTH, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun readEncrypted(): String {
        val data = encryptedAccountsFile.readBytes()
        return String(decrypt(data), Charsets.UTF_8)
    }

    private fun writeEncrypted(content: String) {
        val encrypted = encrypt(content.toByteArray(Charsets.UTF_8))
        encryptedAccountsFile.writeBytes(encrypted)
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

    private fun cleanupLegacyFiles() {
        if (legacyEncryptedFile.exists()) secureDelete(legacyEncryptedFile)
        if (legacyAccountsFile.exists()) secureDelete(legacyAccountsFile)
        if (legacyProvidersFile.exists()) secureDelete(legacyProvidersFile)
    }

    suspend fun loadAccounts(): List<Account> = mutex.withLock {
        try {
            // Clean up any old-format files (prerelease — no migration)
            cleanupLegacyFiles()

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

    /**
     * Live settings — seeded from disk on first access, updated on every
     * [saveSettings] (baw.12). Lets managers/ViewModels react to settings
     * changed elsewhere (e.g. the offline-mode toggle in Settings) without
     * re-reading the file.
     */
    private val _settings by lazy { MutableStateFlow(loadSettingsSync()) }
    val settings: StateFlow<AppSettings> get() = _settings

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
        _settings.value = settings
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

    // Position sidecar (baw.14): the full LastPlayed.LibraryTrack (whole queue)
    // is written ONCE per queue by saveLastPlayed; pause/periodic ticks only
    // rewrite this tiny "index positionMs" file, so the hot playback path never
    // re-serializes the Track list. Cleared by any full saveLastPlayed().
    private val lastPlayedPositionFile: File
        get() = File(context.filesDir, "last_played_pos.txt")

    suspend fun saveLastPlayed(lastPlayed: LastPlayed) = withContext(Dispatchers.IO) {
        mutex.withLock {
            lastPlayedFile.writeText(json.encodeToString(lastPlayed))
            lastPlayedPositionFile.delete() // full save supersedes any stale sidecar
        }
    }

    /** Cheap position-only update against the queue already saved by [saveLastPlayed] (baw.14). */
    suspend fun saveLastPlayedPosition(index: Int, positionMs: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            lastPlayedPositionFile.writeText("$index $positionMs")
        }
    }

    /**
     * Loads the last-played entry (baw.10). Pre-baw.10 files store a bare
     * channel-id string (no JSON) — if decoding as [LastPlayed] fails, the raw
     * text is treated as a legacy channel id rather than discarded (a garbage
     * id simply resolves to nothing downstream in ResumptionPolicy). A library
     * entry is overlaid with the position sidecar when present (baw.14).
     */
    suspend fun loadLastPlayed(): LastPlayed? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!lastPlayedFile.exists()) return@withLock null
                val text = lastPlayedFile.readText()
                if (text.isBlank()) return@withLock null
                val entry = try {
                    json.decodeFromString<LastPlayed>(text)
                } catch (_: Exception) {
                    LastPlayed.Channel(text) // legacy plain-text format (or corrupt JSON)
                }
                if (entry is LastPlayed.LibraryTrack && lastPlayedPositionFile.exists()) {
                    runCatching {
                        val (index, positionMs) = lastPlayedPositionFile.readText().split(" ")
                        entry.copy(index = index.toInt(), positionMs = positionMs.toLong())
                    }.getOrDefault(entry) // corrupt sidecar → keep full-save values
                } else {
                    entry
                }
            } catch (_: Exception) {
                null
            }
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

    suspend fun deleteAllData() = mutex.withLock {
        listOf(
            encryptedAccountsFile,
            legacyEncryptedFile,
            legacyAccountsFile,
            legacyProvidersFile,
            favoritesFile,
            settingsFile,
            lovedTracksFile,
            lastPlayedFile,
            lastPlayedPositionFile,
            customPlaylistsFile,
        ).forEach { file ->
            if (file.exists()) {
                if (file == encryptedAccountsFile || file == legacyEncryptedFile || file == legacyAccountsFile) {
                    secureDelete(file)
                } else {
                    file.delete()
                }
            }
        }

        // Clear image cache
        val cacheDir = context.cacheDir
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }

        // Clear SharedPreferences
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        prefsDir.listFiles()?.forEach { it.delete() }

        // Clear temp files
        context.filesDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) file.delete()
        }
    }
}
