package com.example.furnitureapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max

class RoomClassifier(private val context: Context) : Closeable {

    companion object {
        private const val TAG = "RoomClassifier"

        // Must match your assets exactly
        private const val MODEL_FILE = "room_classifier.tflite"
        private const val LABELS_FILE = "room_labels.json"

        private const val DEFAULT_INPUT_SIZE = 224
        private const val NUM_CHANNELS = 3
    }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var isQuantized: Boolean = false   // true if model input is UINT8
    private var inputSize: Int = DEFAULT_INPUT_SIZE

    val isReady: Boolean
        get() = interpreter != null && labels.isNotEmpty()

    init {
        try {
            val model = loadModelFile(MODEL_FILE)
            interpreter = Interpreter(model)

            // Read model input info
            val inputTensor = interpreter!!.getInputTensor(0)
            val inputShape = inputTensor.shape() // typically [1, H, W, 3] or [1, 3, H, W]
            val inputType = inputTensor.dataType()
            isQuantized = inputType == DataType.UINT8

            // Try to infer input size from shape
            inputSize = inferInputSize(inputShape) ?: DEFAULT_INPUT_SIZE

            Log.d(TAG, "Model input shape=${inputShape.contentToString()}, type=$inputType, inputSize=$inputSize")

            labels = loadLabelsJson(LABELS_FILE)
            Log.d(TAG, "Labels loaded: ${labels.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RoomClassifier. Check assets/$MODEL_FILE and assets/$LABELS_FILE", e)
            interpreter = null
            labels = emptyList()
        }
    }

    /**
     * Returns Triple(label, confidence, scoresFloatArray).
     * confidence is softmax(score) for the best class when model is FLOAT.
     * For quantized models, confidence is still computed after softmax over dequantized values (approx).
     */
    fun classifyWithConfidence(bitmap: Bitmap): Triple<String, Float, FloatArray> {
        val tflite = interpreter
        if (tflite == null || labels.isEmpty()) {
            Log.w(TAG, "Interpreter or labels not initialized. isReady=$isReady")
            return Triple("unknown", 0f, floatArrayOf())
        }

        val numLabels = labels.size
        val outputArray = Array(1) { FloatArray(numLabels) }

        return try {
            val inputBuffer = if (isQuantized) preprocessUint8(bitmap) else preprocessFloat(bitmap)

            tflite.run(inputBuffer, outputArray)

            val rawScores = outputArray[0]
            val probs = softmax(rawScores)

            // Argmax
            var maxIdx = 0
            var maxProb = probs[0]
            for (i in 1 until probs.size) {
                if (probs[i] > maxProb) {
                    maxProb = probs[i]
                    maxIdx = i
                }
            }

            val bestLabel = if (maxIdx in labels.indices) labels[maxIdx] else "unknown"
            Log.d(TAG, "Best label=$bestLabel, conf=$maxProb (index=$maxIdx)")

            Triple(bestLabel, maxProb, probs)
        } catch (e: Exception) {
            Log.e(TAG, "Error during classification", e)
            Triple("unknown", 0f, floatArrayOf())
        }
    }

    // ---------- Preprocess ----------

    /** FLOAT model: normalize to [-1, 1] */
    private fun preprocessFloat(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputCount = inputSize * inputSize * NUM_CHANNELS
        val buffer = ByteBuffer.allocateDirect(4 * inputCount).order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        resized.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        var pixelIndex = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = intValues[pixelIndex++]
                val r = (((pixel shr 16) and 0xFF) - 127.5f) / 127.5f
                val g = (((pixel shr 8) and 0xFF) - 127.5f) / 127.5f
                val b = ((pixel and 0xFF) - 127.5f) / 127.5f
                buffer.putFloat(r)
                buffer.putFloat(g)
                buffer.putFloat(b)
            }
        }

        buffer.rewind()
        return buffer
    }

    /** UINT8 model: raw [0..255] */
    private fun preprocessUint8(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputCount = inputSize * inputSize * NUM_CHANNELS
        val buffer = ByteBuffer.allocateDirect(inputCount).order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        resized.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        var pixelIndex = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = intValues[pixelIndex++]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                buffer.put(r.toByte())
                buffer.put(g.toByte())
                buffer.put(b.toByte())
            }
        }

        buffer.rewind()
        return buffer
    }

    // ---------- Helpers ----------

    private fun inferInputSize(shape: IntArray): Int? {
        // common: [1, H, W, 3]
        if (shape.size == 4 && shape[3] == 3 && shape[1] == shape[2] && shape[1] > 0) return shape[1]
        // some models: [1, 3, H, W]
        if (shape.size == 4 && shape[1] == 3 && shape[2] == shape[3] && shape[2] > 0) return shape[2]
        return null
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val out = FloatArray(logits.size)
        var maxLogit = logits[0]
        for (i in 1 until logits.size) maxLogit = max(maxLogit, logits[i])

        var sum = 0.0
        for (i in logits.indices) {
            val e = exp((logits[i] - maxLogit).toDouble())
            out[i] = e.toFloat()
            sum += e
        }

        if (sum <= 0) return out
        for (i in out.indices) out[i] = (out[i] / sum.toFloat())
        return out
    }

    private fun loadModelFile(fileName: String): MappedByteBuffer {
        val fd = context.assets.openFd(fileName)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    /**
     * Supports:
     *  ["bedroom", "office", ...]
     *  { "labels": ["bedroom", "office"] }
     *  { "0": "bedroom", "1": "office", ... }
     */
    private fun loadLabelsJson(fileName: String): List<String> {
        val jsonText = context.assets.open(fileName).bufferedReader().use { it.readText() }

        return try {
            val trimmed = jsonText.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).map { i -> arr.getString(i) }
            } else {
                val obj = JSONObject(trimmed)
                if (obj.has("labels")) {
                    val arr = obj.getJSONArray("labels")
                    (0 until arr.length()).map { i -> arr.getString(i) }
                } else {
                    val labelsList = mutableListOf<String>()
                    var index = 0
                    while (obj.has(index.toString())) {
                        labelsList.add(obj.getString(index.toString()))
                        index++
                    }
                    labelsList
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing labels JSON", e)
            emptyList()
        }
    }

    override fun close() {
        try {
            interpreter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing interpreter", e)
        }
    }
}
