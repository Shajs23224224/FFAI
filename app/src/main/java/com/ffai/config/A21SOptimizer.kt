package com.ffai.config

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import timber.log.Timber

/**
 * A21S Optimizer - Samsung Galaxy A21S Specific Optimizations
 * Hardware: Exynos 850 (8x Cortex-A55 @ 2.0GHz, Mali-G52)
 * Target: LIGHT tier with aggressive throttling
 */
object A21SOptimizer {
    
    // A21S Known Configurations
    private val A21S_MODELS = setOf(
        "SM-A217F", "SM-A217M", "SM-A217N",
        "SM-A217U", "SM-A217U1", "SM-A217W"
    )
    
    fun detectDeviceProfile(context: Context): DeviceProfile {
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        val apiLevel = Build.VERSION.SDK_INT
        
        // Check if this is an A21S
        val isA21S = A21S_MODELS.contains(model) || model.contains("A21", ignoreCase = true)
        
        // Get actual RAM
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalRamMB = (memoryInfo.totalMem / (1024 * 1024)).toInt()
        val totalRamGB = totalRamMB / 1024
        
        // Get screen resolution
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        Timber.d("Device Detection: $manufacturer $model")
        Timber.d("Total RAM: ${totalRamGB}GB (${totalRamMB}MB)")
        Timber.d("Screen: ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi}dpi")
        Timber.d("Android API: $apiLevel")
        
        return if (isA21S) {
            createA21SProfile(totalRamGB, metrics, apiLevel)
        } else {
            createGenericProfile(manufacturer, model, totalRamGB, metrics, apiLevel)
        }
    }
    
    private fun createA21SProfile(
        ramGB: Int,
        metrics: DisplayMetrics,
        apiLevel: Int
    ): DeviceProfile {
        // A21S uses LIGHT tier but we may adjust based on actual RAM
        val tier = when (ramGB) {
            in 0..2 -> DeviceProfile.OptimizationTier.ULTRA_LIGHT
            in 3..4 -> DeviceProfile.OptimizationTier.LIGHT
            in 5..6 -> DeviceProfile.OptimizationTier.MEDIUM
            else -> DeviceProfile.OptimizationTier.HIGH
        }
        
        return DeviceProfile(
            name = "Samsung Galaxy A21S (${ramGB}GB)",
            manufacturer = "Samsung",
            model = Build.MODEL,
            soc = DeviceProfile.SystemOnChip(
                name = "Exynos 850",
                cpuCores = 8,
                cpuArchitecture = "Cortex-A55",
                hasGpu = true,
                hasNpu = false,  // No dedicated NPU
                maxFrequencyGHz = 2.0
            ),
            ramGb = ramGB,
            screenResolution = DeviceProfile.ScreenResolution(
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi
            ),
            androidApiLevel = apiLevel,
            optimizationTier = tier
        )
    }
    
    private fun createGenericProfile(
        manufacturer: String,
        model: String,
        ramGB: Int,
        metrics: DisplayMetrics,
        apiLevel: Int
    ): DeviceProfile {
        val tier = when (ramGB) {
            in 0..2 -> DeviceProfile.OptimizationTier.ULTRA_LIGHT
            in 3..4 -> DeviceProfile.OptimizationTier.LIGHT
            in 5..6 -> DeviceProfile.OptimizationTier.MEDIUM
            in 7..8 -> DeviceProfile.OptimizationTier.HIGH
            else -> DeviceProfile.OptimizationTier.ULTRA
        }
        
        return DeviceProfile(
            name = "$manufacturer $model",
            manufacturer = manufacturer,
            model = model,
            soc = DeviceProfile.SystemOnChip(
                name = "Unknown",
                cpuCores = Runtime.getRuntime().availableProcessors(),
                cpuArchitecture = System.getProperty("os.arch") ?: "Unknown",
                hasGpu = true,
                hasNpu = false,
                maxFrequencyGHz = 2.0
            ),
            ramGb = ramGB,
            screenResolution = DeviceProfile.ScreenResolution(
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi
            ),
            androidApiLevel = apiLevel,
            optimizationTier = tier
        )
    }
    
    /**
     * A21S Specific Configuration Constants
     */
    object A21SConfig {
        // Screen capture at reduced resolution to save processing
        const val CAPTURE_WIDTH = 360
        const val CAPTURE_HEIGHT = 800  // 720p scaled down
        
        // Model inference settings
        const val INFERENCE_INTERVAL_MS = 100L  // 10fps analysis max
        const val MODEL_LOAD_TIMEOUT_MS = 5000L
        const val MAX_MODEL_MEMORY_MB = 200
        
        // Thermal throttling (A21S gets hot easily)
        const val THERMAL_THROTTLE_TEMP = 40  // Start throttling at 40C
        const val THERMAL_CRITICAL_TEMP = 45  // Stop heavy processing at 45C
        const val THERMAL_CHECK_INTERVAL_MS = 2000L
        
        // Gesture execution
        const val GESTURE_MIN_DURATION_MS = 50L
        const val GESTURE_JITTER_THRESHOLD_PX = 3f
        const val CAMERA_SMOOTHING_FACTOR = 0.3f  // Lower = smoother but slower
        
        // Memory management
        const val MEMORY_TRIM_THRESHOLD_MB = 180
        const val GC_INTERVAL_MS = 30000L  // Force GC every 30s if needed
        
        // Audio analysis (disabled on A21S to save resources)
        const val ENABLE_AUDIO_ANALYSIS = false
        
        // RL Training (limited on A21S)
        const val RL_BATCH_SIZE = 32
        const val RL_UPDATE_INTERVAL_MS = 5000L
        const val RL_MAX_REPLAY_BUFFER = 1000
    }
}
