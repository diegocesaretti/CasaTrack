package com.casatrack.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

object WifiProbe {
    fun current(context: Context): WifiState {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return WifiState(false, null, null)
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = findWifiNetwork(cm) ?: return WifiState(false, null, null)
        val caps = cm.getNetworkCapabilities(network) ?: return WifiState(false, null, null)

        var info: WifiInfo? = if (Build.VERSION.SDK_INT >= 29) caps.transportInfo as? WifiInfo else null
        if (info == null) {
            @Suppress("DEPRECATION")
            info = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
        }

        val ssid = info?.ssid
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.takeUnless { it == WifiManager.UNKNOWN_SSID }
        val bssid = info?.bssid?.takeUnless { it == "02:00:00:00:00:00" }
        return WifiState(true, ssid, bssid)
    }

    private fun findWifiNetwork(cm: ConnectivityManager): Network? {
        val active = cm.activeNetwork
        if (active != null && cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            return active
        }

        // A VPN can become Android's active network while the phone is still
        // physically connected to Wi-Fi. Scan all networks so Wi-Fi hold still works.
        return cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }
}
