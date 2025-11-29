package com.hci.handednessdetector

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class ImuCollector(
    private val context: Context,
    private val interpreter: HandednessInterpreter,
    private val onResult: (label: String, confidences: FloatArray) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro  = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    //windowing 2s window @60Hz, infer every 1s
    private val windowSize = 120
    private val channels = 6
    private val ring = Array(windowSize) { FloatArray(channels) }
    private var ringPos = 0
    private var samplesInBuffer = 0

    private var samplesSinceLastInference = 0
    private val inferenceStride = 60 //1s hop (50% overlap 2s window)

    private val lastAccel = FloatArray(3)
    private val lastGyro  = FloatArray(3)
    private var lastSampleTimeNs: Long = 0L

    private var isRunning = false
    private val samplingIntervalNs = (1_000_000_000L / 60.0).roundToInt().toLong() //60Hz

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        if (isRunning) return
        isRunning = true
        ringPos = 0
        samplesInBuffer = 0
        samplesSinceLastInference = 0
        lastSampleTimeNs = 0L
        lastAccel.fill(0f); lastGyro.fill(0f)

        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, gyro,  SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        sensorManager.unregisterListener(this)
        scope.coroutineContext.cancelChildren()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRunning) return
        val nowNs = event.timestamp

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel[0] = event.values[0]
                lastAccel[1] = event.values[1]
                lastAccel[2] = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                lastGyro[0] = event.values[0]
                lastGyro[1] = event.values[1]
                lastGyro[2] = event.values[2]
            }
            else -> return
        }

        //60Hz commit when enough samples
        if (lastSampleTimeNs == 0L || (nowNs - lastSampleTimeNs) >= samplingIntervalNs) {
            lastSampleTimeNs = nowNs

            //One 6D sample of ax ay az gx gy gz
            val sample = FloatArray(channels).apply {
                this[0] = lastAccel[0]; this[1] = lastAccel[1]; this[2] = lastAccel[2]
                this[3] = lastGyro[0];  this[4] = lastGyro[1];  this[5] = lastGyro[2]
            }

            //write to ring buffer
            ring[ringPos] = sample
            ringPos = (ringPos + 1) % windowSize
            if (samplesInBuffer < windowSize) samplesInBuffer++

            samplesSinceLastInference++

            //infer if full window
            if (samplesInBuffer >= windowSize && samplesSinceLastInference >= inferenceStride) {
                samplesSinceLastInference = 0

                //snapshot of window starting at ringPos
                val window = Array(windowSize) { FloatArray(channels) }
                var idx = ringPos
                for (i in 0 until windowSize) {
                    window[i] = ring[idx]
                    idx = (idx + 1) % windowSize
                }


                scope.launch {
                    val (labelIdx, probs) = interpreter.runInference(window)
                    val labels = arrayOf("BOTH", "LEFT", "RIGHT")
                    val label = labels.getOrNull(labelIdx) ?: "UNKNOWN"
                    withContext(Dispatchers.Main) {
                        onResult(label, probs)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }
}
