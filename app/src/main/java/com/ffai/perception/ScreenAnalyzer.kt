package com.ffai.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import com.ffai.config.DeviceProfile
import com.ffai.config.PerformanceLimits
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Screen Analyzer - Captures and analyzes game screen
 * Optimized for A21S: Low resolution, frame skipping, efficient processing
 */
class ScreenAnalyzer(
    private val context: Context,
    private val analysisWidth: Int = 240,
    private val targetFps: Int = 10
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Analysis state
    private val isRunning = AtomicBoolean(false)
    private val frameCount = AtomicLong(0)
    private val lastFrameTime = AtomicLong(0)
    private val frameSkip = AtomicLong(0)
    
    // Results
    private val _lastResult = MutableStateFlow<PerceptionResult?>(null)
    val lastResult: StateFlow<PerceptionResult?> = _lastResult
    
    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing
    
    // Frame queue for processing
    private val frameQueue = ConcurrentLinkedQueue<Bitmap>()
    private val maxQueueSize = 2  // Limit queue to prevent memory issues on A21S
    
    // ML models (loaded lazily)
    private var objectDetector: ObjectDetector? = null
    private var ocrEngine: OcrEngine? = null
    
    // Screen capture
    private var imageReader: ImageReader? = null
    private var mediaProjection: MediaProjection? = null
    
    // Analysis interval (ms)
    private val analysisInterval = 1000L / targetFps
    
    init {
        initializeMLModels()
        startAnalysisLoop()
    }
    
    private fun initializeMLModels() {
        scope.launch {
            try {
                objectDetector = ObjectDetector(context, analysisWidth)
                ocrEngine = OcrEngine(context)
                Timber.i("ML models initialized")
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize ML models")
            }
        }
    }
    
    fun startCapture(projection: MediaProjection, screenWidth: Int, screenHeight: Int) {
        mediaProjection = projection
        
        // Calculate analysis dimensions maintaining aspect ratio
        val scaleFactor = analysisWidth.toFloat() / screenWidth
        val analysisHeight = (screenHeight * scaleFactor).toInt()
        
        imageReader = ImageReader.newInstance(
            analysisWidth,
            analysisHeight,
            android.graphics.PixelFormat.RGBA_8888,
            2  // Max images - prevent memory buildup
        )
        
        imageReader?.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage()
                image?.let { processImage(it) }
                image?.close()
            } catch (e: Exception) {
                Timber.w(e, "Image processing error")
            }
        }, mainHandler)
        
        projection.createVirtualDisplay(
            "FFAI_ScreenCapture",
            analysisWidth,
            analysisHeight,
            context.resources.displayMetrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )
        
        isRunning.set(true)
        Timber.i("Screen capture started: ${analysisWidth}x${analysisHeight}")
    }
    
    private fun processImage(image: Image) {
        // Skip frames if processing is behind
        if (_isAnalyzing.value) {
            frameSkip.incrementAndGet()
            return
        }
        
        // Rate limiting
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameTime.get() < analysisInterval) {
            return
        }
        lastFrameTime.set(currentTime)
        
        // Convert to bitmap
        val bitmap = imageToBitmap(image) ?: return
        
        // Add to queue (drop old if full)
        if (frameQueue.size >= maxQueueSize) {
            frameQueue.poll()?.recycle()
        }
        frameQueue.offer(bitmap)
    }
    
    private fun startAnalysisLoop() {
        scope.launch {
            while (isActive) {
                try {
                    val bitmap = frameQueue.poll()
                    if (bitmap != null && !bitmap.isRecycled) {
                        _isAnalyzing.value = true
                        
                        val result = analyzeFrame(bitmap)
                        _lastResult.value = result
                        
                        frameCount.incrementAndGet()
                        _isAnalyzing.value = false
                        
                        bitmap.recycle()
                    }
                    delay(analysisInterval)
                } catch (e: Exception) {
                    Timber.e(e, "Analysis loop error")
                    _isAnalyzing.value = false
                    delay(1000)
                }
            }
        }
    }
    
    private suspend fun analyzeFrame(bitmap: Bitmap): PerceptionResult {
        return withContext(Dispatchers.Default) {
            val startTime = System.nanoTime()
            
            // Parallel analysis (limited for A21S)
            val objectsDeferred = async { detectObjects(bitmap) }
            val textDeferred = async { recognizeText(bitmap) }
            
            val detectedObjects = objectsDeferred.await()
            val recognizedText = textDeferred.await()
            
            // Parse results
            val enemies = detectedObjects.filter { it.className == "enemy" }
                .map { Enemy.fromDetection(it) }
            
            val allies = detectedObjects.filter { it.className == "ally" }
                .map { Ally.fromDetection(it) }
            
            val loot = detectedObjects.filter { it.className == "loot" }
                .map { LootItem.fromDetection(it) }
            
            val cover = detectedObjects.filter { it.className == "cover" }
                .map { Cover.fromDetection(it) }
            
            // Parse UI elements from OCR
            val playerState = parsePlayerState(recognizedText)
            val safeZone = parseSafeZone(recognizedText)
            
            val inferenceTime = (System.nanoTime() - startTime) / 1_000_000 // ms
            
            PerceptionResult(
                timestamp = System.currentTimeMillis(),
                detectedEnemies = enemies,
                detectedAllies = allies,
                detectedLoot = loot,
                detectedCover = cover,
                playerState = playerState,
                safeZone = safeZone,
                recognizedText = recognizedText,
                inferenceTimeMs = inferenceTime,
                confidence = calculateConfidence(detectedObjects),
                frameHash = bitmap.hashCode()
            )
        }
    }
    
    private fun detectObjects(bitmap: Bitmap): List<Detection> {
        val detector = objectDetector ?: return emptyList()
        return detector.detect(bitmap)
    }
    
    private fun recognizeText(bitmap: Bitmap): List<TextRegion> {
        val ocr = ocrEngine ?: return emptyList()
        return ocr.recognize(bitmap)
    }
    
    private fun parsePlayerState(texts: List<TextRegion>): PlayerState {
        // Extract HP from text like "100/100" or health bar
        val hpText = texts.find { it.text.contains(Regex("\\d+/\\d+")) }
        val (currentHp, maxHp) = hpText?.text?.let {
            val match = Regex("(\\d+)/(\\d+)").find(it)
            match?.let { m ->
                Pair(
                    m.groupValues[1].toIntOrNull() ?: 100,
                    m.groupValues[2].toIntOrNull() ?: 100
                )
            }
        } ?: Pair(100, 100)
        
        // Extract ammo
        val ammoText = texts.find { it.text.contains(Regex("\\d+/\\d+")) && it.region.top > 0.8f }
        val ammo = ammoText?.text?.let {
            Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
        } ?: 0
        
        return PlayerState(
            health = currentHp,
            maxHealth = maxHp,
            armor = 0,  // Parse from UI
            ammoCount = ammo,
            currentWeapon = "unknown",  // Parse from UI
            inCover = false,  // Detect from visual analysis
            isAiming = false,
            isMoving = false
        )
    }
    
    private fun parseSafeZone(texts: List<TextRegion>): SafeZone {
        // Parse safe zone timer and indicator
        val timerText = texts.find { it.text.contains(Regex("\\d+:\\d+")) }
        val seconds = timerText?.text?.let {
            val parts = it.split(":")
            (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
        } ?: 0
        
        return SafeZone(
            timeToShrink = seconds,
            isInside = true,  // Parse from minimap
            distanceToEdge = 0f
        )
    }
    
    private fun calculateConfidence(detections: List<Detection>): Float {
        if (detections.isEmpty()) return 0.5f
        return detections.map { it.confidence }.average().toFloat()
    }
    
    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } catch (e: Exception) {
            Timber.w(e, "Image conversion failed")
            null
        }
    }
    
    fun stop() {
        isRunning.set(false)
        mediaProjection?.stop()
        imageReader?.close()
        scope.cancel()
        Timber.i("Screen analyzer stopped")
    }
    
    fun getStats(): AnalyzerStats {
        return AnalyzerStats(
            totalFrames = frameCount.get(),
            skippedFrames = frameSkip.get(),
            currentFps = if (frameCount.get() > 0) {
                (frameCount.get() * 1000.0 / (System.currentTimeMillis() - 
                    (lastResult.value?.timestamp ?: System.currentTimeMillis()))).toFloat()
            } else 0f,
            queueSize = frameQueue.size,
            lastInferenceTime = lastResult.value?.inferenceTimeMs ?: 0
        )
    }
    
    // Data classes
    data class PerceptionResult(
        val timestamp: Long,
        val detectedEnemies: List<Enemy>,
        val detectedAllies: List<Ally>,
        val detectedLoot: List<LootItem>,
        val detectedCover: List<Cover>,
        val playerState: PlayerState,
        val safeZone: SafeZone,
        val recognizedText: List<TextRegion>,
        val inferenceTimeMs: Long,
        val confidence: Float,
        val frameHash: Int
    )
    
    data class Enemy(
        val id: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val health: Float,
        val distance: Float,
        val weaponType: String?,
        val isFiring: Boolean,
        val isMovingTowards: Boolean,
        val threatLevel: Float,
        val confidence: Float
    ) {
        companion object {
            fun fromDetection(det: Detection): Enemy {
                return Enemy(
                    id = "${det.className}_${det.hashCode()}",
                    x = det.x,
                    y = det.y,
                    width = det.width,
                    height = det.height,
                    health = 100f,  // Estimate from visual
                    distance = estimateDistance(det.height),
                    weaponType = null,  // Parse from visual
                    isFiring = false,  // Detect from muzzle flash
                    isMovingTowards = false,  // Track from frames
                    threatLevel = calculateThreat(det),
                    confidence = det.confidence
                )
            }
            
            private fun estimateDistance(heightPx: Float): Float {
                // Rough estimate based on enemy height in pixels
                return 1000f / heightPx
            }
            
            private fun calculateThreat(det: Detection): Float {
                // Closer and larger = more threat
                val distance = estimateDistance(det.height)
                return (1f - (distance / 200f).coerceIn(0f, 1f)) * det.confidence
            }
        }
    }
    
    data class Ally(
        val id: String,
        val x: Float,
        val y: Float,
        val distance: Float
    ) {
        companion object {
            fun fromDetection(det: Detection): Ally {
                return Ally(
                    id = "${det.className}_${det.hashCode()}",
                    x = det.x,
                    y = det.y,
                    distance = 100f  // Estimate
                )
            }
        }
    }
    
    data class LootItem(
        val type: String,
        val x: Float,
        val y: Float,
        val value: Float,
        val priority: Float
    ) {
        companion object {
            fun fromDetection(det: Detection): LootItem {
                return LootItem(
                    type = det.className,
                    x = det.x,
                    y = det.y,
                    value = estimateValue(det.className),
                    priority = 0.5f
                )
            }
            
            private fun estimateValue(type: String): Float {
                return when (type) {
                    "weapon_legendary" -> 1.0f
                    "weapon_epic" -> 0.8f
                    "armor_level3" -> 0.9f
                    "health_kit" -> 0.7f
                    "ammo" -> 0.3f
                    else -> 0.5f
                }
            }
        }
    }
    
    data class Cover(
        val x: Float,
        val y: Float,
        val type: String,
        val protectionLevel: Float
    ) {
        companion object {
            fun fromDetection(det: Detection): Cover {
                return Cover(
                    x = det.x,
                    y = det.y,
                    type = det.className,
                    protectionLevel = det.confidence
                )
            }
        }
    }
    
    data class PlayerState(
        val health: Int,
        val maxHealth: Int,
        val armor: Int,
        val ammoCount: Int,
        val currentWeapon: String,
        val inCover: Boolean,
        val isAiming: Boolean,
        val isMoving: Boolean
    )
    
    data class SafeZone(
        val timeToShrink: Int,  // seconds
        val isInside: Boolean,
        val distanceToEdge: Float
    )
    
    data class TextRegion(
        val text: String,
        val region: Rect,
        val confidence: Float
    )
    
    data class Detection(
        val className: String,
        val confidence: Float,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )
    
    data class AnalyzerStats(
        val totalFrames: Long,
        val skippedFrames: Long,
        val currentFps: Float,
        val queueSize: Int,
        val lastInferenceTime: Long
    )
}
