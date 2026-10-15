package de.unger.camstream.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.Socket

class SenderControlClient(
    private val host: InetAddress,
    private val port: Int
) {
    suspend fun startStream(tabletIp: String, tabletPort: Int): String? =
        send("START $tabletIp $tabletPort")

    suspend fun stopStream(): String? =
        send("STOP")

    suspend fun requestKeyframe(): String? =
        send("KEYFRAME")

    suspend fun ping(): String? =
        send("PING")

    private suspend fun send(command: String): String? = withContext(Dispatchers.IO) {
        Socket(host, port).use { socket ->
            socket.soTimeout = 3000

            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            writer.println(command)
            reader.readLine()
        }
    }
}