package io.cosgriff.sightline.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.session.HistoryRow
import io.cosgriff.sightline.data.session.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val loading: Boolean = true,
    val rows: List<HistoryRow> = emptyList(),
    /** Device names by ID, for rows when sessions come from more than one device. */
    val deviceNames: Map<String, String> = emptyMap(),
    val selected: Set<Long> = emptySet(),
) {
    val manyDevices: Boolean get() = rows.map { it.info.deviceId }.distinct().size > 1
}

/** History (SPEC Screens › 9): saved sessions newest first; select to delete. */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val sessions: SessionRepository,
    devices: DeviceRepository,
) : ViewModel() {
    private val selected = MutableStateFlow<Set<Long>>(emptySet())

    val state: StateFlow<HistoryUiState> = combine(sessions.history, devices.all, selected) { rows, all, sel ->
        val ids = rows.map { it.info.id }.toSet()
        HistoryUiState(false, rows, all.associate { it.deviceId to it.friendlyName }, sel intersect ids)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    init {
        // Sessions ended while the app was away (or before History existed) get their summaries.
        viewModelScope.launch { sessions.summariseMissing() }
    }

    fun toggle(id: Long) = selected.update { if (id in it) it - id else it + id }

    fun clearSelection() = selected.update { emptySet() }

    fun deleteSelected() {
        val ids = selected.value
        selected.update { emptySet() }
        viewModelScope.launch { sessions.delete(ids) }
    }
}
