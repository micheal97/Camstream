package de.unger.camstream.sender
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

class CameraNsdPublisher(
    context: Context,
    private val serviceName: String,
    private val port: Int
) {
    companion object {
        const val SERVICE_TYPE = "_h264cam._tcp."
    }

    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun register() {
        val info = NsdServiceInfo().apply {
            this.serviceType = SERVICE_TYPE
            this.serviceName = this@CameraNsdPublisher.serviceName
            this.port = this@CameraNsdPublisher.port
            try {
                setAttribute("codec", "h264")
                setAttribute("transport", "udp-push")
                setAttribute("proto", "control-v1")
            } catch (_: Exception) {
            }
        }

        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        }

        listener = l
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun unregister() {
        listener?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
        }
        listener = null
    }
}