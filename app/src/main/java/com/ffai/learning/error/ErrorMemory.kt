package com.ffai.learning.error

import com.ffai.learning.adaptive.AdaptiveLearningEngine
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Error Memory - Stores and learns from failures
 * Tracks frequent errors, patterns that cause defeat, and bad decision situations
 */
class ErrorMemory {
    
    // Error storage
    private val errorLog = mutableListOf<ErrorRecord>()
    private val errorPatterns = ConcurrentHashMap<String, ErrorPattern>()
    private val errorHotspots = mutableMapOf<String, Int>()
    private val decisionFailureMap = ConcurrentHashMap<String, DecisionFailureProfile>()
    
    // Configuration
    private val maxErrorLogSize = 100
    private val minOccurrencesForPattern = 3
    private val errorDecayRate = 0.95f  // Errors lose relevance over time
    
    /**
     * Record a new error/failure
     */
    fun recordError(
        action: AdaptiveLearningEngine.ExecutedAction,
        context: AdaptiveLearningEngine.ErrorContext,
        failureType: FailureType,
        severity: ErrorSeverity
    ) {
        val record = ErrorRecord(
            timestamp = System.currentTimeMillis(),
            action = action,
            context = context,
            failureType = failureType,
            severity = severity,
            situationHash = hashSituation(context.situation),
            learnable = isLearnable(failureType, severity)
        )
        
        synchronized(errorLog) {
            if (errorLog.size >= maxErrorLogSize) {
                errorLog.removeAt(0)
            }
            errorLog.add(record)
        }
        
        // Update error patterns
        if (record.learnable) {
            updateErrorPattern(record)
            updateDecisionFailureProfile(record)
        }
        
        // Track error hotspots
        val positionKey = "${context.situation.mapPosition.first.toInt()},${context.situation.mapPosition.second.toInt()}"
        errorHotspots[positionKey] = errorHotspots.getOrDefault(positionKey, 0) + 1
        
        Timber.d("Error recorded: $failureType at severity $severity")
    }
    
    /**
     * Check if current situation matches known error patterns
     */
    fun checkForDanger(situation: AdaptiveLearningEngine.SituationContext): DangerAssessment {
        val matchingPatterns = mutableListOf<ErrorPatternMatch>()
        var riskLevel = 0f
        
        errorPatterns.values.forEach { pattern ->
            val similarity = calculateSituationSimilarity(pattern.context, situation)
            if (similarity > 0.7f) {
                matchingPatterns.add(ErrorPatternMatch(
                    pattern = pattern,
                    similarity = similarity,
                    riskContribution = pattern.frequency * pattern.severity.ordinal * similarity
                ))
                riskLevel += pattern.frequency * similarity
            }
        }
        
        // Check if we're in an error hotspot
        val positionKey = "${situation.mapPosition.first.toInt()},${situation.mapPosition.second.toInt()}"
        val hotspotDanger = (errorHotspots[positionKey] ?: 0) / 10f
        
        return DangerAssessment(
            dangerLevel = min(riskLevel + hotspotDanger, 1f),
            matchingPatterns = matchingPatterns.sortedByDescending { it.riskContribution },
            isHotspot = hotspotDanger > 0.3f,
            recommendedAvoidance = generateAvoidanceStrategy(matchingPatterns)
        )
    }
    
    /**
     * Get lessons from past errors for current situation
     */
    fun getLessons(situation: AdaptiveLearningEngine.SituationContext): List<ErrorLesson> {
        val relevant = errorLog.filter { record ->
            calculateSituationSimilarity(record.context.situation, situation) > 0.6f
        }
        
        return relevant.groupBy { it.failureType }
            .map { (type, records) ->
                ErrorLesson(
                    failureType = type,
                    frequency = records.size,
                    commonContext = extractCommonContext(records),
                    advice = generateAdvice(type, records),
                    confidence = min(records.size / 5f, 1f)
                )
            }
            .sortedByDescending { it.confidence }
            .take(5)
    }
    
    /**
     * Analyze personal bad habits
     */
    fun analyzeBadHabits(): List<BadHabit> {
        val habits = mutableListOf<BadHabit>()
        
        // Analyze decision failures
        decisionFailureMap.forEach { (decisionType, profile) ->
            if (profile.failureRate > 0.6f && profile.totalAttempts >= 5) {
                habits.add(BadHabit(
                    habitType = HabitType.DECISION,
                    description = "Frequently fails when $decisionType",
                    occurrenceRate = profile.failureRate,
                    severity = profile.averageSeverity,
                    suggestion = "Consider alternative to $decisionType in similar situations"
                ))
            }
        }
        
        // Analyze timing patterns
        val timingErrors = errorLog.filter { 
            it.failureType == FailureType.TIMING 
        }
        if (timingErrors.size > 5) {
            habits.add(BadHabit(
                habitType = HabitType.TIMING,
                description = "Timing issues detected",
                occurrenceRate = timingErrors.size.toFloat() / errorLog.size,
                severity = ErrorSeverity.MEDIUM,
                suggestion = "Work on reaction timing"
            ))
        }
        
        return habits.sortedByDescending { it.occurrenceRate * it.severity.ordinal }
    }
    
