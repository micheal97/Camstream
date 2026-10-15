package de.unger.camstream.sender

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import androidx.annotation.RequiresPermission
import de.unger.camstream.H264CodecUtil
import de.unger.camstream.H264RtpPacketizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CameraStreamer(
    context: Context,
    private val width: Int = 640,
    private val height: Int = 480,
    private val fps: Int = 5,
    private val bitrate: Int = 300_000
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cameraThread = HandlerThread("cam-streamer-camera").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var packetizer: H264RtpPacketizer? = null

    private var drainJob: Job? = null

    private var targetHost: String? = null
    private var targetPort: Int? = null

    private val running = AtomicBoolean(false)

    @androidx.annotation.RequiresPermission(android.Manifest.permission.CAMERA)
    fun start(host: String, port: Int) {
        synchronized(this) {
            val alreadySameTarget = running.get() && targetHost == host && targetPort == port
            if (alreadySameTarget) return

            stopLocked()

            targetHost = host
            targetPort = port

            packetizer = H264RtpPacketizer(host, port, fps = fps)
            startEncoder()
            openBackCamera()
            startEncoderDrainLoop()

            running.set(true)
        }
    }

    fun stop() {
        synchronized(this) {
            stopLocked()
        }
    }

    fun release() {
        stop()
        cameraThread.quitSafely()
        scope.cancel()
    }

    fun requestSyncFrame() {
        try {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Throwable) {
        }
    }

    private fun stopLocked() {
        running.set(false)
        drainJob?.cancel()
        drainJob = null

        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null

        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null
        encoderSurface = null

        try {
            packetizer?.close()
        } catch (_: Exception) {
        }
        packetizer = null
    }

    private fun startEncoder() {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
            try {
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
            } catch (_: Exception) {
            }
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = createInputSurface()
            start()
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openBackCamera() {
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: error("No back camera found")

        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try {
                        camera.close()
                    } catch (_: Exception) {
                    }
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    try {
                        camera.close()
                    } catch (_: Exception) {
                    }
                    cameraDevice = null
                }
            },
            cameraHandler
        )
    }

    private fun createSession(camera: CameraDevice) {
        val surface = encoderSurface ?: return

        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session

                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)

                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                        set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                        )
                        set(
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                        )
                        set(
                            CaptureRequest.NOISE_REDUCTION_MODE,
                            CaptureRequest.NOISE_REDUCTION_MODE_FAST
                        )
                        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                        set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }.build()

                    session.setRepeatingRequest(request, null, cameraHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                }
            },
            cameraHandler
        )
    }

    private fun startEncoderDrainLoop() {
        val codec = encoder ?: return
        val rtp = packetizer ?: return

        drainJob = scope.launch {
            val info = MediaCodec.BufferInfo()

            while (isActive) {
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        val sps = H264CodecUtil.extractCsdNal(fmt, "csd-0")
                        val pps = H264CodecUtil.extractCsdNal(fmt, "csd-1")
                        rtp.setCodecConfig(sps, pps)
                    }

                    else -> if (index >= 0) {
                        val out = codec.getOutputBuffer(index)?.duplicate()
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)

                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                val frame = ByteArray(info.size)
                                out.get(frame)

                                val nals = H264CodecUtil.splitAccessUnit(frame)
                                val keyFrame =
                                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                rtp.sendAccessUnit(nals, keyFrame)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
    }
}
