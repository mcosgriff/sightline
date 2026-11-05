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
import io.cosgriff.sightline.analysis.ToneRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The settings (SPEC "Data model › Settings", defaults from SPEC), edited on the Settings screen; Aim also writes
 * its toggles, tower bearings and home.
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
    val theme: ThemeSetting = ThemeSetting.DARK,
    /** Days readings are kept before they're deleted at app start; null keeps them for ever. Events and summaries stay. */
    val retentionDays: Int? = DEFAULT_RETENTION_DAYS,
    val toneRange: ToneRange = ToneRange(),
) {
    companion object {
        const val MAX_TOWERS = 3
        const val DEFAULT_RETENTION_DAYS = 90

        /** The poll intervals offered: every second, or every 5 s to save battery (SPEC Screens › 6). */
        val POLL_INTERVALS_MS = listOf(1_000L, 5_000L)
    }
}

/** The app's theme (SPEC Screens › 10). Dark is the default, whatever the phone's own setting. */
enum class ThemeSetting { SYSTEM, LIGHT, DARK, TRUE_BLACK }

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
            theme = p[THEME]?.let { name -> ThemeSetting.entries.firstOrNull { it.name == name } } ?: ThemeSetting.DARK,
            retentionDays = when (val days = p[RETENTION_DAYS]) {
                null -> Settings.DEFAULT_RETENTION_DAYS
                KEEP_FOREVER -> null
                else -> days
            },
            toneRange = ToneRange(p[TONE_LOW_HZ] ?: ToneRange().lowHz, p[TONE_HIGH_HZ] ?: ToneRange().highHz),
        )
    }

    /**
     * Saves the bands, kept in order: each marginal line at or below its good line, and the overload line above
     * the good strength line.
     */
    suspend fun setThresholds(t: Thresholds) {
        val snqGood = t.snqGood.coerceIn(1, 100)
        val seqGood = t.seqGood.coerceIn(1, 100)
        val ssGood = t.ssGood.coerceIn(1, 99)
        store.edit {
            it[SNQ_GOOD] = snqGood
            it[SNQ_MARGINAL] = t.snqMarginal.coerceIn(0, snqGood)
            it[SEQ_GOOD] = seqGood
            it[SEQ_MARGINAL] = t.seqMarginal.coerceIn(0, seqGood)
            it[SS_GOOD] = ssGood
            it[SS_MARGINAL] = t.ssMarginal.coerceIn(0, ssGood)
            it[SS_OVERLOAD] = t.ssOverload.coerceIn(ssGood + 1, 100)
        }
    }

    suspend fun resetThresholds() = setThresholds(Thresholds.DEFAULT)

    suspend fun setPollInterval(ms: Long) {
        store.edit { it[POLL_INTERVAL_MS] = ms.coerceIn(Settings.POLL_INTERVALS_MS.first(), Settings.POLL_INTERVALS_MS.last()) }
    }

    suspend fun setUnits(units: Units) {
        store.edit { it[UNITS] = units.name }
    }

    suspend fun setTheme(theme: ThemeSetting) {
        store.edit { it[THEME] = theme.name }
    }

    suspend fun setKeepScreenOn(on: Boolean) {
        store.edit { it[KEEP_SCREEN_ON] = on }
    }

    /** [days] null keeps readings for ever. */
    suspend fun setRetentionDays(days: Int?) {
        store.edit { it[RETENTION_DAYS] = days?.coerceAtLeast(1) ?: KEEP_FOREVER }
    }

    /** Saves the tone's ends, at least [MIN_TONE_SPAN_HZ] apart inside the default 200–1200 Hz. */
    suspend fun setToneRange(range: ToneRange) {
        val low = range.lowHz.coerceIn(ToneRange().lowHz, ToneRange().highHz - MIN_TONE_SPAN_HZ)
        val high = range.highHz.coerceIn(low + MIN_TONE_SPAN_HZ, ToneRange().highHz)
        store.edit {
            it[TONE_LOW_HZ] = low
            it[TONE_HIGH_HZ] = high
        }
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

    /** Forgets home: weather can't be fetched and Aim uses magnetic north until it's set again. */
    suspend fun clearHome() {
        store.edit {
            it.remove(HOME_LAT)
            it.remove(HOME_LON)
        }
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
        val THEME = stringPreferencesKey("theme")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
        val TONE_LOW_HZ = intPreferencesKey("tone_low_hz")
        val TONE_HIGH_HZ = intPreferencesKey("tone_high_hz")

        /** Stored for "keep for ever", since a missing key means the default. */
        const val KEEP_FOREVER = 0
        const val MIN_TONE_SPAN_HZ = 200

        fun roundTo2(v: Double) = Math.round(v * 100) / 100.0
    }
}
