package de.unger.camstream.settings

import android.os.Build

data class AppSettings(
    val senderLabel: String = Build.MODEL ?: "android-camera",
    val controlPort: Int = 9000,

    val width: Int = 640,
    val height: Int = 480,
    val fps: Int = 2,
    val bitrateKbps: Int = 300,

    val leftUdpPort: Int = 5004,
    val rightUdpPort: Int = 5006,

    val softStallMs: Long = 3000L,
    val hardStallMs: Long = 8000L,

    // Audio-over-server
    val audioServerUrl: String = "ws://82.165.165.63:8080",
    val audioRoom: String = "left-audio",
    val audioSampleRate: Int = 16000,
    val audioBitrateKbps: Int = 32,
)