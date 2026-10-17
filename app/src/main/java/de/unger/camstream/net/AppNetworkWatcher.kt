package de.unger.camstream.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

class AppNetworkWatcher(
    context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onNetworkLost()
        fun onNetworkChanged()
    }

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false

    private val notifyChangedRunnable = Runnable {
        callback.onNetworkChanged()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleChanged()
        }

        override fun onLost(network: Network) {
            mainHandler.removeCallbacks(notifyChangedRunnable)
            mainHandler.post {
                callback.onNetworkLost()
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            scheduleChanged()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            scheduleChanged()
        }
    }

    fun start() {
        if (registered) return
        registered = true
        cm.registerDefaultNetworkCallback(networkCallback)
    }

    fun stop() {
        if (!registered) return
        registered = false
        mainHandler.removeCallbacks(notifyChangedRunnable)
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    private fun scheduleChanged() {
        mainHandler.removeCallbacks(notifyChangedRunnable)
        mainHandler.postDelayed(notifyChangedRunnable, 1500)
    }
}