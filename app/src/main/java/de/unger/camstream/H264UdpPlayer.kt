package de.unger.camstream
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.*
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

    fun start() {
        if (running) return
        running = true

        createDecoder()
        openSocket()

        lastPacketAtMs = SystemClock.elapsedRealtime()
        lastRecoveryAtMs = 0L

        receiveJob = scope.launch { receiveLoop() }
        watchdogJob = scope.launch { watchdogLoop() }
    }

    fun stop() {
        running = false

        receiveJob?.cancel()
        watchdogJob?.cancel()

        try { socket?.close() } catch (_: Exception) {}
        socket = null

        releaseDecoder()

        scope.cancel()
        dispatcher.close()
    }

    private fun createDecoder() {
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            configure(format, surface, null, 0)
            start()
        }
    }

    private fun releaseDecoder() {
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
    }

    private fun recreateDecoder() {
        releaseDecoder()
        depacketizer.reset()
        createDecoder()
    }

    private fun openSocket() {
        socket = DatagramSocket(port).apply {
            soTimeout = 1000
        }
    }

    private suspend fun receiveLoop() {
        val buf = ByteArray(1500)

        while (running && isActive) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket?.receive(packet) ?: break

                lastPacketAtMs = SystemClock.elapsedRealtime()

                val nals = depacketizer.push(packet.data, packet.length)
                for (nal in nals) {
                    queueNal(nal)
                    drainOutput()
                }
            } catch (_: SocketTimeoutException) {
                // allow watchdog to decide
            } catch (_: Exception) {
                delay(250)
            }
        }
    }

    private suspend fun watchdogLoop() {
        while (running && isActive) {
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

        val startCode = byteArrayOf(0, 0, 0, 1)
        input.put(startCode)
        input.put(nal)

        val ptsUs = System.nanoTime() / 1000L
        codec.queueInputBuffer(index, 0, startCode.size + nal.size, ptsUs, 0)
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