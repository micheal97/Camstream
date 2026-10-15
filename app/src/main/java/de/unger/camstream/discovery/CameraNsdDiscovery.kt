package de.unger.camstream.discovery


import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import de.unger.camstream.sender.CameraNsdPublisher

class CameraNsdDiscovery(
    context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onCameraFound(info: CameraServiceInfo)
        fun onCameraLost(name: String)
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        multicastLock = wifiManager.createMulticastLock("camstream-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != CameraNsdPublisher.SERVICE_TYPE) return

                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        }

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host ?: return
                            callback.onCameraFound(
                                CameraServiceInfo(
                                    name = resolved.serviceName,
                                    host = host,
                                    port = resolved.port
                                )
                            )
                        }
                    }
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                callback.onCameraLost(serviceInfo.serviceName)
            }
        }

        discoveryListener = listener
        nsdManager.discoverServices(
            CameraNsdPublisher.SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            listener
        )
    }

    fun stop() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
        }
        discoveryListener = null

        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }
}