package de.unger.camstream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

class H264UdpPlayer(
    private val surface: Surface,
    private val port: Int,
    private val width: Int = 640,
    private val height: Int = 480,
    private val stallTimeoutMs: Long = 3000,
    private val hardResetTimeoutMs: Long = 8000,
    private val onSoftStall: suspend () -> Unit = {},
    private val onHardStall: suspend () -> Unit = {}
) {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private var socket: DatagramSocket? = null
    private var decoder: MediaCodec? = null
    private val depacketizer = H264RtpDepacketizer()

    @Volatile
    private var running = false

    @Volatile
    private var lastPacketAtMs = 0L

    @Volatile
    private var lastRecoveryAtMs = 0L

    private var receiveJob: Job? = null
    private var watchdogJob: Job? = null
    private var released = false
    fun start() {
        if (running || released) return
        running = true

        openSocket()
        lastPacketAtMs = android.os.SystemClock.elapsedRealtime()
        lastRecoveryAtMs = 0L

        receiveJob = scope.launch { receiveLoop() }
        watchdogJob = scope.launch { watchdogLoop() }
    }


    fun stop() {
        if (released) return
        released = true
        running = false

        receiveJob?.cancel()
        watchdogJob?.cancel()
        receiveJob = null
        watchdogJob = null

        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null

        releaseDecoder()
        depacketizer.reset()
        scope.cancel()
        dispatcher.close()
    }


    private fun createDecoder(sps: ByteArray, pps: ByteArray) {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width,
                height
            ).apply {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
            }

            codec.configure(format, surface, null, 0)
            codec.start()
            decoder = codec
        } catch (t: Throwable) {
            try {
                codec.release()
            } catch (_: Exception) {
            }
            decoder = null
            Log.e("H264UdpPlayer", "Decoder init failed", t)
        }
    }

    private fun releaseDecoder() {
        try {
            decoder?.stop()
        } catch (_: Exception) {
        }
        try {
            decoder?.release()
        } catch (_: Exception) {
        }
        decoder = null
    }

    private fun recreateDecoder() {
        releaseDecoder()
        depacketizer.reset()
    }

    private fun openSocket() {
        try {
            // Falls aus irgendeinem Grund noch ein alter Socket offen ist -> schließen!
            socket?.close()

            socket = DatagramSocket(port).apply {
                soTimeout = 1000
            }
        } catch (e: BindException) {
            Log.e(
                "H264UdpPlayer",
                "Port $port wird bereits verwendet! Stream kann nicht gestartet werden.",
                e
            )
            // Hier eventuell einen Callback an die Activity feuern, um es später erneut zu versuchen
        }
    }

    private suspend fun receiveLoop() {
        val buf = ByteArray(1500)

        while (running && currentCoroutineContext().isActive) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket?.receive(packet) ?: break
                lastPacketAtMs = SystemClock.elapsedRealtime()

                val nals = depacketizer.push(packet.data, packet.length)

                // 2. Prüfe, ob SPS/PPS JETZT im Live-Stream angekommen sind
                if (decoder == null && depacketizer.sps != null && depacketizer.pps != null) {
                    // Decoder erst genau hier erstellen, wenn die Daten da sind!

                    createDecoder(depacketizer.sps!!, depacketizer.pps!!)
                }

                // 3. Wenn der Decoder läuft, füttere ihn mit den Frames
                if (decoder != null) {
                    for (nal in nals) {
                        queueNal(nal)
                        drainOutput()
                    }
                }
            } catch (_: SocketTimeoutException) {
                // allow watchdog to decide
            } catch (_: Exception) {
                delay(250)
            }
        }
    }

    private suspend fun watchdogLoop() {
        while (running && currentCoroutineContext().isActive) {
            delay(1000)

            val now = SystemClock.elapsedRealtime()
            val silence = now - lastPacketAtMs
            val sinceRecovery = now - lastRecoveryAtMs

            if (silence >= hardResetTimeoutMs && sinceRecovery >= hardResetTimeoutMs) {
                lastRecoveryAtMs = now
                recreateDecoder()
                onHardStall()
            } else if (silence >= stallTimeoutMs && sinceRecovery >= stallTimeoutMs) {
                lastRecoveryAtMs = now
                onSoftStall()
            }
        }
    }

    private fun queueNal(nal: ByteArray) {
        val codec = decoder ?: return
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return

        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        input.put(nal)

        val ptsUs = System.nanoTime() / 1000L
        codec.queueInputBuffer(index, 0, nal.size, ptsUs, 0)
    }

    private fun drainOutput() {
        val codec = decoder ?: return
        val info = MediaCodec.BufferInfo()

        while (true) {
            when (val outIndex = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (outIndex >= 0) {
                    codec.releaseOutputBuffer(outIndex, true)
                } else {
                    return
                }
            }
        }
    }

}