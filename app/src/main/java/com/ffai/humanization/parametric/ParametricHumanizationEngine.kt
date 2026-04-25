package com.ffai.humanization.parametric

import com.ffai.config.DeviceProfile
import com.ffai.personality.PersonalityEngine
import com.ffai.core.orchestrator.CentralOrchestrator
import timber.log.Timber
import kotlin.math.*
import kotlin.random.Random

/**
 * Parametric Humanization Engine - Models realistic human behavior
 * Features: Time variation, precision variation, natural pauses, error recovery, rhythm changes
 */
class ParametricHumanizationEngine(
    private val deviceProfile: DeviceProfile,
    private val personalityEngine: PersonalityEngine
) {
    // Human behavior models
    private val rhythmModel = RhythmModel()
    private val errorRecoveryModel = ErrorRecoveryModel()
    private val pressureResponseModel = PressureResponseModel()
    private val precisionModel = PrecisionModel()
    
    // Current state
    private var currentPressure = 0f
    private var currentFatigue = 0f
    private var consecutiveErrors = 0
    private var lastActionTime = 0L
    
    // Parameters
    private val baseReactionTimeMs = 150f
    private val maxReactionTimeMs = 400f
    
    /**
     * Apply humanization to action
     */
    fun humanize(action: CentralOrchestrator.AIAction, context: HumanizationContext): HumanizedAction {
        // Update pressure based on context
        updatePressure(context)
        
        // Get personality influence
        val personality = personalityEngine.getCurrentPersonality()
        
        // Calculate humanization parameters
        val rhythmParams = rhythmModel.getParameters(
            pressure = currentPressure,
            fatigue = currentFatigue,
            personality = personality
        )
        
        val precisionParams = precisionModel.getParameters(
            action = action,
            pressure = currentPressure,
            fatigue = currentFatigue,
            skillLevel = personality.accuracy // Use accuracy as proxy for skill
        )
        
        val timingParams = calculateTiming(
            baseTiming = getBaseTiming(action),
            rhythm = rhythmParams,
            pressure = currentPressure,
            personality = personality
        )
        
        // Apply error if applicable
        val errorParams = calculateErrorChance(
            action = action,
            pressure = currentPressure,
            consecutiveErrors = consecutiveErrors,
            fatigue = currentFatigue
        )
        
        // Create humanized action
        val humanized = when (action) {
            is CentralOrchestrator.AIAction.Move -> humanizeMove(action, timingParams, precisionParams)
            is CentralOrchestrator.AIAction.Aim -> humanizeAim(action, timingParams, precisionParams, errorParams)
            is CentralOrchestrator.AIAction.Fire -> humanizeFire(action, timingParams, rhythmParams, errorParams)
            is CentralOrchestrator.AIAction.UseAbility -> humanizeAbility(action, timingParams)
            is CentralOrchestrator.AIAction.Compound -> humanizeCompound(action, timingParams, rhythmParams)
            else -> HumanizedAction.Base(action, timingParams)
        }
        
        // Record for feedback
        lastActionTime = System.currentTimeMillis()
        
        if (errorParams.willError) {
            consecutiveErrors++
        } else {
            consecutiveErrors = 0
        }
        
        Timber.v("Humanized action: type=${action::class.simpleName}, " +
                "delay=${timingParams.reactionDelayMs}ms, " +
                "precision=${precisionParams.precisionModifier}, " +
                "willError=${errorParams.willError}")
        
        return humanized
    }
    
    /**
     * Generate natural pause
     */
    fun generatePause(context: PauseContext): Long {
        val basePause = when (context.type) {
            PauseType.THINKING -> 200L..500L
            PauseType.REACTION -> 100L..300L
            PauseType.STRATEGIC -> 500L..1500L
            PauseType.RECOVERY -> 1000L..3000L
            PauseType.NATURAL -> 50L..200L
        }
        
        // Adjust for pressure
        val pressureFactor = if (currentPressure > 0.7f) 0.5f else 1f
        
        // Adjust for fatigue
        val fatigueFactor = 1f + currentFatigue * 0.5f
        
        val minPause = (basePause.first * pressureFactor * fatigueFactor).toLong()
        val maxPause = (basePause.last * pressureFactor * fatigueFactor).toLong()
        
        return Random.nextLong(minPause, maxPause)
    }
    
    /**
     * Calculate recovery from error
     */
    fun calculateErrorRecovery(
        error: ErrorType,
        context: RecoveryContext
    ): RecoveryPlan {
        val baseRecovery = errorRecoveryModel.getRecoveryStrategy(error)
        
        // Adjust for pressure
        val pressureAdjusted = if (currentPressure > 0.8f) {
            baseRecovery.copy(
                recoveryTimeMs = (baseRecovery.recoveryTimeMs * 0.7f).toLong(),
                compensationAmount = baseRecovery.compensationAmount * 1.2f
            )
        } else baseRecovery
        
        // Adjust for personality
        val personality = personalityEngine.getCurrentPersonality()
        val finalRecovery = if (personality.caution > 0.7f) {
            pressureAdjusted.copy(
                conservativeFallback = true,
                recoveryTimeMs = (pressureAdjusted.recoveryTimeMs * 1.3f).toLong()
            )
        } else pressureAdjusted
        
        return finalRecovery
    }
    
    /**
     * Change rhythm based on game pressure
     */
    fun adaptRhythm(pressureLevel: Float, reason: String) {
        currentPressure = pressureLevel.coerceIn(0f, 1f)
        
        // Increase fatigue under sustained pressure
        if (pressureLevel > 0.6f) {
            currentFatigue = (currentFatigue + 0.01f).coerceAtMost(1f)
        } else {
            currentFatigue = (currentFatigue - 0.05f).coerceAtLeast(0f)
        }
        
        Timber.d("Rhythm adapted: pressure=$pressureLevel, fatigue=$currentFatigue, reason=$reason")
    }
    
    /**
     * Get current humanization parameters
     */
    fun getCurrentParams(): HumanizationParams {
        return HumanizationParams(
            pressure = currentPressure,
            fatigue = currentFatigue,
            rhythm = rhythmModel.getBaseRhythm(),
            precision = precisionModel.getBasePrecision()
        )
    }
    
    // Private methods
    
    private fun updatePressure(context: HumanizationContext) {
        val factors = mutableListOf<Float>()
        
        if (context.enemiesNearby > 0) factors.add(0.3f)
        if (context.healthPercent < 30) factors.add(0.5f)
        if (context.healthPercent < 50) factors.add(0.2f)
        if (context.isUnderFire) factors.add(0.4f)
        if (context.timeRemaining < 30) factors.add(0.2f)
        if (context.consecutiveMisses > 2) factors.add(0.3f)
        
        val targetPressure = factors.sum().coerceIn(0f, 1f)
        
        // Smooth transition
        currentPressure = currentPressure * 0.8f + targetPressure * 0.2f
    }
    
    private fun calculateTiming(
        baseTiming: BaseTiming,
        rhythm: RhythmParameters,
        pressure: Float,
        personality: PersonalityEngine.PersonalityProfile
    ): TimingParameters {
        // Base reaction time with variation
        val reactionVar = Random.nextFloat() * (maxReactionTimeMs - baseReactionTimeMs)
        val reactionDelay = (baseReactionTimeMs + reactionVar) * 
            (1 + rhythm.reactionTimeMultiplier) * 
            (1 + pressure * 0.3f) *  // Pressure slows reactions
            (1 - personality.reactionSpeed * 0.2f)  // Faster players react quicker
        
        // Execution speed
        val executionSpeed = rhythm.executionSpeed * (1 - pressure * 0.2f)
        
        // Pause duration
        val pauseDuration = generatePause(PauseContext(PauseType.NATURAL, pressure))
        
        return TimingParameters(
            reactionDelayMs = reactionDelay.toLong().coerceAtLeast(50),
            executionSpeedMultiplier = executionSpeed.coerceIn(0.5f, 1.5f),
            pauseDurationMs = pauseDuration,
            rhythmPattern = rhythm.pattern
        )
    }
    
    private fun calculateErrorChance(
        action: CentralOrchestrator.AIAction,
        pressure: Float,
        consecutiveErrors: Int,
        fatigue: Float
    ): ErrorParameters {
        // Base error rate
        var errorChance = 0.02f  // 2% base error rate
        
        // Increase with pressure
        errorChance += pressure * 0.1f
        
        // Increase with fatigue
        errorChance += fatigue * 0.05f
        
        // Decrease with consecutive errors (recovery mode)
        if (consecutiveErrors > 0) {
            errorChance *= (0.5f / (consecutiveErrors + 1))
        }
        
        // Action-specific adjustments
        errorChance *= when (action) {
            is CentralOrchestrator.AIAction.Aim -> 1.0f
            is CentralOrchestrator.AIAction.Fire -> 0.8f
            is CentralOrchestrator.AIAction.Move -> 0.3f
            else -> 0.5f
        }
        
        val willError = Random.nextFloat() < errorChance.coerceAtMost(0.3f)
        
        return ErrorParameters(
            willError = willError,
            errorType = if (willError) selectErrorType(action, pressure) else null,
            errorMagnitude = Random.nextFloat() * (0.5f + pressure * 0.5f)
        )
    }
    
    private fun selectErrorType(
        action: CentralOrchestrator.AIAction,
        pressure: Float
    ): ErrorType {
        val options = when (action) {
            is CentralOrchestrator.AIAction.Aim -> listOf(
                ErrorType.AIM_JITTER to 0.4f,
                ErrorType.AIM_OVERSHOOT to 0.3f,
                ErrorType.AIM_LAG to 0.3f
            )
            is CentralOrchestrator.AIAction.Fire -> listOf(
                ErrorType.TIMING_EARLY to 0.3f,
                ErrorType.TIMING_LATE to 0.4f,
                ErrorType.RECOIL_MISMANAGE to 0.3f
            )
            is CentralOrchestrator.AIAction.Move -> listOf(
                ErrorType.MOVE_OVERSHOOT to 0.5f,
                ErrorType.MOVE_PAUSE to 0.5f
            )
            else -> listOf(ErrorType.TIMING_LATE to 1f)
        }
        
        val random = Random.nextFloat()
        var cumulative = 0f
        
        for ((type, probability) in options) {
            cumulative += probability
            if (random <= cumulative) return type
        }
        
        return ErrorType.TIMING_LATE
    }
    
    private fun humanizeMove(
        action: CentralOrchestrator.AIAction.Move,
        timing: TimingParameters,
        precision: PrecisionParameters
    ): HumanizedAction {
        return HumanizedAction.Move(
            direction = action.direction,
            speed = action.speed,
            timing = timing,
            pathDeviation = Random.nextFloat() * precision.pathDeviation * 10f
        )
    }
    
    private fun humanizeAim(
        action: CentralOrchestrator.AIAction.Aim,
        timing: TimingParameters,
        precision: PrecisionParameters,
        error: ErrorParameters
    ): HumanizedAction {
        val targetX = action.target.x + 
            (Random.nextFloat() - 0.5f) * precision.aimJitter * (1 - precision.precisionModifier)
        val targetY = action.target.y + 
            (Random.nextFloat() - 0.5f) * precision.aimJitter * (1 - precision.precisionModifier)
        
        return HumanizedAction.Aim(
            target = CentralOrchestrator.Vector2(targetX, targetY),
            predictionOffset = action.predictionOffset,
            timing = timing,
            precision = precision,
            willError = error.willError,
            errorType = error.errorType
        )
    }
    
    private fun humanizeFire(
        action: CentralOrchestrator.AIAction.Fire,
        timing: TimingParameters,
        rhythm: RhythmParameters,
        error: ErrorParameters
    ): HumanizedAction {
        val burstVariation = (Random.nextFloat() - 0.5f) * rhythm.burstVariation
        
        return HumanizedAction.Fire(
            mode = action.mode,
            baseDuration = action.durationMs,
            adjustedDuration = (action.durationMs * (1 + burstVariation)).toLong(),
            timing = timing,
            rhythm = rhythm,
            willError = error.willError,
            errorType = error.errorType
        )
    }
    
    private fun humanizeAbility(
        action: CentralOrchestrator.AIAction.UseAbility,
        timing: TimingParameters
    ): HumanizedAction {
        return HumanizedAction.Ability(
            ability = action.ability,
            timing = timing
        )
    }
    
    private fun humanizeCompound(
        action: CentralOrchestrator.AIAction.Compound,
        timing: TimingParameters,
        rhythm: RhythmParameters
    ): HumanizedAction {
        val adjustedDelays = action.delays.map { delay ->
            (delay * (1 + (Random.nextFloat() - 0.5f) * rhythm.sequenceVariation)).toLong()
        }
        
        return HumanizedAction.Compound(
            actions = action.actions,
            baseDelays = action.delays,
            adjustedDelays = adjustedDelays,
            timing = timing,
            rhythm = rhythm
        )
    }
    
    private fun getBaseTiming(action: CentralOrchestrator.AIAction): BaseTiming {
        return when (action) {
            is CentralOrchestrator.AIAction.Move -> BaseTiming(200f, 0.5f)
            is CentralOrchestrator.AIAction.Aim -> BaseTiming(150f, 0.8f)
            is CentralOrchestrator.AIAction.Fire -> BaseTiming(100f, 0.3f)
            else -> BaseTiming(200f, 0.5f)
        }
    }
    
    // Data classes
    
    sealed class HumanizedAction {
        data class Base(
            val original: CentralOrchestrator.AIAction,
            val timing: TimingParameters
        ) : HumanizedAction()
        
        data class Move(
            val direction: CentralOrchestrator.Vector2,
            val speed: CentralOrchestrator.AIAction.MovementSpeed,
            val timing: TimingParameters,
            val pathDeviation: Float
        ) : HumanizedAction()
        
        data class Aim(
            val target: CentralOrchestrator.Vector2,
            val predictionOffset: CentralOrchestrator.Vector2,
            val timing: TimingParameters,
            val precision: PrecisionParameters,
            val willError: Boolean,
            val errorType: ErrorType?
        ) : HumanizedAction()
        
        data class Fire(
            val mode: CentralOrchestrator.AIAction.FireMode,
            val baseDuration: Long,
            val adjustedDuration: Long,
            val timing: TimingParameters,
            val rhythm: RhythmParameters,
            val willError: Boolean,
            val errorType: ErrorType?
        ) : HumanizedAction()
        
        data class Ability(
            val ability: CentralOrchestrator.AIAction.AbilityType,
            val timing: TimingParameters
        ) : HumanizedAction()
        
        data class Compound(
            val actions: List<CentralOrchestrator.AIAction>,
            val baseDelays: List<Long>,
            val adjustedDelays: List<Long>,
            val timing: TimingParameters,
            val rhythm: RhythmParameters
        ) : HumanizedAction()
    }
    
    data class HumanizationContext(
        val enemiesNearby: Int,
        val healthPercent: Int,
        val isUnderFire: Boolean,
        val timeRemaining: Int,
        val consecutiveMisses: Int,
        val currentStreak: Int
    )
    
    data class TimingParameters(
        val reactionDelayMs: Long,
        val executionSpeedMultiplier: Float,
        val pauseDurationMs: Long,
        val rhythmPattern: RhythmPattern
    )
    
    data class PrecisionParameters(
        val precisionModifier: Float,
        val aimJitter: Float,
        val pathDeviation: Float
    )
    
    data class RhythmParameters(
        val reactionTimeMultiplier: Float,
        val executionSpeed: Float,
        val burstVariation: Float,
        val sequenceVariation: Float,
        val pattern: RhythmPattern
    )
    
    data class ErrorParameters(
        val willError: Boolean,
        val errorType: ErrorType?,
        val errorMagnitude: Float
    )
    
    data class RecoveryPlan(
        val recoveryTimeMs: Long,
        val compensationAmount: Float,
        val correctionGesture: String,
        val conservativeFallback: Boolean
    )
    
    data class PauseContext(
        val type: PauseType,
        val pressure: Float
    )
    
    data class RecoveryContext(
        val actionType: String,
        val errorCount: Int,
        val pressureLevel: Float
    )
    
    data class HumanizationParams(
        val pressure: Float,
        val fatigue: Float,
        val rhythm: RhythmPattern,
        val precision: Float
    )
    
    data class BaseTiming(
        val reactionTimeMs: Float,
        val executionComplexity: Float
    )
    
    enum class PauseType {
        THINKING, REACTION, STRATEGIC, RECOVERY, NATURAL
    }
    
    enum class RhythmPattern {
        STEADY, AGGRESSIVE, CAUTIOUS, PANIC, FATIGUED
    }
    
    enum class ErrorType {
        AIM_JITTER, AIM_OVERSHOOT, AIM_LAG,
        TIMING_EARLY, TIMING_LATE,
        MOVE_OVERSHOOT, MOVE_PAUSE,
        RECOIL_MISMANAGE, MISCLICK
    }
    
    // Inner model classes
    
    private inner class RhythmModel {
        fun getParameters(
            pressure: Float,
            fatigue: Float,
            personality: PersonalityEngine.PersonalityProfile
        ): RhythmParameters {
            val baseRhythm = when {
                pressure > 0.8f -> 1.2f  // Faster under pressure
                fatigue > 0.7f -> 0.7f   // Slower when tired
                personality.aggression > 0.7f -> 1.1f
                personality.caution > 0.7f -> 0.9f
                else -> 1.0f
            }
            
            return RhythmParameters(
                reactionTimeMultiplier = (0.8f + Random.nextFloat() * 0.4f) * baseRhythm,
                executionSpeed = baseRhythm,
                burstVariation = 0.1f + pressure * 0.2f,
                sequenceVariation = 0.15f,
                pattern = when {
                    pressure > 0.8f -> RhythmPattern.PANIC
                    fatigue > 0.7f -> RhythmPattern.FATIGUED
                    personality.aggression > 0.7f -> RhythmPattern.AGGRESSIVE
                    personality.caution > 0.7f -> RhythmPattern.CAUTIOUS
                    else -> RhythmPattern.STEADY
                }
            )
        }
        
        fun getBaseRhythm(): RhythmPattern = RhythmPattern.STEADY
    }
    
    private inner class ErrorRecoveryModel {
        fun getRecoveryStrategy(error: ErrorType): RecoveryPlan {
            return when (error) {
                ErrorType.AIM_OVERSHOOT -> RecoveryPlan(
                    recoveryTimeMs = 200,
                    compensationAmount = 0.3f,
                    correctionGesture = "micro_adjust_back",
                    conservativeFallback = false
                )
                ErrorType.TIMING_LATE -> RecoveryPlan(
                    recoveryTimeMs = 100,
                    compensationAmount = 0.5f,
                    correctionGesture = "accelerate_next",
                    conservativeFallback = true
                )
                else -> RecoveryPlan(
                    recoveryTimeMs = 300,
                    compensationAmount = 0.2f,
                    correctionGesture = "pause_reassess",
                    conservativeFallback = true
                )
            }
        }
    }
    
    private inner class PressureResponseModel {
        // Pressure response logic
    }
    
    private inner class PrecisionModel {
        fun getParameters(
            action: CentralOrchestrator.AIAction,
            pressure: Float,
            fatigue: Float,
            skillLevel: Float
        ): PrecisionParameters {
            val basePrecision = skillLevel * (1 - fatigue * 0.3f) * (1 - pressure * 0.2f)
            
            return PrecisionParameters(
                precisionModifier = basePrecision.coerceIn(0.3f, 1f),
                aimJitter = 5f + pressure * 10f + fatigue * 5f,
                pathDeviation = 0.05f + fatigue * 0.1f
            )
        }
        
        fun getBasePrecision(): Float = 0.7f
    }
}
