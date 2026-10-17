package de.unger.camstream.sender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
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
    private lateinit var networkWatcher: de.unger.camstream.net.AppNetworkWatcher
    private lateinit var publishedServiceName: String
    override fun onCreate() {
        super.onCreate()

        settingsStore = SettingsStore(this)
        val s = settingsStore.load()

        createNotificationChannel()
        startForegroundCompat(buildNotification("Idle - waiting for tablet"))

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
                streamer.start(targetHost, targetPort)
                updateNotification("Streaming to $targetHost:$targetPort")
            }

            override fun onStopStream() {
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

        publishedServiceName = "cam-$safeLabel"

        registerPublisher()

        networkWatcher = de.unger.camstream.net.AppNetworkWatcher(
            this,
            object : de.unger.camstream.net.AppNetworkWatcher.Callback {
                override fun onNetworkLost() {
                    try {
                        streamer.stop()
                    } catch (_: Exception) {
                    }
                    try {
                        nsdPublisher.unregister()
                    } catch (_: Exception) {
                    }
                    updateNotification("Network lost - waiting...")
                }

                override fun onNetworkChanged() {
                    reRegisterPublisher()
                    updateNotification("Idle - waiting for tablet")
                }
            })
        networkWatcher.start()
    }

    private fun registerPublisher() {
        val s = settingsStore.load()

        nsdPublisher = CameraNsdPublisher(
            context = this,
            serviceName = publishedServiceName,
            port = s.controlPort
        )
        nsdPublisher.register()
    }

    private fun reRegisterPublisher() {
        try {
            nsdPublisher.unregister()
        } catch (_: Exception) {
        }
        registerPublisher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {}
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            networkWatcher.stop()
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
