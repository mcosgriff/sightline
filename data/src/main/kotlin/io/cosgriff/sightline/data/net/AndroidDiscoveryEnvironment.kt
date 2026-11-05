package io.cosgriff.sightline.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.cosgriff.sightline.protocol.DiscoveryEnvironment
import java.net.Inet4Address
import java.net.InetAddress
import javax.inject.Inject

/**
 * Discovery's Android side (ADR-0003): a `MulticastLock` while replies are collected, since many phones drop
 * inbound broadcast without one, and the directed broadcast address of the active network's IPv4 subnet.
 */
class AndroidDiscoveryEnvironment @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DiscoveryEnvironment {
    override suspend fun <T> withBroadcastReception(block: suspend () -> T): T {
        val wifi = context.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock(LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            return block()
        } finally {
            lock?.release()
        }
    }

    override fun broadcastAddresses(): List<InetAddress> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val properties = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return emptyList()
        return properties.linkAddresses
            .filter { it.address is Inet4Address && it.prefixLength in 1..30 }
            .map { directedBroadcast(it.address.address, it.prefixLength) }
    }

    companion object {
        private const val LOCK_TAG = "sightline:discovery"

        /** `192.168.1.21/24` → `192.168.1.255`. */
        fun directedBroadcast(address: ByteArray, prefixLength: Int): InetAddress {
            val ip = address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            val hostMask = (1L shl (32 - prefixLength)) - 1
            val broadcast = ip or hostMask
            return InetAddress.getByAddress(ByteArray(4) { i -> (broadcast shr (24 - 8 * i)).toByte() })
        }
    }
}
