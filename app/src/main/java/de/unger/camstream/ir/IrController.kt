package de.unger.camstream.ir

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class IrController(
    context: Context,
    baseUrl: String,
    private val luxThreshold: Float = 10f,
    private val hysteresis: Float = 3f
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = IrHttpClient(baseUrl)

    @Volatile
    private var mode: IrMode = IrMode.OFF

    @Volatile
    private var irOn = false

    fun setMode(newMode: IrMode) {
        mode = newMode
        when (newMode) {
            IrMode.OFF -> {
                stopAuto()
                pushOff()
            }

            IrMode.ON -> {
                stopAuto()
                pushOn()
            }

            IrMode.AUTO -> {
                startAuto()
            }
        }
    }

    fun getMode(): IrMode = mode

    fun release() {
        stopAuto()
        scope.cancel()
        http.close()
    }

    private fun startAuto() {
        if (lightSensor == null) {
            pushOn()
            return
        }
        sensorManager.unregisterListener(this)
        sensorManager.registerListener(
            this,
            lightSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
    }

    private fun stopAuto() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (mode != IrMode.AUTO) return

        val lux = event.values.firstOrNull() ?: return

        if (!irOn && lux < luxThreshold) {
            pushOn()
        } else if (irOn && lux > luxThreshold + hysteresis) {
            pushOff()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun pushOn() {
        if (irOn) return
        irOn = true
        scope.launch {
            runCatching { http.setOn() }
        }
    }

    private fun pushOff() {
        if (!irOn) return
        irOn = false
        scope.launch {
            runCatching { http.setOff() }
        }
    }
}
