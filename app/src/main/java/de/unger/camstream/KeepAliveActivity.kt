package de.unger.camstream

import android.app.KeyguardManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class SenderKeepAliveActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = 0.01f
        }

        setContentView(View(this).apply {
            setBackgroundColor(Color.BLACK)
        })

        val km = getSystemService(KeyguardManager::class.java)
        km?.requestDismissKeyguard(this, null)
    }
}