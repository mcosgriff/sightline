package io.cosgriff.sightline.data.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.cosgriff.sightline.data.monitor.PhoneWifi
import io.cosgriff.sightline.data.monitor.WifiState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale
import javax.inject.Inject

/**
 * The phone's Wi-Fi from the platform (SPEC "Phone Wi-Fi", ADR-0002): a `ConnectivityManager` callback on Wi-Fi
 * networks, each change read from `WifiInfo` (`NetworkCapabilities.transportInfo` on API 31+; on API 29–30, which
 * have no Wi-Fi transport info, `WifiManager.connectionInfo`). Losing the last Wi-Fi network is [WifiState.OFF].
 *
 * The BSSID comes only with precise location, which Sightline declares and never asks for: the user can turn it on in
 * Android's settings. Without it, the callback is registered without `FLAG_INCLUDE_LOCATION_INFO` and no BSSID is
 * kept; a redacted one counts as none.
 */
class AndroidPhoneWifi @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PhoneWifi {
    override fun states(): Flow<WifiState> = callbackFlow {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity == null) {
            close()
            return@callbackFlow
        }
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        // Callbacks come one at a time on the system's callback thread.
        val wifiNetworks = mutableSetOf<Network>()
        val callback = callback(
            precise,
            onChanged = { network, caps ->
                wifiNetworks += network
                trySend(read(caps, precise))
            },
            onGone = { network ->
                if (wifiNetworks.remove(network) && wifiNetworks.isEmpty()) trySend(WifiState.OFF)
            },
        )
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (_: RuntimeException) {
            // Too many callbacks registered, or no permission: the session goes on without Wi-Fi samples.
            close()
            return@callbackFlow
        }
        awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }

    private fun read(caps: NetworkCapabilities, precise: Boolean): WifiState {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) caps.transportInfo as? WifiInfo else legacyInfo()
        val strength = caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
        return WifiState(
            connected = true,
            rssiDbm = info?.rssi?.takeIf { it in MIN_RSSI until 0 } ?: strength,
            linkMbps = info?.linkSpeed?.takeIf { it > 0 },
            frequencyMhz = info?.frequency?.takeIf { it > 0 },
            bssid = info?.bssid?.lowercase(Locale.ROOT)?.takeIf { precise && it !in HIDDEN_BSSIDS },
        )
    }

    // API 29–30 have no WifiInfo in the network's transport info; this is the only way there.
    @Suppress("DEPRECATION")
    private fun legacyInfo(): WifiInfo? = context.getSystemService(WifiManager::class.java)?.connectionInfo

    /** The network callback, asking for the BSSID (API 31+) only with precise location. */
    private class Callback : ConnectivityManager.NetworkCallback {
        private val onChanged: (Network, NetworkCapabilities) -> Unit
        private val onGone: (Network) -> Unit

        constructor(onChanged: (Network, NetworkCapabilities) -> Unit, onGone: (Network) -> Unit) : super() {
            this.onChanged = onChanged
            this.onGone = onGone
        }

        @RequiresApi(Build.VERSION_CODES.S)
        constructor(flags: Int, onChanged: (Network, NetworkCapabilities) -> Unit, onGone: (Network) -> Unit) : super(flags) {
            this.onChanged = onChanged
            this.onGone = onGone
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) onChanged(network, networkCapabilities)
        }

        override fun onLost(network: Network) = onGone(network)
    }

    private fun callback(precise: Boolean, onChanged: (Network, NetworkCapabilities) -> Unit, onGone: (Network) -> Unit): Callback =
        if (precise && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Callback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO, onChanged, onGone)
        } else {
            Callback(onChanged, onGone)
        }

    private companion object {
        /** `WifiInfo`'s "no signal" value is −127; anything at or under it isn't a reading. */
        const val MIN_RSSI = -126

        /** What Android gives instead of the BSSID without location, or with no access point. */
        val HIDDEN_BSSIDS = setOf("02:00:00:00:00:00", "00:00:00:00:00:00")
    }
}