    /**
     * Get error-prone situations to avoid
     */
    fun getSituationsToAvoid(): List<SituationToAvoid> {
        return errorPatterns.values
            .filter { it.frequency >= minOccurrencesForPattern }
            .map { pattern ->
                SituationToAvoid(
                    contextDescription = describeContext(pattern.context),
                    dangerScore = pattern.frequency * pattern.severity.ordinal / 3f,
                    timesOccurred = pattern.occurrences.size,
                    commonResult = pattern.mostCommonOutcome,
                    alternativeAction = suggestAlternative(pattern)
                )
            }
            .sortedByDescending { it.dangerScore }
            .take(10)
    }
    
    /**
     * Get statistics
     */
    fun getStats(): ErrorStats {
        return ErrorStats(
            totalErrors = errorLog.size,
            uniquePatterns = errorPatterns.size,
            criticalErrors = errorLog.count { it.severity == ErrorSeverity.CRITICAL },
            learnableErrors = errorLog.count { it.learnable },
            mostCommonFailure = errorLog.groupingBy { it.failureType }
                .eachCount()
                .maxByOrNull { it.value }?.key,
            recentErrorRate = calculateRecentErrorRate()
        )
    }
    
    fun getErrorCount(): Int = errorLog.size
    
    /**
     * Clear old errors (selective forgetting)
     */
    fun clearOldErrors(ageThresholdMs: Long = 86400000) {
        val cutoff = System.currentTimeMillis() - ageThresholdMs
        
        synchronized(errorLog) {
            errorLog.removeIf { it.timestamp < cutoff }
        }
        
        // Decay pattern frequencies
        errorPatterns.values.forEach { pattern ->
            pattern.frequency *= errorDecayRate
        }
        
        // Remove low-frequency patterns
        errorPatterns.entries.removeIf { it.value.frequency < 0.1f }
    }
    
    // Private methods
    
    private fun updateErrorPattern(record: ErrorRecord) {
        val key = record.situationHash
        
        val existing = errorPatterns[key]
        if (existing != null) {
            errorPatterns[key] = existing.copy(
                frequency = existing.frequency + 1,
                lastOccurred = System.currentTimeMillis(),
                occurrences = existing.occurrences + record
            )
        } else {
            errorPatterns[key] = ErrorPattern(
                context = record.context.situation,
                failureType = record.failureType,
                severity = record.severity,
                frequency = 1f,
                firstOccurred = record.timestamp,
                lastOccurred = record.timestamp,
                occurrences = listOf(record)
            )
        }
    }
    
    private fun updateDecisionFailureProfile(record: ErrorRecord) {
        val decisionType = record.action.type
        val profile = decisionFailureMap.getOrPut(decisionType) {
            DecisionFailureProfile(
                decisionType = decisionType,
                totalAttempts = 0,
                failures = 0,
                averageSeverity = ErrorSeverity.LOW
            )
        }
        
        decisionFailureMap[decisionType] = profile.copy(
            totalAttempts = profile.totalAttempts + 1,
            failures = profile.failures + 1,
            averageSeverity = max(profile.averageSeverity, record.severity)
        )
    }
    
    private fun hashSituation(situation: AdaptiveLearningEngine.SituationContext): String {
        // Create a hashable representation of the situation
        return "${situation.mapId}_${situation.weaponType}_${situation.health.toInt()}_${situation.enemyCount}"
    }
    
    private fun isLearnable(failureType: FailureType, severity: ErrorSeverity): Boolean {
        return severity >= ErrorSeverity.MEDIUM || 
               failureType == FailureType.TIMING ||
               failureType == FailureType.POSITIONING
    }
    
    private fun calculateSituationSimilarity(
        ctx1: AdaptiveLearningEngine.SituationContext,
        ctx2: AdaptiveLearningEngine.SituationContext
    ): Float {
        val healthSim = 1f - kotlin.math.abs(ctx1.health - ctx2.health) / 100f
        val enemySim = 1f - kotlin.math.abs(ctx1.enemyCount - ctx2.enemyCount) / 10f
        val weaponSim = if (ctx1.weaponType == ctx2.weaponType) 1f else 0.5f
        val posDist = kotlin.math.sqrt(
            (ctx1.mapPosition.first - ctx2.mapPosition.first).pow(2) +
            (ctx1.mapPosition.second - ctx2.mapPosition.second).pow(2)
        )
        val posSim = exp(-0.001f * posDist)
        
        return (healthSim + enemySim + weaponSim + posSim) / 4f
    }
    
