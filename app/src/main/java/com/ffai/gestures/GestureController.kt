package com.ffai.gestures

import android.content.Context
import com.ffai.config.DeviceProfile
import com.ffai.service.FFAIAccessibilityService
import com.ffai.FFAIApplication
import timber.log.Timber
import kotlin.math.sqrt

/**
 * Gesture Controller - High-level gesture orchestration
 * Manages gesture execution with device-specific calibration
 */
class GestureController(
    private val context: Context,
    private val deviceProfile: DeviceProfile
) {
    private val accessibilityService: FFAIAccessibilityService?
        get() = FFAIApplication.instance.accessibilityService
    
    // Calibration data
    private val calibration = loadCalibration()
    
    // Gesture statistics
    private var gestureCount = 0
    private var failedGestures = 0
    
    companion object {
        // Default timings
        const val TAP_DURATION_MS = 80L
        const val LONG_PRESS_DURATION_MS = 500L
        const val SWIPE_DURATION_MS = 250L
        const val CAMERA_PAN_DURATION_MS = 400L
        const val MICRO_ADJUST_DURATION_MS = 50L
        
        // Dead zone to prevent jitter
        const val JITTER_THRESHOLD_PX = 3f
    }
    
    /**
     * Execute simple tap
     */
    fun tap(x: Float, y: Float, durationMs: Long = TAP_DURATION_MS) {
        val calibratedX = applyCalibration(x)
        val calibratedY = applyCalibration(y)
        
        accessibilityService?.executeTap(calibratedX, calibratedY, durationMs)
        gestureCount++
        
        Timber.v("Tap at ($calibratedX, $calibratedY)")
    }
    
    /**
     * Double tap
     */
    fun doubleTap(x: Float, y: Float) {
        tap(x, y, TAP_DURATION_MS)
        Thread.sleep(TAP_DURATION_MS / 2)
        tap(x, y, TAP_DURATION_MS)
    }
    
    /**
     * Long press
     */
    fun longPress(x: Float, y: Float, durationMs: Long = LONG_PRESS_DURATION_MS) {
        tap(x, y, durationMs)
    }
    
    /**
     * Execute swipe
     */
    fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = SWIPE_DURATION_MS
    ) {
        // Filter small movements (jitter)
        val distance = distance(startX, startY, endX, endY)
        if (distance < JITTER_THRESHOLD_PX) {
            tap(startX, startY)
            return
        }
        
        val calStartX = applyCalibration(startX)
        val calStartY = applyCalibration(startY)
        val calEndX = applyCalibration(endX)
        val calEndY = applyCalibration(endY)
        
        accessibilityService?.executeSwipe(
            calStartX, calStartY,
            calEndX, calEndY,
            durationMs
        )
        
        gestureCount++
        Timber.v("Swipe from ($calStartX, $calStartY) to ($calEndX, $calEndY)")
    }
    
    /**
     * Pan camera smoothly
     */
    fun panCamera(
        deltaX: Float, deltaY: Float,
        durationMs: Long = CAMERA_PAN_DURATION_MS
    ) {
        val screenWidth = deviceProfile.screenResolution.width.toFloat()
        val screenHeight = deviceProfile.screenResolution.height.toFloat()
        
        // Start from center of right side (typical camera area)
        val startX = screenWidth * 0.75f
        val startY = screenHeight * 0.5f
        
        val endX = startX + deltaX * calibration.sensitivityMultiplier
        val endY = startY + deltaY * calibration.sensitivityMultiplier
        
        swipe(startX, startY, endX, endY, durationMs)
    }
    
    /**
     * Micro-adjust camera (fine aiming)
     */
    fun microAdjust(deltaX: Float, deltaY: Float) {
        val screenWidth = deviceProfile.screenResolution.width.toFloat()
        val screenHeight = deviceProfile.screenResolution.height.toFloat()
        
        val startX = screenWidth * 0.75f
        val startY = screenHeight * 0.5f
        
        // Smaller movement for precision
        val microMultiplier = calibration.sensitivityMultiplier * 0.3f
        val endX = startX + deltaX * microMultiplier
        val endY = startY + deltaY * microMultiplier
        
        accessibilityService?.executeSwipe(
            startX, startY, endX, endY,
            MICRO_ADJUST_DURATION_MS
        )
        
        gestureCount++
    }
    
    /**
     * Drag (touch + hold + move)
     */
    fun drag(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = SWIPE_DURATION_MS
    ) {
        // Same as swipe but with explicit semantics
        swipe(startX, startY, endX, endY, durationMs)
    }
    
    /**
     * Multi-touch gesture (e.g., pinch)
     */
    fun multiTouch(touchPoints: List<TouchPoint>, durationMs: Long) {
        val calibratedPoints = touchPoints.map { point ->
            TouchPoint(
                x = applyCalibration(point.x),
                y = applyCalibration(point.y),
                startDelayMs = point.startDelayMs,
                durationMs = point.durationMs
            )
        }
        
        accessibilityService?.executeMultiTouch(calibratedPoints, durationMs)
        gestureCount++
    }
    
    /**
     * Press fire button
     */
    fun fireTap() {
        val screenWidth = deviceProfile.screenResolution.width.toFloat()
        val screenHeight = deviceProfile.screenResolution.height.toFloat()
        
        // Fire button position (bottom right)
        val fireX = screenWidth * 0.85f
        val fireY = screenHeight * 0.75f
        
        tap(fireX, fireY, TAP_DURATION_MS)
    }
    
    /**
     * Press fire button (burst)
     */
    fun fireBurst(count: Int = 3, intervalMs: Long = 100) {
        repeat(count) {
            fireTap()
            Thread.sleep(intervalMs)
        }
    }
    
    /**
     * Press fire button (spray - hold)
     */
    fun fireSpray(durationMs: Long) {
        val screenWidth = deviceProfile.screenResolution.width.toFloat()
        val screenHeight = deviceProfile.screenResolution.height.toFloat()
        
        val fireX = screenWidth * 0.85f
        val fireY = screenHeight * 0.75f
        
        tap(fireX, fireY, durationMs)
    }
    
    /**
     * Move joystick
     */
    fun moveJoystick(directionX: Float, directionY: Float, magnitude: Float = 1.0f) {
        val screenWidth = deviceProfile.screenResolution.width.toFloat()
        val screenHeight = deviceProfile.screenResolution.height.toFloat()
        
        // Joystick position (bottom left)
        val joyX = screenWidth * 0.15f
        val joyY = screenHeight * 0.75f
        val joyRadius = screenWidth * 0.1f * magnitude
        
        val endX = joyX + directionX * joyRadius
        val endY = joyY + directionY * joyRadius
        
        swipe(joyX, joyY, endX, endY, SWIPE_DURATION_MS)
    }
    
    /**
     * Stop moving (release joystick)
     */
    fun stopJoystick() {
        val screenWidth = deviceProfile.screenResolution.width.toFloat()
        val screenHeight = deviceProfile.screenResolution.height.toFloat()
        
        val joyX = screenWidth * 0.15f
        val joyY = screenHeight * 0.75f
        
        tap(joyX, joyY, 50)
    }
    
    /**
     * Use ability/skill
     */
    fun useAbility(ability: FFAIAccessibilityService.AbilityType) {
        // Ability button positions vary by game layout
        // This is a generic implementation
        val screenWidth = deviceProfile.screenResolution.width.toFloat()
        val screenHeight = deviceProfile.screenResolution.height.toFloat()
        
        val abilityX = screenWidth * 0.7f
        val abilityY = screenHeight * 0.85f
        
        tap(abilityX, abilityY)
    }
    
    /**
     * Reload weapon
     */
    fun reload(weaponSlot: Int = 0) {
        val screenWidth = deviceProfile.screenResolution.width.toFloat()
        val screenHeight = deviceProfile.screenResolution.height.toFloat()
        
        // Reload button location
        val reloadX = screenWidth * 0.7f
        val reloadY = screenHeight * 0.60f
        
        tap(reloadX, reloadY)
    }
    
    /**
     * Use item (health kit, shield, etc.)
     */
    fun useItem(itemSlot: Int) {
        val screenWidth = deviceProfile.screenResolution.width.toFloat()
        val screenHeight = deviceProfile.screenResolution.height.toFloat()
        
        // Item bar position
        val itemY = screenHeight * 0.90f
        val itemSpacing = screenWidth * 0.08f
        val itemX = screenWidth * 0.5f + (itemSlot - 2) * itemSpacing
        
        tap(itemX, itemY)
    }
    
    /**
     * Press jump
     */
    fun jump() {
        val screenWidth = deviceProfile.screenResolution.width.toFloat()
        val screenHeight = deviceProfile.screenResolution.height.toFloat()
        
        val jumpX = screenWidth * 0.92f
        val jumpY = screenHeight * 0.55f
        
        tap(jumpX, jumpY)
    }
    
    /**
     * Press crouch/prone
     */
    fun crouch() {
        val screenWidth = deviceProfile.screenResolution.width.toFloat()
        val screenHeight = deviceProfile.screenResolution.height.toFloat()
        
        val crouchX = screenWidth * 0.92f
        val crouchY = screenHeight * 0.65f
        
        tap(crouchX, crouchY)
    }
    
    /**
     * Interact (open door, loot, etc.)
     */
    fun interact() {
        val screenWidth = deviceProfile.screenResolution.width.toFloat()
        val screenHeight = deviceProfile.screenResolution.height.toFloat()
        
        // Center of screen
        val interactX = screenWidth * 0.5f
        val interactY = screenHeight * 0.5f
        
        tap(interactX, interactY)
    }
    
    /**
     * Execute compound gesture sequence
     */
    fun executeSequence(vararg gestures: TimedGesture) {
        gestures.forEach { gesture ->
            when (gesture) {
                is TimedGesture.Tap -> tap(gesture.x, gesture.y, gesture.durationMs)
                is TimedGesture.Swipe -> swipe(
                    gesture.startX, gesture.startY,
                    gesture.endX, gesture.endY,
                    gesture.durationMs
                )
                is TimedGesture.Pan -> panCamera(gesture.deltaX, gesture.deltaY, gesture.durationMs)
                is TimedGesture.Wait -> Thread.sleep(gesture.durationMs)
            }
            
            // Small delay between gestures
            Thread.sleep(50)
        }
    }
    
    private fun applyCalibration(value: Float): Float {
        return value * calibration.coordinateMultiplier + calibration.coordinateOffset
    }
    
    private fun loadCalibration(): Calibration {
        // Load from preferences or use defaults
        return Calibration(
            sensitivityMultiplier = 1.0f,
            coordinateMultiplier = 1.0f,
            coordinateOffset = 0f
        )
    }
    
    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
    }
    
    fun getStats(): GestureStats {
        return GestureStats(
            totalGestures = gestureCount,
            failedGestures = failedGestures,
            successRate = if (gestureCount > 0) {
                (gestureCount - failedGestures).toFloat() / gestureCount
            } else 1f
        )
    }
    
    // Data classes
    data class TouchPoint(
        val x: Float,
        val y: Float,
        val startDelayMs: Long = 0,
        val durationMs: Long = 100
    )
    
    data class Calibration(
        val sensitivityMultiplier: Float,
        val coordinateMultiplier: Float,
        val coordinateOffset: Float
    )
    
    data class GestureStats(
        val totalGestures: Int,
        val failedGestures: Int,
        val successRate: Float
    )
    
    sealed class TimedGesture(val durationMs: Long) {
        class Tap(val x: Float, val y: Float, duration: Long) : TimedGesture(duration)
        class Swipe(
            val startX: Float, val startY: Float,
            val endX: Float, val endY: Float,
            duration: Long
        ) : TimedGesture(duration)
        class Pan(val deltaX: Float, val deltaY: Float, duration: Long) : TimedGesture(duration)
        class Wait(duration: Long) : TimedGesture(duration)
    }
}
