package io.cosgriff.sightline.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.ToneRange
import io.cosgriff.sightline.data.settings.Settings
import io.cosgriff.sightline.data.settings.SettingsRepository
import io.cosgriff.sightline.data.settings.ThemeSetting
import io.cosgriff.sightline.data.settings.Units
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings: each change is written straight away; the repository keeps values in range. */
@HiltViewModel
class SettingsViewModel @Inject constructor(private val repository: SettingsRepository) : ViewModel() {
    val settings: StateFlow<Settings?> = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setTheme(theme: ThemeSetting) = write { setTheme(theme) }

    fun setUnits(units: Units) = write { setUnits(units) }

    fun setThresholds(thresholds: Thresholds) = write { setThresholds(thresholds) }

    fun resetThresholds() = write { resetThresholds() }

    fun setPollInterval(ms: Long) = write { setPollInterval(ms) }

    fun setKeepScreenOn(on: Boolean) = write { setKeepScreenOn(on) }

    fun setToneRange(range: ToneRange) = write { setToneRange(range) }

    fun setRetentionDays(days: Int?) = write { setRetentionDays(days) }

    fun setHome(latitude: Double, longitude: Double) = write { setHome(latitude, longitude) }

    fun clearHome() = write { clearHome() }

    private fun write(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repository.block() }
    }
}