    private fun generateAvoidanceStrategy(patterns: List<ErrorPatternMatch>): List<String> {
        return patterns.take(3).flatMap { match ->
            when (match.pattern.failureType) {
                FailureType.POSITIONING -> listOf("reposition", "find_cover")
                FailureType.TIMING -> listOf("wait", "delay_action")
                FailureType.AIM -> listOf("adjust_aim", "get_closer")
                FailureType.DECISION -> listOf("reconsider", "fallback")
                else -> listOf("proceed_with_caution")
            }
        }.distinct()
    }
    
    private fun extractCommonContext(records: List<ErrorRecord>): String {
        // Extract common features from error records
        val weapons = records.map { it.context.situation.weaponType }.distinct()
        val healthRanges = records.map { it.context.situation.health.toInt() / 25 * 25 }
        
        return "Weapon: ${weapons.firstOrNull() ?: "various"}, Health: ${healthRanges.firstOrNull() ?: "various"}"
    }
    
    private fun generateAdvice(type: FailureType, records: List<ErrorRecord>): String {
        return when (type) {
            FailureType.POSITIONING -> "Avoid exposed positions"
            FailureType.TIMING -> "Improve reaction timing"
            FailureType.AIM -> "Practice aim under pressure"
            FailureType.DECISION -> "Consider alternative tactics"
            FailureType.AWARENESS -> "Increase situational awareness"
            else -> "Review and adapt"
        }
    }
    
    private fun describeContext(context: AdaptiveLearningEngine.SituationContext): String {
        return "${context.weaponType} with ${context.health.toInt()} HP vs ${context.enemyCount} enemies"
    }
    
    private fun suggestAlternative(pattern: ErrorPattern): String {
        return when (pattern.failureType) {
            FailureType.POSITIONING -> "Take cover before engaging"
            FailureType.TIMING -> "Wait for better opportunity"
            FailureType.AIM -> "Get closer or use different weapon"
            FailureType.DECISION -> "Retreat and reposition"
            else -> "Be more cautious"
        }
    }
    
    private fun calculateRecentErrorRate(): Float {
        val oneHourAgo = System.currentTimeMillis() - 3600000
        val recent = errorLog.count { it.timestamp > oneHourAgo }
        return if (errorLog.isNotEmpty()) recent.toFloat() / errorLog.size else 0f
    }
    
    private val DecisionFailureProfile.failureRate: Float
        get() = if (totalAttempts > 0) failures.toFloat() / totalAttempts else 0f
    
    private val ErrorPattern.mostCommonOutcome: String
        get() = occurrences.groupingBy { it.action.result.reward > 0 }
            .eachCount()
            .maxByOrNull { it.value }
            ?.let { if (it.key) "success" else "failure" }
            ?: "unknown"
    
    private fun Float.pow(exp: Float): Float = kotlin.math.pow(this, exp)
    
    // Data classes
    
    data class ErrorRecord(
        val timestamp: Long,
        val action: AdaptiveLearningEngine.ExecutedAction,
        val context: AdaptiveLearningEngine.ErrorContext,
        val failureType: FailureType,
        val severity: ErrorSeverity,
        val situationHash: String,
        val learnable: Boolean
    )
    
    data class ErrorPattern(
        val context: AdaptiveLearningEngine.SituationContext,
        val failureType: FailureType,
        val severity: ErrorSeverity,
        val frequency: Float,
        val firstOccurred: Long,
        val lastOccurred: Long,
        val occurrences: List<ErrorRecord>
    )
    
    data class ErrorPatternMatch(
        val pattern: ErrorPattern,
        val similarity: Float,
        val riskContribution: Float
    )
    
    data class DangerAssessment(
        val dangerLevel: Float,
        val matchingPatterns: List<ErrorPatternMatch>,
        val isHotspot: Boolean,
        val recommendedAvoidance: List<String>
    )
    
    data class ErrorLesson(
        val failureType: FailureType,
        val frequency: Int,
        val commonContext: String,
        val advice: String,
        val confidence: Float
    )
    
    data class DecisionFailureProfile(
        val decisionType: String,
        val totalAttempts: Int,
        val failures: Int,
        val averageSeverity: ErrorSeverity
    )
    
    data class BadHabit(
        val habitType: HabitType,
        val description: String,
        val occurrenceRate: Float,
        val severity: ErrorSeverity,
        val suggestion: String
    )
    
    data class SituationToAvoid(
        val contextDescription: String,
        val dangerScore: Float,
        val timesOccurred: Int,
        val commonResult: String,
        val alternativeAction: String
    )
    
    data class ErrorStats(
        val totalErrors: Int,
        val uniquePatterns: Int,
        val criticalErrors: Int,
        val learnableErrors: Int,
        val mostCommonFailure: FailureType?,
        val recentErrorRate: Float
    )
    
    enum class FailureType {
        POSITIONING, TIMING, AIM, DECISION, AWARENESS, MOVEMENT, 
        COMMUNICATION, RESOURCE, UNKNOWN
    }
    
    enum class ErrorSeverity { LOW, MEDIUM, HIGH, CRITICAL }
    enum class HabitType { DECISION, TIMING, POSITIONING, AWARENESS, MOVEMENT }
}
