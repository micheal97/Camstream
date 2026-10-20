package de.unger.camstream.audio

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AudioRelayClient(
    private val serverUrl: String
) {
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private val mutex = Mutex()
    private var session: WebSocketSession? = null

    suspend fun connectSender(room: String) {
        mutex.withLock {
            close()
            session = client.webSocketSession("$serverUrl/audio/$room/send")
        }
    }

    suspend fun sendConfig(sampleRate: Int, channels: Int, csd0: ByteArray) {
        val base64 = Base64.encodeToString(csd0, Base64.NO_WRAP)
        session?.send(Frame.Text("CFG $sampleRate $channels $base64"))
    }

    suspend fun sendAacFrame(data: ByteArray) {
        session?.send(Frame.Binary(true, data))
    }

    suspend fun close() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
    }

    fun release() {
        try {
            client.close()
        } catch (_: Exception) {
        }
    }
}