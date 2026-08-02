package one.globalconnect.pinpad.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import java.net.Inet4Address

internal data class LanAddress(
    val address: Inet4Address,
    val prefixLength: Int,
) {
    fun isInSameSubnet(other: Inet4Address): Boolean {
        val left = address.address
        val right = other.address
        if (left.size != right.size) return false
        var remaining = prefixLength
        for (index in left.indices) {
            if (remaining <= 0) return true
            val bits = remaining.coerceAtMost(8)
            val mask = (0xFF shl (8 - bits)) and 0xFF
            if ((left[index].toInt() and mask) != (right[index].toInt() and mask)) return false
            remaining -= bits
        }
        return true
    }
}

internal fun Context.activeLanIpv4Addresses(): List<LanAddress> {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    return manager.allNetworks.flatMap { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@flatMap emptyList()
        val allowed = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!allowed) return@flatMap emptyList()
        val properties = manager.getLinkProperties(network) ?: return@flatMap emptyList()
        properties.linkAddresses.mapNotNull(LinkAddress::toLanAddressOrNull)
    }.distinctBy { it.address.hostAddress }
}

private fun LinkAddress.toLanAddressOrNull(): LanAddress? {
    val ipv4 = address as? Inet4Address ?: return null
    if (ipv4.isAnyLocalAddress || ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress) return null
    return LanAddress(ipv4, prefixLength.coerceIn(0, 32))
}
