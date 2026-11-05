package io.cosgriff.sightline.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.cosgriff.sightline.protocol.LockKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StateStore

/** A lock key Sightline set and hasn't seen released. */
data class HeldLock(val deviceId: String, val tuner: Int, val lockKey: Long)

/** Which device and channel Live shows. Kept across restarts. */
data class LiveSelection(val deviceId: String, val guideNumber: String?)

/**
 * App state that isn't a setting: the lock keys Sightline holds (so a key left by a killed process is released
 * on the next start, SPEC "Polling loop") and the Live selection.
 */
@Singleton
class AppStateRepository @Inject constructor(@param:StateStore private val store: DataStore<Preferences>) : LockKeyStore {
    override suspend fun remember(deviceId: String, tuner: Int, lockKey: Long) {
        store.edit { it[lockKeyPref(deviceId, tuner)] = lockKey }
    }

    override suspend fun forget(deviceId: String, tuner: Int) {
        store.edit { it.remove(lockKeyPref(deviceId, tuner)) }
    }

    suspend fun heldLocks(): List<HeldLock> = store.data.first().asMap().mapNotNull { (key, value) ->
        val parts = key.name.split('/')
        if (parts.size != 3 || parts[0] != LOCK_PREFIX || value !is Long) return@mapNotNull null
        HeldLock(parts[1], parts[2].toIntOrNull() ?: return@mapNotNull null, value)
    }

    val liveSelection: Flow<LiveSelection?> = store.data.map { p ->
        p[LIVE_DEVICE]?.let { LiveSelection(it, p[LIVE_CHANNEL]) }
    }

    suspend fun selectForLive(deviceId: String, guideNumber: String?) {
        store.edit {
            it[LIVE_DEVICE] = deviceId
            if (guideNumber != null) it[LIVE_CHANNEL] = guideNumber else it.remove(LIVE_CHANNEL)
        }
    }

    private companion object {
        const val LOCK_PREFIX = "lockkey"
        fun lockKeyPref(deviceId: String, tuner: Int) = longPreferencesKey("$LOCK_PREFIX/$deviceId/$tuner")
        val LIVE_DEVICE = stringPreferencesKey("live_device")
        val LIVE_CHANNEL = stringPreferencesKey("live_channel")
    }
}
