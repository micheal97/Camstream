package de.unger.camstream

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.*
import android.util.Range
import android.util.Size
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class CameraSenderActivity : AppCompatActivity() {

    private val tabletIp = "192.168.1.100"
    private val tabletPort = 5004

    private val width = 640
    private val height = 480
    private val fps = 5
    private val bitrate = 300_000

    private lateinit var cameraManager: CameraManager
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var encoder: MediaCodec? = null
    private var encoderSurface: android.view.Surface? = null
    private var packetizer: H264RtpPacketizer? = null

    private var drainJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startPipeline()
        else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val v = View(this)
        v.setBackgroundColor(0xFF000000.toInt())
        setContentView(v)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = 0.01f
        }

        cameraManager = getSystemService(CameraManager::class.java)
        cameraThread = HandlerThread("camera-thread").also { it.start() }
        cameraHandler = Handler(cameraThread.looper)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startPipeline()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPipeline()
        cameraThread.quitSafely()
    }

    private fun startPipeline() {
        packetizer = H264RtpPacketizer(tabletIp, tabletPort, fps = fps)
        startEncoder()
        openBackCamera()
        startEncoderDrainLoop()
    }

    private fun stopPipeline() {
        drainJob?.cancel()

        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null

        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null

        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        encoderSurface = null

        try { packetizer?.close() } catch (_: Exception) {}
        packetizer = null
    }

    private fun startEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = createInputSurface()
            start()
        }
    }

    private fun openBackCamera() {
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: error("No back camera found")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createSession(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
            }
        }, cameraHandler)
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
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                        set(CaptureRequest.NOISE_REDUCTION_MODE,
                            CaptureRequest.NOISE_REDUCTION_MODE_FAST)
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

        drainJob = lifecycleScope.launch(Dispatchers.IO) {
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
                                val keyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
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