package com.nullsignal.launcher.sys

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

data class LocalNet(val ip: String, val prefix: Int, val gateway: String?, val onWifi: Boolean)

object NetInfo {
    /** Свой IPv4-адрес, маска и шлюз активной сети. */
    fun local(ctx: Context): LocalNet? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val wifi = cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        return LocalNet(la.address.hostAddress ?: return null, la.prefixLength, gw, wifi)
    }
}
