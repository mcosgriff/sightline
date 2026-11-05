package io.cosgriff.sightline.ui.screens.compare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.cosgriff.sightline.analysis.SweepComparison
import io.cosgriff.sightline.data.session.SessionDetail
import io.cosgriff.sightline.data.session.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Two sessions side by side: [before] started first. Null [comparison] while loading; [missing] when one is gone. */
data class CompareUiState(
    val loading: Boolean = true,
    val before: SessionDetail? = null,
    val after: SessionDetail? = null,
    val comparison: SweepComparison? = null,
) {
    val missing: Boolean get() = !loading && (before == null || after == null)
}

/**
 * History's Compare (SPEC Screens › 9): the summary figures of two sessions and each channel's median quality
 * before and after, with the same "3 points or more" change rule as a sweep comparison.
 */
@HiltViewModel(assistedFactory = CompareViewModel.Factory::class)
class CompareViewModel @AssistedInject constructor(
    @Assisted("a") private val a: Long,
    @Assisted("b") private val b: Long,
    private val sessions: SessionRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(@Assisted("a") a: Long, @Assisted("b") b: Long): CompareViewModel
    }

    private val _state = MutableStateFlow(CompareUiState())
    val state: StateFlow<CompareUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val (x, y) = withContext(Dispatchers.Default) {
                val first = async { sessions.load(a, zone) }
                val second = async { sessions.load(b, zone) }
                first.await() to second.await()
            }
            val (before, after) = if ((x?.info?.startedAt ?: 0) <= (y?.info?.startedAt ?: 0)) x to y else y to x
            val comparison = if (before != null && after != null) SweepComparison.of(before.stats.channels, after.stats.channels) else null
            _state.value = CompareUiState(false, before, after, comparison)
        }
    }
}
