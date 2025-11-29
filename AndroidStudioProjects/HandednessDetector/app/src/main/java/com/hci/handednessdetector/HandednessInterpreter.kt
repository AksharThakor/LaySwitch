package com.hci.handednessdetector

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class HandednessInterpreter(
    private val context: Context,
    private val modelAssetName: String = "imu_cnn_model.tflite",
    private val metadataAssetName: String = "metadata.json"
) {

    private val interpreter: Interpreter
    private val labels: List<String>

    init {
        //load model
        val buffer = loadModelFile(context, modelAssetName)
        Log.d("IMU", "Model file loaded successfully")
        interpreter = Interpreter(buffer, Interpreter.Options())
        Log.d("IMU", "TFLite interpreter initialized")

        //load metadata for label order
        labels = loadLabelsFromMetadata()
        Log.d("IMU", "Loaded label order = $labels")
    }

    private fun loadModelFile(context: Context, assetName: String): ByteBuffer {
        val afd = context.assets.openFd(assetName)
        val inputStream = FileInputStream(afd.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = afd.startOffset
        val length = afd.declaredLength
        val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, length)
        inputStream.close()
        afd.close()
        return buffer
    }

    private fun loadLabelsFromMetadata(): List<String> {
        return try {
            val jsonStr = context.assets.open(metadataAssetName)
                .bufferedReader().use { it.readText() }
            val json = JSONObject(jsonStr)
            val arr = json.getJSONArray("label_classes")
            List(arr.length()) { idx -> arr.getString(idx) }
        } catch (e: Exception) {
            Log.e("IMU", "Failed to load metadata.json label order → using fallback", e)
            listOf("BOTH", "LEFT", "RIGHT") // fallback
        }
    }

    fun runInference(window: Array<FloatArray>): Pair<Int, FloatArray> {

        //according to the expected format of (1,120,6)
        val modelInput = Array(1) { Array(120) { FloatArray(6) } }

        for (i in 0 until 120) {
            for (j in 0 until 6) {
                modelInput[0][i][j] = window[i][j]
            }
        }

        val output = Array(1) { FloatArray(labels.size) }

        Log.d("IMU", "Running inference...")
        interpreter.run(modelInput, output)
        Log.d("IMU", "Model output = ${output[0].joinToString()}")

        val probs = output[0]


        var bestIdx = 0
        var bestVal = probs[0]
        for (i in 1 until probs.size) {
            if (probs[i] > bestVal) {
                bestVal = probs[i]
                bestIdx = i
            }
        }

        return Pair(bestIdx, probs)
    }

    fun getLabel(index: Int): String {
        return labels.getOrNull(index) ?: "UNKNOWN"
    }

    fun close() {
        interpreter.close()
    }
}
