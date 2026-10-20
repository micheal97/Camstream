package de.unger.camstream.sender

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class SenderControlServer(
    private val port: Int,
    private val callback: Callback
) : Closeable {
    interface Callback {
        fun onStartStream(targetHost: String, videoPort: Int, audioPort: Int)
        fun onStopStream()
        fun onRequestKeyframe()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    fun start() {
        serverSocket = ServerSocket(port)
        scope.launch {
            while (isActive) {
                val client = try {
                    serverSocket?.accept()
                } catch (_: Exception) {
                    null
                } ?: break

                launch {
                    handleClient(client)
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val writer = PrintWriter(s.getOutputStream(), true)

            val line = reader.readLine()?.trim().orEmpty()
            val parts = line.split(' ')

            try {
                when (parts.firstOrNull()?.uppercase()) {
                    "START" -> {
                        val host = parts.getOrNull(1) ?: error("missing host")
                        val videoPort =
                            parts.getOrNull(2)?.toIntOrNull() ?: error("missing video port")
                        val audioPort =
                            parts.getOrNull(3)?.toIntOrNull() ?: error("missing audio port")
                        callback.onStartStream(host, videoPort, audioPort)
                        writer.println("OK")
                    }

                    "STOP" -> {
                        callback.onStopStream()
                        writer.println("OK")
                    }

                    "KEYFRAME" -> {
                        callback.onRequestKeyframe()
                        writer.println("OK")
                    }

                    "PING" -> {
                        writer.println("PONG")
                    }

                    else -> writer.println("ERR unknown-command")
                }
            } catch (t: Throwable) {
                writer.println("ERR ${t.message}")
            }
        }
    }

    override fun close() {
        scope.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }
}