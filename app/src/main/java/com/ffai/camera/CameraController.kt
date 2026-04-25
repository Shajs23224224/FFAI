package com.ffai.camera

import com.ffai.config.DeviceProfile
import com.ffai.gestures.GestureController
import com.ffai.service.FFAIAccessibilityService
import com.ffai.FFAIApplication
import kotlinx.coroutines.*
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Camera Controller - Smooth camera movements and aiming
 * Implements PID control + Kalman filtering for optimal tracking
 */
class CameraController(
    private val deviceProfile: DeviceProfile
) {
    private val gestureController = GestureController(
        FFAIApplication.instance,
        deviceProfile
    )
    
    // Screen dimensions
    private val screenWidth = deviceProfile.screenResolution.width.toFloat()
    private val screenHeight = deviceProfile.screenResolution.height.toFloat()
    
    // Camera state
    private var currentYaw = 0f  // Horizontal rotation
    private var currentPitch = 0f  // Vertical rotation
    
    // Target tracking
    private var targetX = screenWidth * 0.5f
    private var targetY = screenHeight * 0.5f
    private var targetVelocityX = 0f
    private var targetVelocityY = 0f
    
    // Smoothing settings (based on device tier)
    private val smoothingFactor = when(deviceProfile.optimizationTier) {
        DeviceProfile.OptimizationTier.ULTRA_LIGHT -> 0.5f
        DeviceProfile.OptimizationTier.LIGHT -> 0.4f
        DeviceProfile.OptimizationTier.MEDIUM -> 0.3f
        DeviceProfile.OptimizationTier.HIGH -> 0.2f
        DeviceProfile.OptimizationTier.ULTRA -> 0.15f
    }
    
    // PID controllers for smooth movement
    private val pidX = PIDController(kp = 0.8f, ki = 0.1f, kd = 0.3f)
    private val pidY = PIDController(kp = 0.8f, ki = 0.1f, kd = 0.3f)
    
    // Kalman filter for prediction
    private val kalman = SimpleKalmanFilter(
        processNoise = 0.01f,
        measurementNoise = 0.1f
    )
    
    // Scope for continuous tracking
    private var trackingJob: Job? = null
    
    companion object {
        // Camera sensitivity (adjustable)
        const val BASE_SENSITIVITY_X = 0.003f  // radians per pixel
        const val BASE_SENSITIVITY_Y = 0.003f
        const val MAX_ROTATION_SPEED = 5f  // radians per second
        const val OVERSHOOT_LIMIT = 0.1f  // radians
    }
    
    /**
     * Aim at specific screen coordinates
     */
    fun aim(targetX: Float, targetY: Float, predictionOffset: Pair<Float, Float> = Pair(0f, 0f)) {
        val predictedX = targetX + predictionOffset.first
        val predictedY = targetY + predictionOffset.second
        
        // Calculate delta from center
        val deltaX = predictedX - screenWidth * 0.5f
        val deltaY = predictedY - screenHeight * 0.5f
        
        // PID-controlled movement
        val controlX = pidX.calculate(deltaX)
        val controlY = pidY.calculate(deltaY)
        
        // Apply smoothing and limits
        val smoothX = applySmoothing(controlX, currentYaw)
        val smoothY = applySmoothing(controlY, currentPitch)
        
        // Convert to rotation (with sensitivity)
        val rotationX = smoothX * BASE_SENSITIVITY_X
        val rotationY = smoothY * BASE_SENSITIVITY_Y
        
        // Apply overshoot limit
        val limitedRotationX = rotationX.coerceIn(-OVERSHOOT_LIMIT, OVERSHOOT_LIMIT)
        val limitedRotationY = rotationY.coerceIn(-OVERSHOOT_LIMIT, OVERSHOOT_LIMIT)
        
        // Execute camera movement
        if (kotlin.math.abs(limitedRotationX) > 0.001f || kotlin.math.abs(limitedRotationY) > 0.001f) {
            gestureController.panCamera(
                limitedRotationX,
                limitedRotationY,
                50  // Quick adjustment
            )
        }
        
        // Update current rotation
        currentYaw += limitedRotationX
        currentPitch += limitedRotationY
        
        Timber.v("Aim: delta=($deltaX, $deltaY), rot=($limitedRotationX, $limitedRotationY)")
    }
    
    /**
     * Track moving target with prediction
     */
    fun trackTarget(target: Pair<Float, Float>, targetVelocity: Pair<Float, Float>) {
        // Update Kalman filter with measurement
        kalman.predict()
        kalman.update(target.first, target.second)
        
        // Get filtered position
        val filteredPos = kalman.getEstimate()
        
        // Predict future position
        val predictionTime = 0.1f  // 100ms ahead
        val predictedX = filteredPos.first + targetVelocity.first * predictionTime
        val predictedY = filteredPos.second + targetVelocity.second * predictionTime
        
        aim(predictedX, predictedY)
    }
    
    /**
     * Start continuous tracking
     */
    fun startTracking(targetProvider: () -> Pair<Float, Float>?) {
        trackingJob?.cancel()
        
        trackingJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                val target = targetProvider()
                target?.let { aim(it.first, it.second) }
                delay(16)  // ~60fps
            }
        }
    }
    
    /**
     * Stop continuous tracking
     */
    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        pidX.reset()
        pidY.reset()
    }
    
    /**
     * Micro-adjust for precision aiming
     */
    fun microAdjust(deltaX: Float, deltaY: Float, precision: Float = 0.95f) {
        // Smaller, more precise movements
        val microX = deltaX * 0.3f * precision
        val microY = deltaY * 0.3f * precision
        
        gestureController.microAdjust(microX, microY)
    }
    
    /**
     * Sweep camera to scan area
     */
    fun scan(area: Rect) {
        // Pan across the area
        val centerX = (area.left + area.right) / 2
        val centerY = (area.top + area.bottom) / 2
        val width = area.right - area.left
        
        // Horizontal sweep
        val startX = centerX - width / 2
        val endX = centerX + width / 2
        
        gestureController.panCamera(
            (endX - startX) * BASE_SENSITIVITY_X,
            0f,
            500  // Slow, deliberate scan
        )
    }
    
    /**
     * Re-center camera
     */
    fun recenter() {
        // Reset to default view
        val deltaYaw = -currentYaw
        val deltaPitch = -currentPitch
        
        gestureController.panCamera(
            deltaYaw / BASE_SENSITIVITY_X,
            deltaPitch / BASE_SENSITIVITY_Y,
            300
        )
        
        currentYaw = 0f
        currentPitch = 0f
        pidX.reset()
        pidY.reset()
    }
    
    /**
     * Set camera sensitivity
     */
    fun setSensitivity(sensitivityX: Float, sensitivityY: Float) {
        // Update base sensitivity
        // This would be passed to the native gesture engine
    }
    
    private fun applySmoothing(target: Float, current: Float): Float {
        return current + (target - current) * smoothingFactor
    }
    
    private fun calculateDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
    }
    
    /**
     * Get camera state
     */
    fun getState(): CameraState {
        return CameraState(
            yaw = currentYaw,
            pitch = currentPitch,
            targetDistance = calculateDistance(
                screenWidth * 0.5f, screenHeight * 0.5f,
                targetX, targetY
            ),
            isTracking = trackingJob?.isActive == true
        )
    }
    
    // Data classes
    data class CameraState(
        val yaw: Float,
        val pitch: Float,
        val targetDistance: Float,
        val isTracking: Boolean
    )
    
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)
    
    // PID Controller implementation
    class PIDController(
        private val kp: Float,
        private val ki: Float,
        private val kd: Float
    ) {
        private var integral = 0f
        private var lastError = 0f
        private var lastTime = System.currentTimeMillis()
        
        fun calculate(error: Float): Float {
            val currentTime = System.currentTimeMillis()
            val dt = (currentTime - lastTime) / 1000f  // seconds
            
            if (dt <= 0) return error * kp
            
            // Proportional
            val p = error * kp
            
            // Integral (with anti-windup)
            integral += error * dt
            integral = integral.coerceIn(-1f, 1f)
            val i = integral * ki
            
            // Derivative
            val derivative = (error - lastError) / dt
            val d = derivative * kd
            
            lastError = error
            lastTime = currentTime
            
            return p + i + d
        }
        
        fun reset() {
            integral = 0f
            lastError = 0f
        }
    }
    
    // Simple Kalman Filter for 2D tracking
    class SimpleKalmanFilter(
        private val processNoise: Float,
        private val measurementNoise: Float
    ) {
        private var x = 0f
        private var y = 0f
        private var vx = 0f
        private var vy = 0f
        private var uncertainty = 1f
        
        fun predict() {
            // State prediction (constant velocity model)
            x += vx * 0.016f  // assume 60fps
            y += vy * 0.016f
            uncertainty += processNoise
        }
        
        fun update(measuredX: Float, measuredY: Float) {
            // Kalman gain
            val k = uncertainty / (uncertainty + measurementNoise)
            
            // State update
            val innovationX = measuredX - x
            val innovationY = measuredY - y
            
            x += k * innovationX
            y += k * innovationY
            
            // Velocity update (from innovation)
            vx = k * innovationX / 0.016f
            vy = k * innovationY / 0.016f
            
            // Uncertainty update
            uncertainty = (1 - k) * uncertainty
        }
        
        fun getEstimate(): Pair<Float, Float> = Pair(x, y)
        fun getVelocity(): Pair<Float, Float> = Pair(vx, vy)
    }
}
