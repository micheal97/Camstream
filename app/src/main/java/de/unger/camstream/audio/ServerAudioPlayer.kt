package de.unger.camstream.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class ServerAudioPlayer(
    private val serverUrl: String,
    private val room: String = "left-audio"
) {
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var job: Job? = null
    private var decoder: MediaCodec? = null
    private var track: AudioTrack? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true

        job = scope.launch {
            client.webSocket("$serverUrl/audio/$room/recv") {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> handleConfig(frame.readText())
                        is Frame.Binary -> handleAac(frame.readBytes())
                        else -> Unit
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        releaseDecoder()
        releaseTrack()
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun handleConfig(text: String) {
        if (!text.startsWith("CFG ")) return
        val parts = text.split(' ')
        if (parts.size < 4) return

        val sampleRate = parts[1].toIntOrNull() ?: return
        val channels = parts[2].toIntOrNull() ?: return
        val csd0 = android.util.Base64.decode(parts[3], android.util.Base64.NO_WRAP)

        releaseDecoder()
        releaseTrack()

        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
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
                .setChannelMask(channelMask)
                .build(),
            maxOf(minBuf, 4096),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        ).apply {
            play()
        }

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channels
        ).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        }

        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private fun handleAac(data: ByteArray) {
        val codec = decoder ?: return

        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val input = codec.getInputBuffer(inputIndex) ?: return
            input.clear()
            input.put(data)
            val ptsUs = System.nanoTime() / 1000L
            codec.queueInputBuffer(inputIndex, 0, data.size, ptsUs, 0)
        }

        drainDecoder()
    }

    private fun drainDecoder() {
        val codec = decoder ?: return
        val audioTrack = track ?: return
        val info = MediaCodec.BufferInfo()

        while (true) {
            when (val outIndex = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (outIndex >= 0) {
                    val out = codec.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        out.get(pcm)
                        audioTrack.write(pcm, 0, pcm.size)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                } else {
                    return
                }
            }
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

    private fun releaseTrack() {
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
}