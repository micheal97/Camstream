package de.unger.camstream.sender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import de.unger.camstream.SenderKeepAliveActivity
import de.unger.camstream.settings.SettingsStore

class CameraSenderService : Service() {

    companion object {
        const val ACTION_START = "camstream.sender.START"
        const val ACTION_STOP = "camstream.sender.STOP"

        private const val CHANNEL_ID = "camera_sender"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var streamer: CameraStreamer
    private lateinit var controlServer: SenderControlServer
    private lateinit var nsdPublisher: CameraNsdPublisher
    private lateinit var settingsStore: SettingsStore

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var lastTargetHost: String? = null

    @Volatile
    private var lastTargetPort: Int? = null

    private val unlockReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.CAMERA)
        override fun onReceive(context: Context?, intent: Intent?) {
            val host = lastTargetHost ?: return
            val port = lastTargetPort ?: return

            try {
                showKeepAliveActivity()
            } catch (_: Exception) {
            }

            try {
                streamer.start(host, port)
                updateNotification("Streaming to $host:$port")
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        settingsStore = SettingsStore(this)
        val s = settingsStore.load()

        createNotificationChannel()
        startForegroundCompat(buildNotification("Idle - waiting for tablet"))

        acquireDeviceLocks()
        registerUnlockReceiver()
        showKeepAliveActivity()

        streamer = CameraStreamer(
            context = this,
            width = s.width,
            height = s.height,
            fps = s.fps,
            bitrate = s.bitrateKbps * 1000
        )

        controlServer = SenderControlServer(s.controlPort, object : SenderControlServer.Callback {
            @RequiresPermission(Manifest.permission.CAMERA)
            override fun onStartStream(targetHost: String, targetPort: Int) {
                lastTargetHost = targetHost
                lastTargetPort = targetPort

                showKeepAliveActivity()

                streamer.start(targetHost, targetPort)
                updateNotification("Streaming to $targetHost:$targetPort")
            }

            override fun onStopStream() {
                lastTargetHost = null
                lastTargetPort = null

                streamer.stop()
                updateNotification("Idle - waiting for tablet")
            }

            override fun onRequestKeyframe() {
                streamer.requestSyncFrame()
            }
        })
        controlServer.start()

        val safeLabel = s.senderLabel
            .ifBlank { Build.MODEL ?: "android-camera" }
            .replace(' ', '-')

        nsdPublisher = CameraNsdPublisher(
            context = this,
            serviceName = "cam-$safeLabel",
            port = s.controlPort
        )
        nsdPublisher.register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                showKeepAliveActivity()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(unlockReceiver)
        } catch (_: Exception) {
        }
        try {
            nsdPublisher.unregister()
        } catch (_: Exception) {
        }
        try {
            controlServer.close()
        } catch (_: Exception) {
        }
        try {
            streamer.release()
        } catch (_: Exception) {
        }
        releaseDeviceLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showKeepAliveActivity() {
        val intent = Intent(this, SenderKeepAliveActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        startActivity(intent)
    }

    private fun acquireDeviceLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "camstream:sender-cpu"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        val wm = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wm.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "camstream:sender-wifi"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseDeviceLocks() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null

        try {
            wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun registerUnlockReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(unlockReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera sender")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startForegroundCompat(notification: Notification) {
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Camera sender",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }
}