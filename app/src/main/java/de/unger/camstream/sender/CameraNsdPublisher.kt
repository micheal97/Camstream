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
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun register() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = this@CameraNsdPublisher.serviceName
            this.port = this@CameraNsdPublisher.port

            try {
                setAttribute("role", "sender")
                setAttribute("codec", "h264")
                setAttribute("version", "1")
            } catch (_: Exception) {
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            }
        }

        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregister() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (_: Exception) {
            }
        }
        registrationListener = null
    }
}