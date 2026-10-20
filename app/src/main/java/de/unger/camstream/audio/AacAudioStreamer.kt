package de.unger.camstream.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AacAudioStreamer(
    private val serverUrl: String,
    private val room: String = "left-audio",
    private val sampleRate: Int = 16000,
    private val channelCount: Int = 1,
    private val bitrate: Int = 32_000
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relayClient = AudioRelayClient(serverUrl)

    private var record: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var captureJob: Job? = null
    private var drainJob: Job? = null

    private val running = AtomicBoolean(false)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running.get()) return
        running.set(true)

        scope.launch {
            runCatching {
                relayClient.connectSender(room)
                startEncoder()
                startRecorder()
                startCaptureLoop()
                startDrainLoop()
            }.onFailure {
                stop()
            }
        }
    }

    fun stop() {
        running.set(false)

        captureJob?.cancel()
        drainJob?.cancel()
        captureJob = null
        drainJob = null

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
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null

        scope.launch {
            runCatching { relayClient.close() }
        }
    }

    fun release() {
        stop()
        relayClient.release()
        scope.cancel()
    }

    private fun startEncoder() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecorder() {
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val pcmFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, pcmFormat)
        val bufferSize = maxOf(minBuffer, 4096)

        record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelMask,
            pcmFormat,
            bufferSize
        ).apply {
            startRecording()
        }
    }

    private fun startCaptureLoop() {
        val codec = encoder ?: return
        val audioRecord = record ?: return

        captureJob = scope.launch {
            val pcm = ByteArray(2048)

            while (isActive && running.get()) {
                val read = audioRecord.read(pcm, 0, pcm.size)
                if (read <= 0) continue

                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = codec.getInputBuffer(index) ?: continue
                    input.clear()
                    input.put(pcm, 0, read)
                    val ptsUs = System.nanoTime() / 1000L
                    codec.queueInputBuffer(index, 0, read, ptsUs, 0)
                }
            }
        }
    }

    private fun startDrainLoop() {
        val codec = encoder ?: return

        drainJob = scope.launch {
            val info = MediaCodec.BufferInfo()

            while (isActive && running.get()) {
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val csd0 = codec.outputFormat.getByteBuffer("csd-0")?.toByteArray()
                        if (csd0 != null) {
                            runCatching {
                                relayClient.sendConfig(sampleRate, channelCount, csd0)
                            }
                        }
                    }

                    else -> if (index >= 0) {
                        val out = codec.getOutputBuffer(index)?.duplicate()
                        if (out != null &&
                            info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)

                            val data = ByteArray(info.size)
                            out.get(data)

                            runCatching {
                                relayClient.sendAacFrame(data)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val dup = duplicate()
        val arr = ByteArray(dup.remaining())
        dup.get(arr)
        return arr
    }
}