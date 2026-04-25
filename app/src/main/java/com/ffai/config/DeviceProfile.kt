package com.ffai.config

/**
 * Device Profile - Hardware specifications and optimization parameters
 */
data class DeviceProfile(
    val name: String,
    val manufacturer: String,
    val model: String,
    val soc: SystemOnChip,
    val ramGb: Int,
    val screenResolution: ScreenResolution,
    val androidApiLevel: Int,
    val optimizationTier: OptimizationTier
) {
    data class SystemOnChip(
        val name: String,
        val cpuCores: Int,
        val cpuArchitecture: String,
        val hasGpu: Boolean,
        val hasNpu: Boolean,
        val maxFrequencyGHz: Double
    )
    
    data class ScreenResolution(
        val width: Int,
        val height: Int,
        val densityDpi: Int
    )
    
    enum class OptimizationTier {
        ULTRA_LIGHT,    // < 2GB RAM, very weak CPU
        LIGHT,          // 2-3GB RAM, entry-level CPU
        MEDIUM,         // 4GB RAM, mid-range CPU
        HIGH,           // 6GB+ RAM, flagship CPU
        ULTRA           // 8GB+ RAM, latest flagship
    }
}

/**
 * Performance Limits based on Device Profile
 */
object PerformanceLimits {
    
    fun getForTier(tier: DeviceProfile.OptimizationTier): Limits {
        return when (tier) {
            DeviceProfile.OptimizationTier.ULTRA_LIGHT -> Limits(
                maxConcurrentModels = 1,
                maxMemoryMB = 150,
                targetLatencyMs = 50,
                inferenceThreads = 2,
                screenAnalysisResolution = 160,  // Very low res analysis
                thermalThrottleTempC = 38,
                modelQuantization = ModelQuantization.INT8,
                useNNAPI = false,
                useGPU = false,
                enableAudioAnalysis = false,
                maxEnemiesTracked = 2,
                cameraSmoothingFrames = 3,
                predictionHorizonMs = 500
            )
            
            DeviceProfile.OptimizationTier.LIGHT -> Limits(
                maxConcurrentModels = 1,
                maxMemoryMB = 250,
                targetLatencyMs = 33,  // 30fps
                inferenceThreads = 3,
                screenAnalysisResolution = 240,
                thermalThrottleTempC = 40,
                modelQuantization = ModelQuantization.INT8,
                useNNAPI = true,  // Try NNAPI but may not help much
                useGPU = true,    // Mali-G52 is weak but usable
                enableAudioAnalysis = true,
                maxEnemiesTracked = 3,
                cameraSmoothingFrames = 5,
                predictionHorizonMs = 1000
            )
            
            DeviceProfile.OptimizationTier.MEDIUM -> Limits(
                maxConcurrentModels = 2,
                maxMemoryMB = 400,
                targetLatencyMs = 16,  // 60fps
                inferenceThreads = 4,
                screenAnalysisResolution = 320,
                thermalThrottleTempC = 42,
                modelQuantization = ModelQuantization.INT8,
                useNNAPI = true,
                useGPU = true,
                enableAudioAnalysis = true,
                maxEnemiesTracked = 5,
                cameraSmoothingFrames = 7,
                predictionHorizonMs = 2000
            )
            
            DeviceProfile.OptimizationTier.HIGH -> Limits(
                maxConcurrentModels = 3,
                maxMemoryMB = 600,
                targetLatencyMs = 11,  // 90fps
                inferenceThreads = 6,
                screenAnalysisResolution = 480,
                thermalThrottleTempC = 45,
                modelQuantization = ModelQuantization.FP16,
                useNNAPI = true,
                useGPU = true,
                enableAudioAnalysis = true,
                maxEnemiesTracked = 8,
                cameraSmoothingFrames = 10,
                predictionHorizonMs = 3000
            )
            
            DeviceProfile.OptimizationTier.ULTRA -> Limits(
                maxConcurrentModels = 4,
                maxMemoryMB = 1024,
                targetLatencyMs = 8,   // 120fps
                inferenceThreads = 8,
                screenAnalysisResolution = 640,
                thermalThrottleTempC = 48,
                modelQuantization = ModelQuantization.FP16,
                useNNAPI = true,
                useGPU = true,
                enableAudioAnalysis = true,
                maxEnemiesTracked = 10,
                cameraSmoothingFrames = 15,
                predictionHorizonMs = 5000
            )
        }
    }
    
    data class Limits(
        val maxConcurrentModels: Int,
        val maxMemoryMB: Int,
        val targetLatencyMs: Int,
        val inferenceThreads: Int,
        val screenAnalysisResolution: Int,  // Width of downscaled analysis image
        val thermalThrottleTempC: Int,
        val modelQuantization: ModelQuantization,
        val useNNAPI: Boolean,
        val useGPU: Boolean,
        val enableAudioAnalysis: Boolean,
        val maxEnemiesTracked: Int,
        val cameraSmoothingFrames: Int,
        val predictionHorizonMs: Long
    )
    
    enum class ModelQuantization {
        INT8, FP16, FP32
    }
}
