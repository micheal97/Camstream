package de.unger.camstream.settings

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("camstream_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        return AppSettings(
            senderLabel = prefs.getString(KEY_SENDER_LABEL, AppSettings().senderLabel)
                ?: AppSettings().senderLabel,

            controlPort = prefs.getInt(KEY_CONTROL_PORT, AppSettings().controlPort),

            width = prefs.getInt(KEY_WIDTH, AppSettings().width),
            height = prefs.getInt(KEY_HEIGHT, AppSettings().height),
            fps = prefs.getInt(KEY_FPS, AppSettings().fps),
            bitrateKbps = prefs.getInt(KEY_BITRATE_KBPS, AppSettings().bitrateKbps),

            leftUdpPort = prefs.getInt(KEY_LEFT_UDP_PORT, AppSettings().leftUdpPort),
            rightUdpPort = prefs.getInt(KEY_RIGHT_UDP_PORT, AppSettings().rightUdpPort),
            leftAudioPort = prefs.getInt(KEY_LEFT_AUDIO_PORT, AppSettings().leftAudioPort),
            rightAudioPort = prefs.getInt(KEY_RIGHT_AUDIO_PORT, AppSettings().rightAudioPort),

            softStallMs = prefs.getLong(KEY_SOFT_STALL_MS, AppSettings().softStallMs),
            hardStallMs = prefs.getLong(KEY_HARD_STALL_MS, AppSettings().hardStallMs),
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_SENDER_LABEL, settings.senderLabel)
            .putInt(KEY_CONTROL_PORT, settings.controlPort)
            .putInt(KEY_WIDTH, settings.width)
            .putInt(KEY_HEIGHT, settings.height)
            .putInt(KEY_FPS, settings.fps)
            .putInt(KEY_BITRATE_KBPS, settings.bitrateKbps)
            .putInt(KEY_LEFT_UDP_PORT, settings.leftUdpPort)
            .putInt(KEY_RIGHT_UDP_PORT, settings.rightUdpPort)
            .putInt(KEY_LEFT_AUDIO_PORT, settings.leftAudioPort)
            .putInt(KEY_RIGHT_AUDIO_PORT, settings.rightAudioPort)
            .putLong(KEY_SOFT_STALL_MS, settings.softStallMs)
            .putLong(KEY_HARD_STALL_MS, settings.hardStallMs)
            .apply()
    }

    companion object {
        private const val KEY_SENDER_LABEL = "sender_label"
        private const val KEY_CONTROL_PORT = "control_port"

        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_FPS = "fps"
        private const val KEY_BITRATE_KBPS = "bitrate_kbps"

        private const val KEY_LEFT_UDP_PORT = "left_udp_port"
        private const val KEY_RIGHT_UDP_PORT = "right_udp_port"
        private const val KEY_LEFT_AUDIO_PORT = "left_audio_port"
        private const val KEY_RIGHT_AUDIO_PORT = "right_audio_port"
        private const val KEY_SOFT_STALL_MS = "soft_stall_ms"
        private const val KEY_HARD_STALL_MS = "hard_stall_ms"
    }
}