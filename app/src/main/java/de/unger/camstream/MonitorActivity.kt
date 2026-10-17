package de.unger.camstream

import android.Manifest
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import de.unger.camstream.control.SenderControlClient
import de.unger.camstream.discovery.CameraNsdDiscovery
import de.unger.camstream.discovery.CameraServiceInfo
import de.unger.camstream.net.AppNetworkWatcher
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

    private var leftSender: CameraServiceInfo? = null
    private var rightSender: CameraServiceInfo? = null

    private var leftControl: SenderControlClient? = null
    private var rightControl: SenderControlClient? = null

    private var leftSurfaceReady = false
    private var rightSurfaceReady = false

    private lateinit var discovery: CameraNsdDiscovery
    private lateinit var settingsStore: SettingsStore
    private lateinit var networkWatcher: AppNetworkWatcher

    private var tabletLeftPort: Int = 5004
    private var tabletRightPort: Int = 5006
    private var softStallMs: Long = 3000L
    private var hardStallMs: Long = 8000L

    private var monitorStarted = false

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)

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

        discovery = CameraNsdDiscovery(this, object : CameraNsdDiscovery.Callback {
            @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
            override fun onCameraFound(info: CameraServiceInfo) {
                runOnUiThread {
                    val name = info.name.lowercase()

                    when {
                        "left" in name && leftSender == null -> {
                            leftSender = info
                            leftControl = SenderControlClient(info.host, info.port)
                            maybeStartLeft()
                        }

                        "right" in name && rightSender == null -> {
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

        networkWatcher = AppNetworkWatcher(this, object : AppNetworkWatcher.Callback {
            override fun onNetworkLost() {
                runOnUiThread {
                    handleNetworkLost()
                }
            }

            @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
            override fun onNetworkChanged() {
                runOnUiThread {
                    handleNetworkChanged()
                }
            }
        })
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    override fun onStart() {
        super.onStart()
        monitorStarted = true
        networkWatcher.start()
        restartDiscoveryAndReconnect()
    }

    override fun onStop() {
        monitorStarted = false
        networkWatcher.stop()
        stopMonitoringSession(clearSenders = false)
        super.onStop()
    }

    private fun handleNetworkLost() {
        stopMonitoringSession(clearSenders = true)
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun handleNetworkChanged() {
        if (!monitorStarted) return
        restartDiscoveryAndReconnect()
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun restartDiscoveryAndReconnect() {
        stopMonitoringSession(clearSenders = true)
        discovery.start()

        // If discovery callback already knows a sender quickly, these will start.
        maybeStartLeft()
        maybeStartRight()
    }

    private fun stopMonitoringSession(clearSenders: Boolean) {
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

        if (clearSenders) {
            leftSender = null
            rightSender = null
            leftControl = null
            rightControl = null
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun maybeStartLeft() {
        if (!monitorStarted) return
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
        if (!monitorStarted) return
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