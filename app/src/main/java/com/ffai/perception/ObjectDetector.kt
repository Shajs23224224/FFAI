package com.ffai.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.TensorProcessor
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import timber.log.Timber
import java.io.File
import java.nio.MappedByteBuffer

/**
 * Object Detector - YOLO-based detection for game elements
 * Optimized for A21S: INT8 quantized, NNAPI delegate, small input size
 */
class ObjectDetector(
    private val context: Context,
    private val inputSize: Int = 320
) {
    private var interpreter: Interpreter? = null
    private val labels = mutableListOf<String>()
    
    // Image preprocessing
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(0f, 255f))  // Normalize to 0-1
        .build()
    
    // Output processing
    private val outputProcessor = TensorProcessor.Builder()
        .add(NormalizeOp(0f, 1f))
        .build()
    
    companion object {
        private const val MODEL_FILE = "object_detector.tflite"
        private const val LABELS_FILE = "labels.txt"
        private const val NUM_DETECTIONS = 10
        private const val CONFIDENCE_THRESHOLD = 0.3f
        private const val IOU_THRESHOLD = 0.5f
    }
    
    init {
        loadModel()
        loadLabels()
    }
    
    private fun loadModel() {
        try {
            val modelFile = File(context.filesDir, "models/$MODEL_FILE")
            
            if (!modelFile.exists()) {
                // Copy from assets
                context.assets.open("models/$MODEL_FILE").use { input ->
                    modelFile.parentFile?.mkdirs()
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            val modelBuffer = loadModelFile(modelFile)
            
            val options = Interpreter.Options().apply {
                // A21S optimizations
                setNumThreads(2)  // Limited cores
                
                // Try NNAPI first (Exynos 850 has some support)
                try {
                    val nnApiOptions = NnApiDelegate.Options().apply {
                        setExecutionPreference(
                            NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED
                        )
                    }
                    addDelegate(NnApiDelegate(nnApiOptions))
                } catch (e: Exception) {
                    Timber.w("NNAPI not available, trying GPU")
                    try {
                        addDelegate(GpuDelegate())
                    } catch (e: Exception) {
                        Timber.w("GPU delegate not available, using CPU")
                    }
                }
                
                useXNNPACK = true
            }
            
            interpreter = Interpreter(modelBuffer, options)
            Timber.i("Object detector loaded: ${inputSize}x$inputSize")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to load object detector")
        }
    }
    
    private fun loadLabels() {
        try {
            context.assets.open("models/$LABELS_FILE").bufferedReader().useLines { lines ->
                lines.forEach { labels.add(it) }
            }
        } catch (e: Exception) {
            // Use default labels
            labels.addAll(listOf(
                "enemy", "ally", "loot", "cover", "vehicle", "zone", "weapon"
            ))
        }
    }
    
    fun detect(bitmap: Bitmap): List<ScreenAnalyzer.Detection> {
        val interpreter = this.interpreter ?: return emptyList()
        
        return try {
            // Preprocess image
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val processedImage = imageProcessor.process(tensorImage)
            
            // Run inference
            val inputBuffer = processedImage.buffer
            
            // Output arrays: [num_detections, 6] where 6 = [x, y, w, h, confidence, class]
            val outputLocations = Array(1) { Array(NUM_DETECTIONS) { FloatArray(4) } }
            val outputClasses = Array(1) { FloatArray(NUM_DETECTIONS) }
            val outputScores = Array(1) { FloatArray(NUM_DETECTIONS) }
            val numDetections = FloatArray(1)
            
            val outputs = mapOf(
                0 to outputLocations,
                1 to outputClasses,
                2 to outputScores,
                3 to numDetections
            )
            
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
            
            // Parse results
            val detections = mutableListOf<ScreenAnalyzer.Detection>()
            val numDetection = numDetections[0].toInt()
            
            for (i in 0 until minOf(numDetection, NUM_DETECTIONS)) {
                val score = outputScores[0][i]
                if (score < CONFIDENCE_THRESHOLD) continue
                
                val location = outputLocations[0][i]
                val classId = outputClasses[0][i].toInt()
                
                detections.add(ScreenAnalyzer.Detection(
                    className = labels.getOrElse(classId) { "unknown" },
                    confidence = score,
                    x = location[0],
                    y = location[1],
                    width = location[2],
                    height = location[3]
                ))
            }
            
            // Apply NMS
            applyNMS(detections)
            
        } catch (e: Exception) {
            Timber.w(e, "Detection failed")
            emptyList()
        }
    }
    
    private fun applyNMS(
        detections: List<ScreenAnalyzer.Detection>
    ): List<ScreenAnalyzer.Detection> {
        if (detections.size <= 1) return detections
        
        // Sort by confidence
        val sorted = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<ScreenAnalyzer.Detection>()
        val suppressed = BooleanArray(sorted.size)
        
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            
            selected.add(sorted[i])
            
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                
                val iou = calculateIoU(sorted[i], sorted[j])
                if (iou > IOU_THRESHOLD) {
                    suppressed[j] = true
                }
            }
        }
        
        return selected
    }
    
    private fun calculateIoU(
        a: ScreenAnalyzer.Detection,
        b: ScreenAnalyzer.Detection
    ): Float {
        val boxA = RectF(a.x, a.y, a.x + a.width, a.y + a.height)
        val boxB = RectF(b.x, b.y, b.x + b.width, b.y + b.height)
        
        val intersectionLeft = maxOf(boxA.left, boxB.left)
        val intersectionTop = maxOf(boxA.top, boxB.top)
        val intersectionRight = minOf(boxA.right, boxB.right)
        val intersectionBottom = minOf(boxA.bottom, boxB.bottom)
        
        if (intersectionLeft >= intersectionRight || intersectionTop >= intersectionBottom) {
            return 0f
        }
        
        val intersectionArea = (intersectionRight - intersectionLeft) * 
                              (intersectionBottom - intersectionTop)
        val boxAArea = boxA.width() * boxA.height()
        val boxBArea = boxB.width() * boxB.height()
        
        return intersectionArea / (boxAArea + boxBArea - intersectionArea)
    }
    
    private fun loadModelFile(modelFile: File): MappedByteBuffer {
        return java.io.FileInputStream(modelFile).use { inputStream ->
            inputStream.channel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY,
                0,
                modelFile.length()
            )
        }
    }
    
    fun close() {
        interpreter?.close()
        Timber.d("Object detector closed")
    }
}
