package de.unger.camstream

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import de.unger.camstream.sender.CameraSenderService

class SenderLauncherActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            startSenderService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val start = Button(this).apply {
            text = "Start sender service"
            setOnClickListener { ensurePermissionsAndStart() }
        }

        val stop = Button(this).apply {
            text = "Stop sender service"
            setOnClickListener {
                val intent = Intent(this@SenderLauncherActivity, CameraSenderService::class.java)
                    .setAction(CameraSenderService.ACTION_STOP)
                stopService(intent)
            }
        }
        val settings = Button(this).apply {
            text = "Settings"
            setOnClickListener {
                startActivity(Intent(this@SenderLauncherActivity, SettingsActivity::class.java))
            }
        }

        val monitor = Button(this).apply {
            text = "Monitor"
            setOnClickListener {
                startActivity(Intent(this@SenderLauncherActivity, MonitorActivity::class.java))
            }
        }

        layout.addView(settings)
        layout.addView(monitor)
        layout.addView(start)
        layout.addView(stop)
        setContentView(layout)
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.CAMERA
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        if (needed.isEmpty()) {
            startSenderService()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startSenderService() {
        val intent = Intent(this, CameraSenderService::class.java)
            .setAction(CameraSenderService.ACTION_START)

        ContextCompat.startForegroundService(this, intent)
    }
}