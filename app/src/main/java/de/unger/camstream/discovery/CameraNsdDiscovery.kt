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
    private var started = false

    fun start() {
        if (started) return
        started = true

        multicastLock = wifiManager.createMulticastLock("camstream-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onDiscoveryStopped(serviceType: String) {
                started = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                started = false
                try {
                    nsdManager.stopServiceDiscovery(this)
                } catch (_: Exception) {
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                started = false
                try {
                    nsdManager.stopServiceDiscovery(this)
                } catch (_: Exception) {
                }
            }

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

        try {
            nsdManager.discoverServices(
                CameraNsdPublisher.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
        } catch (_: Exception) {
            started = false
            try {
                multicastLock?.release()
            } catch (_: Exception) {
            }
            multicastLock = null
        }
    }

    fun stop() {
        val listener = discoveryListener
        discoveryListener = null
        started = false

        if (listener != null) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }

        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }
}