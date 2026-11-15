package io.cosgriff.sightline.data.monitor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The phone's own Wi-Fi as it is now (SPEC "Phone Wi-Fi"). [connected] false: off Wi-Fi, and the rest null. Each
 * other field is null when the platform doesn't give it; [bssid] also without precise location.
 */
data class WifiState(
    val connected: Boolean,
    val rssiDbm: Int? = null,
    val linkMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val bssid: String? = null,
) {
    companion object {
        val OFF = WifiState(connected = false)
    }
}

/**
 * Where a monitor session hears about the phone's Wi-Fi (SPEC "Phone Wi-Fi"): the platform on a phone
 * ([io.cosgriff.sightline.data.net.AndroidPhoneWifi]), a script in tests. The monitor stamps each state with its
 * clock as it comes and stores the ones that differ from the last.
 */
fun interface PhoneWifi {
    /** The Wi-Fi now, as soon as it is known, then each change, for as long as it is collected. */
    fun states(): Flow<WifiState>

    companion object {
        /** Nothing known: no samples are stored. */
        val NONE = PhoneWifi { emptyFlow() }
    }
}
