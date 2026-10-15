package de.unger.camstream.net
import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import androidx.annotation.RequiresPermission
import java.net.Inet4Address

    object NetworkUtil {
        @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
        fun currentIpv4Address(context: Context): String? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork ?: return null
            val props = cm.getLinkProperties(network) ?: return null

            return props.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
        }
}