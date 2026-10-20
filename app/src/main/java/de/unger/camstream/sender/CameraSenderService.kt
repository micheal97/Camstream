package de.unger.camstream.sender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import de.unger.camstream.SenderKeepAliveActivity
import de.unger.camstream.audio.AacAudioStreamer
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

    private var audioStreamer: AacAudioStreamer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()

        settingsStore = SettingsStore(this)
        val s = settingsStore.load()

        createNotificationChannel()
        startForegroundCompat(buildNotification("Idle - waiting for tablet"))

        acquireDeviceLocks()
        showKeepAliveActivity()

        streamer = CameraStreamer(
            context = this,
            width = s.width,
            height = s.height,
            fps = s.fps,
            bitrate = s.bitrateKbps * 1000
        )

        val shouldSendAudio =
            s.senderLabel.contains("left", ignoreCase = true) &&
                    s.audioServerUrl.isNotBlank()

        if (shouldSendAudio) {
            audioStreamer = AacAudioStreamer(
                serverUrl = s.audioServerUrl,
                room = s.audioRoom,
                sampleRate = s.audioSampleRate,
                bitrate = s.audioBitrateKbps * 1000
            )
        }

        controlServer = SenderControlServer(
            s.controlPort,
            object : SenderControlServer.Callback {
                @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
                override fun onStartStream(targetHost: String, targetPort: Int) {
                    streamer.start(targetHost, targetPort)
                    audioStreamer?.start()
                    updateNotification(
                        if (audioStreamer != null) {
                            "Streaming AV to $targetHost:$targetPort"
                        } else {
                            "Streaming video to $targetHost:$targetPort"
                        }
                    )
                }

                override fun onStopStream() {
                    streamer.stop()
                    audioStreamer?.stop()
                    updateNotification("Idle - waiting for tablet")
                }

                override fun onRequestKeyframe() {
                    streamer.requestSyncFrame()
                }
            }
        )
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
            nsdPublisher.unregister()
        } catch (_: Exception) {
        }
        try {
            controlServer.close()
        } catch (_: Exception) {
        }
        try {
            audioStreamer?.release()
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
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
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