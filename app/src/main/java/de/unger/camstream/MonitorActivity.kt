package de.unger.camstream

import android.Manifest
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import de.unger.camstream.audio.ServerAudioPlayer
import de.unger.camstream.control.SenderControlClient
import de.unger.camstream.discovery.CameraNsdDiscovery
import de.unger.camstream.discovery.CameraServiceInfo
import de.unger.camstream.net.NetworkUtil
import de.unger.camstream.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MonitorActivity : AppCompatActivity() {

    private lateinit var leftSurface: SurfaceView
    private lateinit var rightSurface: SurfaceView

    private var leftPlayer: H264UdpPlayer? = null
    private var rightPlayer: H264UdpPlayer? = null
    private var serverAudioPlayer: ServerAudioPlayer? = null

    private var leftSender: CameraServiceInfo? = null
    private var rightSender: CameraServiceInfo? = null

    private var leftControl: SenderControlClient? = null
    private var rightControl: SenderControlClient? = null

    private var leftSurfaceReady = false
    private var rightSurfaceReady = false

    private lateinit var discovery: CameraNsdDiscovery
    private lateinit var settingsStore: SettingsStore

    private var tabletLeftPort: Int = 5004
    private var tabletRightPort: Int = 5006
    private var softStallMs: Long = 3000L
    private var hardStallMs: Long = 8000L

    private var audioServerUrl: String = ""
    private var audioRoom: String = "left-audio"

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        leftSurface = findViewById(R.id.leftSurface)
        rightSurface = findViewById(R.id.rightSurface)

        leftSurface.holder.addCallback(object : SurfaceHolder.Callback {
            @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
            override fun surfaceCreated(holder: SurfaceHolder) {
                leftSurfaceReady = true
                maybeStartLeft()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                leftSurfaceReady = false
                leftPlayer?.stop()
                leftPlayer = null
            }
        })

        rightSurface.holder.addCallback(object : SurfaceHolder.Callback {
            @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
            override fun surfaceCreated(holder: SurfaceHolder) {
                rightSurfaceReady = true
                maybeStartRight()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                rightSurfaceReady = false
                rightPlayer?.stop()
                rightPlayer = null
            }
        })

        settingsStore = SettingsStore(this)
        val s = settingsStore.load()

        tabletLeftPort = s.leftUdpPort
        tabletRightPort = s.rightUdpPort
        softStallMs = s.softStallMs
        hardStallMs = s.hardStallMs
        audioServerUrl = s.audioServerUrl
        audioRoom = s.audioRoom

        discovery = CameraNsdDiscovery(this, object : CameraNsdDiscovery.Callback {
            @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
            override fun onCameraFound(info: CameraServiceInfo) {
                runOnUiThread {
                    when {
                        leftSender == null -> {
                            leftSender = info
                            leftControl = SenderControlClient(info.host, info.port)
                            maybeStartLeft()
                        }

                        rightSender == null && !sameEndpoint(leftSender, info) -> {
                            rightSender = info
                            rightControl = SenderControlClient(info.host, info.port)
                            maybeStartRight()
                        }
                    }
                }
            }

            override fun onCameraLost(name: String) {
                runOnUiThread {
                    if (leftSender?.name == name) {
                        leftPlayer?.stop()
                        leftPlayer = null
                        leftSender = null
                        leftControl = null
                    }

                    if (rightSender?.name == name) {
                        rightPlayer?.stop()
                        rightPlayer = null
                        rightSender = null
                        rightControl = null
                    }
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()

        discovery.start()

        if (serverAudioPlayer == null && audioServerUrl.isNotBlank()) {
            serverAudioPlayer = ServerAudioPlayer(
                serverUrl = audioServerUrl,
                room = audioRoom
            ).also { it.start() }
        }

        maybeStartLeft()
        maybeStartRight()
    }

    override fun onStop() {
        stopMonitoringSession()

        serverAudioPlayer?.release()
        serverAudioPlayer = null

        super.onStop()
    }

    private fun stopMonitoringSession() {
        discovery.stop()

        val left = leftControl
        val right = rightControl

        leftPlayer?.stop()
        rightPlayer?.stop()
        leftPlayer = null
        rightPlayer = null

        CoroutineScope(Dispatchers.IO).launch {
            runCatching { left?.stopStream() }
            runCatching { right?.stopStream() }
        }

        leftSender = null
        rightSender = null
        leftControl = null
        rightControl = null
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun maybeStartLeft() {
        if (!leftSurfaceReady || leftPlayer != null) return

        val tabletIp = NetworkUtil.currentIpv4Address(this) ?: return
        val control = leftControl ?: return

        leftPlayer = H264UdpPlayer(
            surface = leftSurface.holder.surface,
            port = tabletLeftPort,
            stallTimeoutMs = softStallMs,
            hardResetTimeoutMs = hardStallMs,
            onSoftStall = {
                control.requestKeyframe()
            },
            onHardStall = {
                control.startStream(tabletIp, tabletLeftPort)
            }
        ).also { it.start() }

        lifecycleScope.launch {
            runCatching { control.startStream(tabletIp, tabletLeftPort) }
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun maybeStartRight() {
        if (!rightSurfaceReady || rightPlayer != null) return

        val tabletIp = NetworkUtil.currentIpv4Address(this) ?: return
        val control = rightControl ?: return

        rightPlayer = H264UdpPlayer(
            surface = rightSurface.holder.surface,
            port = tabletRightPort,
            stallTimeoutMs = softStallMs,
            hardResetTimeoutMs = hardStallMs,
            onSoftStall = {
                control.requestKeyframe()
            },
            onHardStall = {
                control.startStream(tabletIp, tabletRightPort)
            }
        ).also { it.start() }

        lifecycleScope.launch {
            runCatching { control.startStream(tabletIp, tabletRightPort) }
        }
    }

    private fun sameEndpoint(a: CameraServiceInfo?, b: CameraServiceInfo): Boolean {
        if (a == null) return false
        return a.host.hostAddress == b.host.hostAddress && a.port == b.port
    }
}