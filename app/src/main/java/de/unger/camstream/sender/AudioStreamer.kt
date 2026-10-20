package de.unger.camstream.sender

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class AudioStreamer(
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: DatagramSocket? = null
    private var target: InetSocketAddress? = null
    private var record: AudioRecord? = null
    private var job: Job? = null

    private val running = AtomicBoolean(false)
    private var sequence: Int = 0

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(host: String, port: Int) {
        synchronized(this) {
            val same = running.get() && target?.address?.hostAddress == host && target?.port == port
            if (same) return

            stopLocked()

            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBuffer, 2048)

            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            socket = DatagramSocket()
            target = InetSocketAddress(InetAddress.getByName(host), port)

            running.set(true)

            record?.startRecording()

            job = scope.launch {
                val audioBuf = ByteArray(1024)

                while (isActive && running.get()) {
                    val read = record?.read(audioBuf, 0, audioBuf.size) ?: break
                    if (read <= 0) continue

                    val packet = buildPacket(audioBuf, read)
                    val t = target ?: break
                    socket?.send(DatagramPacket(packet, packet.size, t))
                }
            }
        }
    }

    fun stop() {
        synchronized(this) {
            stopLocked()
        }
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun stopLocked() {
        running.set(false)

        job?.cancel()
        job = null

        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null

        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        target = null
        sequence = 0
    }

    private fun buildPacket(data: ByteArray, length: Int): ByteArray {
        // tiny custom header:
        // 0..3  magic 'AUD0'
        // 4..7  sequence int
        // 8..11 sampleRate int
        // 12..13 payload length short
        val out = ByteArray(14 + length)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        bb.put('A'.code.toByte())
        bb.put('U'.code.toByte())
        bb.put('D'.code.toByte())
        bb.put('0'.code.toByte())
        bb.putInt(sequence++)
        bb.putInt(sampleRate)
        bb.putShort(length.toShort())
        bb.put(data, 0, length)
        return out
    }
}
