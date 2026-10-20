package de.unger.camstream

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmAudioPlayer(
    private val port: Int
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: DatagramSocket? = null
    private var track: AudioTrack? = null
    private var job: Job? = null
    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true

        socket = DatagramSocket(port).apply {
            soTimeout = 1000
        }

        val sampleRate = 16000
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            maxOf(minBuffer, 4096),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        ).apply {
            play()
        }

        job = scope.launch {
            val buf = ByteArray(1500)

            while (running && currentCoroutineContext().isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket?.receive(packet) ?: break
                    handlePacket(packet.data, packet.length)
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null

        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null

        try {
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun handlePacket(data: ByteArray, length: Int) {
        if (length < 14) return
        if (data[0] != 'A'.code.toByte()) return
        if (data[1] != 'U'.code.toByte()) return
        if (data[2] != 'D'.code.toByte()) return
        if (data[3] != '0'.code.toByte()) return

        val bb = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        bb.position(4)
        bb.int // sequence
        bb.int // sampleRate
        val payloadLen = bb.short.toInt() and 0xFFFF
        if (payloadLen <= 0 || 14 + payloadLen > length) return

        track?.write(data, 14, payloadLen)
    }
}