package io.cosgriff.sightline.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import io.cosgriff.sightline.analysis.Thresholds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The settings Live reads (SPEC "Data model › Settings", defaults from SPEC). The Settings screen that edits them
 * comes in milestone 6; until then every value is its default unless something wrote it.
 */
data class Settings(
    val thresholds: Thresholds = Thresholds.DEFAULT,
    val pollIntervalMs: Long = 1_000,
    val keepScreenOn: Boolean = true,
)

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
        )
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
    }
}
