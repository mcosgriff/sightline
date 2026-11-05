package io.cosgriff.sightline.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.ToneRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Defaults from SPEC, and the Settings screen's writes kept in range. */
class SettingsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private val repository by lazy {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("s.preferences_pb").also { it.delete() } })
    }

    private suspend fun now() = repository.settings.first()

    @Test
    fun `defaults are the spec's`() = runBlocking<Unit> {
        val s = now()
        assertThat(s.thresholds).isEqualTo(Thresholds.DEFAULT)
        assertThat(s.pollIntervalMs).isEqualTo(1_000)
        assertThat(s.units).isEqualTo(Units.IMPERIAL)
        assertThat(s.theme).isEqualTo(ThemeSetting.DARK)
        assertThat(s.retentionDays).isEqualTo(90)
        assertThat(s.keepScreenOn).isTrue()
        assertThat(s.toneRange).isEqualTo(ToneRange(200, 1200))
    }

    @Test
    fun `thresholds stay in order`() = runBlocking<Unit> {
        repository.setThresholds(Thresholds(snqGood = 70, snqMarginal = 75, seqGood = 100, seqMarginal = 120, ssGood = 50, ssMarginal = 60, ssOverload = 40))
        val t = now().thresholds
        assertThat(t.snqGood).isEqualTo(70)
        assertThat(t.snqMarginal).isEqualTo(70)
        assertThat(t.seqMarginal).isEqualTo(100)
        assertThat(t.ssMarginal).isEqualTo(50)
        assertThat(t.ssOverload).isEqualTo(51)

        repository.resetThresholds()
        assertThat(now().thresholds).isEqualTo(Thresholds.DEFAULT)
    }

    @Test
    fun `theme, units, poll interval and screen-on are kept`() = runBlocking<Unit> {
        repository.setTheme(ThemeSetting.TRUE_BLACK)
        repository.setUnits(Units.METRIC)
        repository.setPollInterval(5_000)
        repository.setKeepScreenOn(false)
        val s = now()
        assertThat(s.theme).isEqualTo(ThemeSetting.TRUE_BLACK)
        assertThat(s.units).isEqualTo(Units.METRIC)
        assertThat(s.pollIntervalMs).isEqualTo(5_000)
        assertThat(s.keepScreenOn).isFalse()
    }

    @Test
    fun `retention can keep for ever`() = runBlocking<Unit> {
        repository.setRetentionDays(30)
        assertThat(now().retentionDays).isEqualTo(30)
        repository.setRetentionDays(null)
        assertThat(now().retentionDays).isNull()
    }

    @Test
    fun `the tone range stays inside 200 to 1200 Hz and at least 200 Hz wide`() = runBlocking<Unit> {
        repository.setToneRange(ToneRange(100, 2000))
        assertThat(now().toneRange).isEqualTo(ToneRange(200, 1200))
        repository.setToneRange(ToneRange(1100, 1150))
        assertThat(now().toneRange).isEqualTo(ToneRange(1000, 1200))
    }

    @Test
    fun `home is forgotten`() = runBlocking<Unit> {
        repository.setHome(39.7392, -104.9903)
        assertThat(now().home).isEqualTo(Home(39.74, -104.99))
        repository.clearHome()
        assertThat(now().home).isNull()
    }
}
