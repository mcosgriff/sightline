package io.cosgriff.sightline.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.cosgriff.sightline.analysis.Thresholds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The settings Live reads (SPEC "Data model › Settings", defaults from SPEC). The Settings screen that edits them
 * comes in milestone 6; until then a value is its default unless a screen wrote it (Aim writes its toggles,
 * tower bearings and home).
 */
data class Settings(
    val thresholds: Thresholds = Thresholds.DEFAULT,
    val pollIntervalMs: Long = 1_000,
    val keepScreenOn: Boolean = true,
    /** Sweep's time on each channel (SPEC: default 6 s). */
    val sweepDwellMs: Long = 6_000,
    /** Aim's tone. Off until asked for: a phone that starts beeping on its own is a surprise. */
    val aimTone: Boolean = false,
    val aimHaptics: Boolean = true,
    /** Up to [MAX_TOWERS] tower bearings in whole degrees from true north, typed in on Aim. */
    val towerBearings: List<Int> = emptyList(),
    /** Home, to 2 decimal places (about 1 km, SPEC "Privacy"): true north on Aim, and where weather is fetched for. */
    val home: Home? = null,
    /** How temperatures and speeds are shown. Everything is stored metric. */
    val units: Units = Units.IMPERIAL,
) {
    companion object {
        const val MAX_TOWERS = 3
    }
}

data class Home(val latitude: Double, val longitude: Double)

/** °F and mph (the default), or °C and km/h. */
enum class Units { IMPERIAL, METRIC }

@Singleton
class SettingsRepository @Inject constructor(@param:SettingsStore private val store: DataStore<Preferences>) {
    val settings: Flow<Settings> = store.data.map { p ->
        val d = Thresholds.DEFAULT
        Settings(
            thresholds = Thresholds(
                snqGood = p[SNQ_GOOD] ?: d.snqGood,
                snqMarginal = p[SNQ_MARGINAL] ?: d.snqMarginal,
                seqGood = p[SEQ_GOOD] ?: d.seqGood,
                seqMarginal = p[SEQ_MARGINAL] ?: d.seqMarginal,
                ssGood = p[SS_GOOD] ?: d.ssGood,
                ssMarginal = p[SS_MARGINAL] ?: d.ssMarginal,
                ssOverload = p[SS_OVERLOAD] ?: d.ssOverload,
            ),
            pollIntervalMs = p[POLL_INTERVAL_MS] ?: 1_000,
            keepScreenOn = p[KEEP_SCREEN_ON] ?: true,
            sweepDwellMs = p[SWEEP_DWELL_MS] ?: 6_000,
            aimTone = p[AIM_TONE] ?: false,
            aimHaptics = p[AIM_HAPTICS] ?: true,
            towerBearings = p[TOWER_BEARINGS]?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty(),
            home = p[HOME_LAT]?.let { lat -> p[HOME_LON]?.let { lon -> Home(lat, lon) } },
            units = p[UNITS]?.let { name -> Units.entries.firstOrNull { it.name == name } } ?: Units.IMPERIAL,
        )
    }

    suspend fun setAimTone(on: Boolean) {
        store.edit { it[AIM_TONE] = on }
    }

    suspend fun setAimHaptics(on: Boolean) {
        store.edit { it[AIM_HAPTICS] = on }
    }

    /** Keeps the first [Settings.MAX_TOWERS], each as a bearing in 0..359. */
    suspend fun setTowerBearings(bearings: List<Int>) {
        val kept = bearings.take(Settings.MAX_TOWERS).map { Math.floorMod(it, 360) }
        store.edit { if (kept.isEmpty()) it.remove(TOWER_BEARINGS) else it[TOWER_BEARINGS] = kept.joinToString(",") }
    }

    /** Saves home rounded to 2 decimal places; nothing finer is ever stored. */
    suspend fun setHome(latitude: Double, longitude: Double) {
        store.edit {
            it[HOME_LAT] = roundTo2(latitude)
            it[HOME_LON] = roundTo2(longitude)
        }
    }

    private companion object {
        val SNQ_GOOD = intPreferencesKey("threshold_snq_good")
        val SNQ_MARGINAL = intPreferencesKey("threshold_snq_marginal")
        val SEQ_GOOD = intPreferencesKey("threshold_seq_good")
        val SEQ_MARGINAL = intPreferencesKey("threshold_seq_marginal")
        val SS_GOOD = intPreferencesKey("threshold_ss_good")
        val SS_MARGINAL = intPreferencesKey("threshold_ss_marginal")
        val SS_OVERLOAD = intPreferencesKey("threshold_ss_overload")
        val POLL_INTERVAL_MS = longPreferencesKey("poll_interval_ms")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SWEEP_DWELL_MS = longPreferencesKey("sweep_dwell_ms")
        val AIM_TONE = booleanPreferencesKey("aim_tone")
        val AIM_HAPTICS = booleanPreferencesKey("aim_haptics")
        val TOWER_BEARINGS = stringPreferencesKey("tower_bearings")
        val HOME_LAT = doublePreferencesKey("home_lat")
        val HOME_LON = doublePreferencesKey("home_lon")
        val UNITS = stringPreferencesKey("units")

        fun roundTo2(v: Double) = Math.round(v * 100) / 100.0
    }
}
