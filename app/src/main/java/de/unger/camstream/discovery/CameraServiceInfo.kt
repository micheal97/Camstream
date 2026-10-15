package de.unger.camstream.discovery

import java.net.InetAddress

data class CameraServiceInfo(
    val name: String,
    val host: InetAddress,
    val port: Int
)