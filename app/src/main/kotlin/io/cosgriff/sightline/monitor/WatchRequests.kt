package io.cosgriff.sightline.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/** A tuner to watch, asked for on Diagnostics and filled into Monitor's setup. */
data class WatchRequest(val deviceId: String, val tuner: Int)

/** Hands a [WatchRequest] from Diagnostics to Monitor's setup, which takes it once. */
@Singleton
class WatchRequests @Inject constructor() {
    private val _pending = MutableStateFlow<WatchRequest?>(null)
    val pending: StateFlow<WatchRequest?> = _pending.asStateFlow()

    fun request(deviceId: String, tuner: Int) {
        _pending.value = WatchRequest(deviceId, tuner)
    }

    /** The pending request, cleared. */
    fun take(): WatchRequest? = _pending.getAndUpdate { null }
}
