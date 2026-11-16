package com.example.mifirewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor

class FirewallVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var connectivityManager: ConnectivityManager

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_RELOAD = "ACTION_RELOAD"
        var isRunning = false
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            if (isRunning) {
                establishFirewall()
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            if (isRunning) {
                establishFirewall()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopFirewall()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_RELOAD -> {
                startForegroundServiceNotification()
                isRunning = true
                establishFirewall()
            }
        }
        return START_STICKY
    }

    private fun establishFirewall() {
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        val allowedWifi = prefs.getStringSet("allowed_wifi", emptySet()) ?: emptySet()
        val allowedMobile = prefs.getStringSet("allowed_mobile", emptySet()) ?: emptySet()

        val isWifi = isConnectedToWifi()
        val appsToAllow = if (isWifi) allowedWifi else allowedMobile

        try {
            vpnInterface?.close()
            vpnInterface = null

            val builder = Builder()
                .setSession("MiFirewall")
                .addAddress("10.1.10.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
                .addRoute("::", 0)

            // Excluimos del túnel sin salida a las apps que tienen la casilla marcada (Permitidas).
            // Todo lo que NO esté marcado entrará al túnel y se quedará sin internet.
            for (pkg in appsToAllow) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Excluimos también a nuestra propia app por seguridad
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {}

            vpnInterface = builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isConnectedToWifi(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun stopFirewall() {
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
        vpnInterface = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {}
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "firewall_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Servicio de Firewall",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Firewall Activo")
                .setContentText("Solo las apps marcadas tienen acceso a Internet")
                .setSmallIcon(android.R.drawable.ic_secure)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Firewall Activo")
                .setContentText("Solo las apps marcadas tienen acceso a Internet")
                .setSmallIcon(android.R.drawable.ic_secure)
                .build()
        }

        startForeground(1, notification)
    }
}
