package com.ffai.gestures.synthesis

import com.ffai.config.DeviceProfile
import com.ffai.core.policy.PolicySelector
import timber.log.Timber
import kotlin.math.*
import kotlin.random.Random

/**
 * Gesture Synthesizer - Converts high-level actions into physical touch primitives
 * Separates intent from physical execution
 */
class GestureSynthesizer(
    private val deviceProfile: DeviceProfile
) {
    // Screen dimensions
    private val screenWidth = deviceProfile.screenResolution.width.toFloat()
    private val screenHeight = deviceProfile.screenResolution.height.toFloat()
    
    // Gesture templates
    private val gestureTemplates = mutableMapOf<String, GestureTemplate>()
    
    // Current gesture sequence
    private var activeSequence: GestureSequence? = null
    
    init {
        initializeTemplates()
    }
    
    /**
     * Synthesize gesture from policy action
     */
    fun synthesize(
        action: PolicySelector.PolicyAction,
        context: SynthesisContext
    ): GestureSequence {
        // Get base template
        val template = gestureTemplates[action.name] ?: getFallbackTemplate(action)
        
        // Customize based on context
        val customized = customizeTemplate(template, context)
        
        // Apply humanization
        val humanized = applyHumanization(customized, context.humanizationParams)
        
        // Create sequence
        val sequence = GestureSequence(
            id = generateSequenceId(),
            action = action,
            gestures = humanized,
            totalDuration = humanized.sumOf { it.durationMs },
            priority = context.priority
        )
        
        activeSequence = sequence
        Timber.d("Synthesized gesture sequence for $action: ${humanized.size} gestures, ${sequence.totalDuration}ms")
        
        return sequence
    }
    
    /**
     * Synthesize compound gesture (multi-step)
     */
    fun synthesizeCompound(
        actions: List<PolicySelector.PolicyAction>,
        delays: List<Long>,
        context: SynthesisContext
    ): CompoundGestureSequence {
        val sequences = actions.map { synthesize(it, context) }
        
        return CompoundGestureSequence(
            id = generateSequenceId(),
            sequences = sequences,
            delays = delays,
            totalDuration = sequences.sumOf { it.totalDuration } + delays.sum()
        )
    }
    
    /**
     * Synthesize camera movement
     */
    fun synthesizeCameraMovement(
        targetX: Float,
        targetY: Float,
        style: CameraStyle,
        context: CameraContext
    ): CameraGesture {
        val startX = screenWidth * 0.75f  // Camera area
        val startY = screenHeight * 0.5f
        
        // Calculate path based on style
        val path = when (style) {
            CameraStyle.DIRECT -> listOf(
                Point(startX, startY),
                Point(targetX, targetY)
            )
            CameraStyle.SMOOTH -> generateSmoothPath(startX, startY, targetX, targetY, 5)
            CameraStyle.MICRO -> generateMicroAdjust(startX, startY, targetX, targetY)
            CameraStyle.SCAN -> generateScanPath(startX, startY, targetX, targetY)
            CameraStyle.FLICK -> generateFlickPath(startX, startY, targetX, targetY)
        }
        
        // Apply style-specific timing
        val duration = when (style) {
            CameraStyle.DIRECT -> 100L
            CameraStyle.SMOOTH -> 200L
            CameraStyle.MICRO -> 50L
            CameraStyle.SCAN -> 500L
            CameraStyle.FLICK -> 80L
        }
        
        return CameraGesture(
            path = path,
            durationMs = duration,
            style = style,
            interpolation = when (style) {
                CameraStyle.DIRECT -> InterpolationType.LINEAR
                CameraStyle.SMOOTH -> InterpolationType.CATMULL_ROM
                CameraStyle.MICRO -> InterpolationType.EASE_OUT
                CameraStyle.SCAN -> InterpolationType.EASE_IN_OUT
                CameraStyle.FLICK -> InterpolationType.EASE_OUT_QUART
            }
        )
    }
    
    /**
     * Generate recoil compensation gesture
     */
    fun synthesizeRecoilCompensation(
        weapon: String,
        shots: Int,
        pattern: RecoilPattern
    ): List<TouchGesture> {
        val gestures = mutableListOf<TouchGesture>()
        
        // Generate compensation movements
        for (i in 0 until shots) {
            val offsetX = pattern.horizontalDrift * i + Random.nextFloat() * 2f
            val offsetY = -pattern.verticalKick * i  // Compensate upward
            
            gestures.add(TouchGesture(
                type = GestureType.MICRO_DRAG,
                startX = screenWidth * 0.85f + offsetX,
                startY = screenHeight * 0.75f + offsetY,
                endX = screenWidth * 0.85f + offsetX + pattern.compensationX,
                endY = screenHeight * 0.75f + offsetY + pattern.compensationY,
                durationMs = pattern.timeBetweenShots,
                pressure = 0.8f + Random.nextFloat() * 0.2f
            ))
        }
        
        return gestures
    }
    
    /**
     * Interrupt current gesture
     */
    fun interrupt(): Boolean {
        activeSequence?.let {
            it.interrupted = true
            activeSequence = null
            Timber.d("Gesture sequence interrupted")
            return true
        }
        return false
    }
    
    /**
     * Check if currently synthesizing
     */
    fun isActive(): Boolean = activeSequence != null && !activeSequence!!.interrupted
    
    // Private methods
    
    private fun initializeTemplates() {
        // Movement templates
        gestureTemplates["MOVE_FORWARD"] = GestureTemplate(
            name = "joystick_forward",
            baseGestures = listOf(
                TouchGesture(
                    type = GestureType.DRAG,
                    startX = screenWidth * 0.15f,
                    startY = screenHeight * 0.75f,
                    endX = screenWidth * 0.15f,
                    endY = screenHeight * 0.65f,
                    durationMs = 200,
                    pressure = 0.9f
                )
            )
        )
        
        gestureTemplates["FIRE_BURST"] = GestureTemplate(
            name = "burst_fire",
            baseGestures = listOf(
                TouchGesture(
                    type = GestureType.TAP_HOLD,
                    startX = screenWidth * 0.85f,
                    startY = screenHeight * 0.75f,
                    endX = screenWidth * 0.85f,
                    endY = screenHeight * 0.75f,
                    durationMs = 150,
                    pressure = 0.95f
                )
            )
        )
        
        gestureTemplates["AIM"] = GestureTemplate(
            name = "aim_down_sights",
            baseGestures = listOf(
                TouchGesture(
                    type = GestureType.TAP_HOLD,
                    startX = screenWidth * 0.92f,
                    startY = screenHeight * 0.55f,
                    endX = screenWidth * 0.92f,
                    endY = screenHeight * 0.55f,
                    durationMs = 500,
                    pressure = 0.85f
                )
            )
        )
        
        gestureTemplates["USE_HEAL"] = GestureTemplate(
            name = "use_medkit",
            baseGestures = listOf(
                TouchGesture(
                    type = GestureType.TAP,
                    startX = screenWidth * 0.7f,
                    startY = screenHeight * 0.9f,
                    endX = screenWidth * 0.7f,
                    endY = screenHeight * 0.9f,
                    durationMs = 100,
                    pressure = 0.8f
                ),
                TouchGesture(
                    type = GestureType.TAP,
                    startX = screenWidth * 0.5f,
                    startY = screenHeight * 0.9f,
                    endX = screenWidth * 0.5f,
                    endY = screenHeight * 0.9f,
                    durationMs = 100,
                    pressure = 0.8f,
                    startDelayMs = 100
                )
            )
        )
    }
    
    private fun getFallbackTemplate(action: PolicySelector.PolicyAction): GestureTemplate {
        return GestureTemplate(
            name = "fallback_tap",
            baseGestures = listOf(
                TouchGesture(
                    type = GestureType.TAP,
                    startX = screenWidth * 0.5f,
                    startY = screenHeight * 0.5f,
                    endX = screenWidth * 0.5f,
                    endY = screenHeight * 0.5f,
                    durationMs = 80,
                    pressure = 0.7f
                )
            )
        )
    }
    
    private fun customizeTemplate(
        template: GestureTemplate,
        context: SynthesisContext
    ): List<TouchGesture> {
        return template.baseGestures.map { gesture ->
            gesture.copy(
                startX = gesture.startX + context.positionOffset.first,
                startY = gesture.startY + context.positionOffset.second,
                durationMs = (gesture.durationMs * context.speedMultiplier).toLong()
            )
        }
    }
    
    private fun applyHumanization(
        gestures: List<TouchGesture>,
        params: HumanizationParams
    ): List<TouchGesture> {
        return gestures.map { gesture ->
            val jitterX = (Random.nextFloat() - 0.5f) * params.jitterAmount * 2
            val jitterY = (Random.nextFloat() - 0.5f) * params.jitterAmount * 2
            
            val timingVar = (Random.nextFloat() - 0.5f) * params.timingVariation * 2
            
            gesture.copy(
                startX = gesture.startX + jitterX,
                startY = gesture.startY + jitterY,
                endX = gesture.endX + jitterX,
                endY = gesture.endY + jitterY,
                durationMs = (gesture.durationMs * (1 + timingVar)).toLong()
                    .coerceAtLeast(16),
                startDelayMs = (gesture.startDelayMs + params.additionalDelay).toLong()
            )
        }
    }
    
    private fun generateSmoothPath(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        points: Int
    ): List<Point> {
        val path = mutableListOf<Point>()
        
        for (i in 0..points) {
            val t = i.toFloat() / points
            // Bezier curve interpolation
            val x = catmullRom(startX, startX, endX, endX, t)
            val y = catmullRom(startY, startY, endY, endY, t)
            path.add(Point(x, y))
        }
        
        return path
    }
    
    private fun generateMicroAdjust(
        startX: Float, startY: Float,
        targetX: Float, targetY: Float
    ): List<Point> {
        val deltaX = targetX - startX
        val deltaY = targetY - startY
        
        // Very small movements for precision
        return listOf(
            Point(startX, startY),
            Point(startX + deltaX * 0.3f, startY + deltaY * 0.3f),
            Point(targetX, targetY)
        )
    }
    
    private fun generateScanPath(
        startX: Float, startY: Float,
        centerX: Float, centerY: Float
    ): List<Point> {
        // Horizontal sweep pattern
        return listOf(
            Point(startX, startY),
            Point(centerX - 100, centerY),
            Point(centerX, centerY),
            Point(centerX + 100, centerY),
            Point(startX, startY)
        )
    }
    
    private fun generateFlickPath(
        startX: Float, startY: Float,
        targetX: Float, targetY: Float
    ): List<Point> {
        // Quick flick with overshoot
        val overshootX = targetX + (targetX - startX) * 0.1f
        val overshootY = targetY + (targetY - startY) * 0.1f
        
        return listOf(
            Point(startX, startY),
            Point(overshootX, overshootY),
            Point(targetX, targetY)
        )
    }
    
    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        
        return 0.5f * (
            (2 * p1) +
            (-p0 + p2) * t +
            (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 +
            (-p0 + 3 * p1 - 3 * p2 + p3) * t3
        )
    }
    
    private fun generateSequenceId(): String {
        return "gest_${System.currentTimeMillis()}_${Random.nextInt(10000)}"
    }
    
    // Data classes
    
    data class GestureSequence(
        val id: String,
        val action: PolicySelector.PolicyAction,
        val gestures: List<TouchGesture>,
        val totalDuration: Long,
        val priority: Int,
        var interrupted: Boolean = false,
        var executed: Boolean = false
    )
    
    data class CompoundGestureSequence(
        val id: String,
        val sequences: List<GestureSequence>,
        val delays: List<Long>,
        val totalDuration: Long
    )
    
    data class TouchGesture(
        val type: GestureType,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long,
        val pressure: Float = 0.8f,
        val startDelayMs: Long = 0
    )
    
    data class CameraGesture(
        val path: List<Point>,
        val durationMs: Long,
        val style: CameraStyle,
        val interpolation: InterpolationType
    )
    
    data class GestureTemplate(
        val name: String,
        val baseGestures: List<TouchGesture>
    )
    
    data class Point(val x: Float, val y: Float)
    
    data class SynthesisContext(
        val priority: Int,
        val positionOffset: Pair<Float, Float> = Pair(0f, 0f),
        val speedMultiplier: Float = 1f,
        val humanizationParams: HumanizationParams = HumanizationParams()
    )
    
    data class HumanizationParams(
        val jitterAmount: Float = 2f,
        val timingVariation: Float = 0.1f,
        val additionalDelay: Float = 0f
    )
    
    data class CameraContext(
        val currentAim: Pair<Float, Float>,
        val targetVelocity: Pair<Float, Float>,
        val urgency: Float
    )
    
    data class RecoilPattern(
        val verticalKick: Float,
        val horizontalDrift: Float,
        val compensationX: Float,
        val compensationY: Float,
        val timeBetweenShots: Long
    )
    
    enum class GestureType {
        TAP, TAP_HOLD, DRAG, MICRO_DRAG, 
        MULTI_TAP, PINCH, ROTATE
    }
    
    enum class CameraStyle {
        DIRECT, SMOOTH, MICRO, SCAN, FLICK
    }
    
    enum class InterpolationType {
        LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT,
        CATMULL_ROM, EASE_OUT_QUART, EASE_OUT_ELASTIC
    }
}
