package de.unger.camstreampackage

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import de.unger.camstream.R
import de.unger.camstream.settings.AppSettings
import de.unger.camstream.settings.SettingsStore

class SettingsActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore

    private lateinit var etSenderLabel: EditText
    private lateinit var etControlPort: EditText
    private lateinit var etWidth: EditText
    private lateinit var etHeight: EditText
    private lateinit var etFps: EditText
    private lateinit var etBitrateKbps: EditText
    private lateinit var etLeftUdpPort: EditText
    private lateinit var etRightUdpPort: EditText
    private lateinit var etSoftStallMs: EditText
    private lateinit var etHardStallMs: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        store = SettingsStore(this)

        etSenderLabel = findViewById(R.id.etSenderLabel)
        etControlPort = findViewById(R.id.etControlPort)
        etWidth = findViewById(R.id.etWidth)
        etHeight = findViewById(R.id.etHeight)
        etFps = findViewById(R.id.etFps)
        etBitrateKbps = findViewById(R.id.etBitrateKbps)
        etLeftUdpPort = findViewById(R.id.etLeftUdpPort)
        etRightUdpPort = findViewById(R.id.etRightUdpPort)
        etSoftStallMs = findViewById(R.id.etSoftStallMs)
        etHardStallMs = findViewById(R.id.etHardStallMs)

        findViewById<Button>(R.id.btnDefaults).setOnClickListener {
            bind(AppSettings())
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            save()
        }

        bind(store.load())
    }

    private fun bind(s: AppSettings) {
        etSenderLabel.setText(s.senderLabel)
        etControlPort.setText(s.controlPort.toString())
        etWidth.setText(s.width.toString())
        etHeight.setText(s.height.toString())
        etFps.setText(s.fps.toString())
        etBitrateKbps.setText(s.bitrateKbps.toString())
        etLeftUdpPort.setText(s.leftUdpPort.toString())
        etRightUdpPort.setText(s.rightUdpPort.toString())
        etSoftStallMs.setText(s.softStallMs.toString())
        etHardStallMs.setText(s.hardStallMs.toString())
    }

    private fun save() {
        val current = store.load()

        val settings = AppSettings(
            senderLabel = etSenderLabel.text.toString().trim().ifBlank { current.senderLabel },

            controlPort = parseInt(etControlPort, current.controlPort, 1024, 65535),
            width = parseInt(etWidth, current.width, 160, 1920),
            height = parseInt(etHeight, current.height, 120, 1080),
            fps = parseInt(etFps, current.fps, 1, 30),
            bitrateKbps = parseInt(etBitrateKbps, current.bitrateKbps, 50, 5000),

            leftUdpPort = parseInt(etLeftUdpPort, current.leftUdpPort, 1024, 65535),
            rightUdpPort = parseInt(etRightUdpPort, current.rightUdpPort, 1024, 65535),

            softStallMs = parseLong(etSoftStallMs, current.softStallMs, 500L, 60_000L),
            hardStallMs = parseLong(etHardStallMs, current.hardStallMs, 1000L, 120_000L),
        )

        store.save(settings)
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun parseInt(
        editText: EditText,
        fallback: Int,
        min: Int,
        max: Int
    ): Int {
        val raw = editText.text.toString().trim().toIntOrNull() ?: fallback
        return raw.coerceIn(min, max)
    }

    private fun parseLong(
        editText: EditText,
        fallback: Long,
        min: Long,
        max: Long
    ): Long {
        val raw = editText.text.toString().trim().toLongOrNull() ?: fallback
        return raw.coerceIn(min, max)
    }
}