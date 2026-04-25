package com.ffai.learning.adaptive

import android.content.Context
import com.ffai.config.DeviceProfile
import com.ffai.memory.retrieval.MemoryRetrievalEngine
import com.ffai.learning.rl.ReinforcementLearningEngine
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Adaptive Learning Engine - Combines RL, incremental learning, and pattern consolidation
 * Features: On-device training, incremental updates, pattern consolidation, self-evaluation
 */
class AdaptiveLearningEngine(
    context: Context,
    private val deviceProfile: DeviceProfile,
    private val memoryRetrieval: MemoryRetrievalEngine,
    private val rlEngine: ReinforcementLearningEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Learning state
    private val patternRepository = ConcurrentHashMap<String, LearnedPattern>()
    private val experienceBuffer = mutableListOf<RichExperience>()
    private val selfEvaluationLog = mutableListOf<SelfEvaluation>()
    private val errorMemory = ErrorMemory()
    
    // Learning parameters
    private val learningRate = if (deviceProfile.optimizationTier <= DeviceProfile.OptimizationTier.LIGHT) {
        0.001f  // Conservative for A21S
    } else 0.003f
    
    private val batchSize = if (deviceProfile.optimizationTier <= DeviceProfile.OptimizationTier.LIGHT) {
        8
    } else 16
    
    private val maxBufferSize = 500
    private val minSamplesForPattern = 5
    private val consolidationThreshold = 0.8f
    
    // Metrics
    private val totalUpdates = AtomicInteger(0)
    private val patternsLearned = AtomicInteger(0)
    private var averageReward = 0f
    private var learningStability = 1.0f
    
    init {
        startConsolidationLoop()
        startEvaluationLoop()
    }
    
    /**
     * Learn from a single experience (incremental learning)
     */
    fun learnFromExperience(experience: RichExperience) {
        // Add to buffer
        synchronized(experienceBuffer) {
            if (experienceBuffer.size >= maxBufferSize) {
                experienceBuffer.removeAt(0)  // Remove oldest
            }
            experienceBuffer.add(experience)
        }
        
        // Immediate pattern extraction
        extractPatterns(experience)
        
        // Update RL
        val rlExp = ReinforcementLearningEngine.Experience(
            state = experience.stateFeatures,
            action = experience.actionIndex,
            reward = experience.outcome.reward,
            nextState = experience.nextStateFeatures,
            done = experience.outcome.isTerminal,
            logProbability = experience.policyLogProb,
            value = experience.valueEstimate
        )
        rlEngine.storeExperience(rlExp)
        
        // Check for consecutive failures
        detectAndLearnFromErrors(experience)
        
        Timber.v("Learned from experience: action=${experience.actionType}, reward=${experience.outcome.reward}")
    }
    
    /**
     * Self-evaluation after action execution
     */
    fun evaluateAction(
        action: ExecutedAction,
        expectedOutcome: ExpectedOutcome,
        actualOutcome: ActualOutcome
    ): SelfEvaluation {
        val evaluation = SelfEvaluation(
            timestamp = System.currentTimeMillis(),
            action = action,
            expectedOutcome = expectedOutcome,
            actualOutcome = actualOutcome,
            wasGood = calculateGoodness(actualOutcome, expectedOutcome),
            wasTimely = calculateTimeliness(action),
            wasRisky = calculateRiskLevel(action, expectedOutcome),
            wasProfitable = calculateProfit(actualOutcome),
            couldBeBetter = identifyImprovements(action, actualOutcome),
            lesson = generateLesson(action, expectedOutcome, actualOutcome)
        )
        
        synchronized(selfEvaluationLog) {
            if (selfEvaluationLog.size > 100) {
                selfEvaluationLog.removeAt(0)
            }
            selfEvaluationLog.add(evaluation)
        }
        
        // Learn from evaluation
        if (!evaluation.wasGood) {
            errorMemory.recordError(
                action = action,
                context = action.context,
                failureType = determineFailureType(expectedOutcome, actualOutcome),
                severity = if (actualOutcome.isTerminal) ErrorSeverity.CRITICAL else ErrorSeverity.MEDIUM
            )
        }
        
        return evaluation
    }
    
    /**
     * Discover and consolidate patterns from recent experiences
     */
    fun consolidatePatterns(): ConsolidationResult {
        val patterns = mutableListOf<ConsolidatedPattern>()
        val startTime = System.nanoTime()
        
        // Group experiences by context similarity
        val groups = groupExperiencesByContext()
        
        groups.forEach { group ->
            if (group.size >= minSamplesForPattern) {
                val pattern = createPatternFromGroup(group)
                
                // Check if pattern already exists
                val existingKey = findSimilarPattern(pattern)
                
                if (existingKey != null) {
                    // Update existing pattern
                    updatePattern(existingKey, pattern, group)
                } else {
                    // Store new pattern
                    val key = "pattern_${System.currentTimeMillis()}_${pattern.hashCode()}"
                    patternRepository[key] = pattern
                    patterns.add(ConsolidatedPattern(
                        key = key,
                        pattern = pattern,
                        sourceExperiences = group.size,
                        confidence = pattern.confidence
                    ))
                    patternsLearned.incrementAndGet()
                }
            }
        }
        
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        
        // Validate patterns
        validatePatterns()
        
        return ConsolidationResult(
            newPatterns = patterns,
            updatedPatterns = 0,  // Would track this
            totalPatterns = patternRepository.size,
            consolidationTimeMs = elapsedMs
        )
    }
    
    /**
     * Get pattern for current context (transfer learning)
     */
    fun getPatternForContext(context: SituationContext): PatternSuggestion? {
        // Find best matching pattern
        val matches = patternRepository.map { (key, pattern) ->
            PatternMatch(
                key = key,
                pattern = pattern,
                similarity = calculateContextSimilarity(context, pattern.context),
                applicability = assessPatternApplicability(pattern, context)
            )
        }.filter { it.similarity > 0.6f && it.applicability > 0.5f }
        .sortedByDescending { it.similarity * it.applicability }
        
        val bestMatch = matches.firstOrNull() ?: return null
        
        return PatternSuggestion(
            pattern = bestMatch.pattern,
            confidence = bestMatch.similarity,
            expectedOutcome = bestMatch.pattern.typicalOutcome,
            adaptationNeeded = calculateAdaptation(bestMatch.pattern.context, context)
        )
    }
    
    /**
     * Update learning with new batch (PPO update)
     */
    suspend fun performLearningUpdate(): LearningUpdateResult {
        return withContext(Dispatchers.Default) {
            val startTime = System.nanoTime()
            
            // Get recent batch
            val batch = synchronized(experienceBuffer) {
                experienceBuffer.takeLast(batchSize)
            }
            
            if (batch.size < batchSize / 2) {
                return@withContext LearningUpdateResult(
                    updated = false,
                    policyLoss = 0f,
                    valueLoss = 0f,
                    stability = learningStability,
                    reason = "Insufficient samples"
                )
            }
            
            // Check learning stability
            if (learningStability < 0.5f) {
                // Reduce learning rate to stabilize
                Timber.w("Learning unstable, reducing learning rate")
                return@withContext LearningUpdateResult(
                    updated = false,
                    policyLoss = 0f,
                    valueLoss = 0f,
                    stability = learningStability,
                    reason = "Instability detected"
                )
            }
            
            // Perform RL update
            val rlResult = rlEngine.updatePolicy()
            
            // Update pattern confidence
            updatePatternConfidence(batch)
            
            // Calculate metrics
            val avgReward = batch.map { it.outcome.reward }.average().toFloat()
            val rewardVariance = batch.map { 
                (it.outcome.reward - avgReward).pow(2) 
            }.average().toFloat()
            
            // Update stability
            learningStability = calculateStability(rewardVariance)
            averageReward = 0.9f * averageReward + 0.1f * avgReward
            totalUpdates.incrementAndGet()
            
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            
            LearningUpdateResult(
                updated = rlResult.updated,
                policyLoss = rlResult.policyLoss,
                valueLoss = rlResult.valueLoss,
                averageReward = avgReward,
                stability = learningStability,
                updateTimeMs = elapsedMs,
                samplesUsed = batch.size
            )
        }
    }
    
    /**
     * Detect repetitive enemy patterns
     */
    fun detectEnemyPatterns(enemyId: String, recentActions: List<EnemyAction>): DetectedPattern? {
        if (recentActions.size < 3) return null
        
        // Analyze movement patterns
        val movementPattern = analyzeMovementPattern(recentActions)
        val timingPattern = analyzeTimingPattern(recentActions)
        val decisionPattern = analyzeDecisionPattern(recentActions)
        
        // Check for consistency
        val consistency = calculateConsistency(movementPattern, timingPattern, decisionPattern)
        
        if (consistency > 0.7f) {
            return DetectedPattern(
                enemyId = enemyId,
                patternType = determinePatternType(movementPattern, timingPattern),
                confidence = consistency,
                predictedNextAction = predictNextAction(recentActions, movementPattern),
                counterStrategy = suggestCounterStrategy(movementPattern, timingPattern)
            )
        }
        
        return null
    }
    
    /**
     * Learn from similar past experiences (transfer)
     */
    fun transferLearning(sourceContext: SituationContext, targetContext: SituationContext): TransferResult {
        // Find similar past experiences
        val similar = findSimilarExperiences(sourceContext)
        
        // Assess transferability
        val transferable = similar.filter { experience ->
            val sourceDist = contextDistance(experience.context, sourceContext)
            val targetDist = contextDistance(experience.context, targetContext)
            
            // Transferable if closer to target than source
            targetDist < sourceDist * 1.5f
        }
        
        // Weight by inverse distance
        val weightedExperiences = transferable.map { exp ->
            val weight = 1f / (1f + contextDistance(exp.context, targetContext))
            WeightedExperience(exp, weight)
        }
        
        // Synthesize transferred knowledge
        val transferredAction = weightedExperiences
            .groupBy { it.experience.actionType }
            .map { (action, group) ->
                action to group.sumOf { it.weight.toDouble() }.toFloat()
            }
            .maxByOrNull { it.second }
            ?.first
        
        return TransferResult(
            sourceExperiences = similar.size,
            transferableExperiences = transferable.size,
            suggestedAction = transferredAction,
            confidence = if (transferable.isNotEmpty()) {
                transferable.size.toFloat() / similar.size
            } else 0f
        )
    }
    
    /**
     * Get learning statistics
     */
    fun getStats(): LearningStats {
        return LearningStats(
            totalUpdates = totalUpdates.get(),
            patternsLearned = patternsLearned.get(),
            patternsInRepository = patternRepository.size,
            averageReward = averageReward,
            learningStability = learningStability,
            errorCount = errorMemory.getErrorCount(),
            evaluationCount = selfEvaluationLog.size,
            experienceBufferSize = experienceBuffer.size
        )
    }
    
    // Private helper methods
    
    private fun extractPatterns(experience: RichExperience) {
        // Extract situational patterns
        val contextKey = buildContextKey(experience.context)
        
        // Store for later consolidation
        // Actual pattern creation happens in consolidatePatterns()
    }
    
    private fun detectAndLearnFromErrors(experience: RichExperience) {
        // Check for negative outcomes
        if (experience.outcome.reward < -1f || experience.outcome.isTerminal) {
            // This was likely a mistake
            val errorContext = ErrorContext(
                situation = experience.context,
                actionTaken = experience.actionType,
                result = experience.outcome
            )
            
            errorMemory.recordError(
                action = ExecutedAction(
                    type = experience.actionType,
                    timestamp = experience.timestamp,
                    context = experience.context,
                    result = experience.outcome
                ),
                context = errorContext,
                failureType = if (experience.outcome.isTerminal) {
                    FailureType.FATAL
                } else {
                    FailureType.INEFFECTIVE
                },
                severity = if (experience.outcome.isTerminal) {
                    ErrorSeverity.CRITICAL
                } else {
                    ErrorSeverity.HIGH
                }
            )
        }
    }
    
    private fun groupExperiencesByContext(): List<List<RichExperience>> {
        // Group by similarity
        val groups = mutableListOf<MutableList<RichExperience>>()
        
        experienceBuffer.forEach { exp ->
            var added = false
            for (group in groups) {
                val similarity = contextSimilarity(exp.context, group.first().context)
                if (similarity > 0.8f) {
                    group.add(exp)
                    added = true
                    break
                }
            }
            if (!added) {
                groups.add(mutableListOf(exp))
            }
        }
        
        return groups
    }
    
    private fun createPatternFromGroup(group: List<RichExperience>): LearnedPattern {
        val representativeContext = group.map { it.context }
            .reduce { acc, ctx -> mergeContexts(acc, ctx) }
        
        val actionDistribution = group.groupingBy { it.actionType }
            .eachCount()
        
        val bestAction = actionDistribution.maxByOrNull { it.value }?.key ?: "unknown"
        val avgReward = group.map { it.outcome.reward }.average().toFloat()
        
        return LearnedPattern(
            context = representativeContext,
            preferredAction = bestAction,
            typicalOutcome = Outcome(
                reward = avgReward,
                isTerminal = group.any { it.outcome.isTerminal }
            ),
            confidence = min(group.size / 10f, 1f),
            sampleCount = group.size,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    private fun findSimilarPattern(pattern: LearnedPattern): String? {
        return patternRepository.entries.find { (key, existing) ->
            contextSimilarity(existing.context, pattern.context) > 0.9f
        }?.key
    }
    
    private fun updatePattern(key: String, newPattern: LearnedPattern, group: List<RichExperience>) {
        val existing = patternRepository[key] ?: return
        
        // Bayesian update of pattern
        val totalSamples = existing.sampleCount + group.size
        val updatedConfidence = (existing.confidence * existing.sampleCount + 
                              newPattern.confidence * group.size) / totalSamples
        
        patternRepository[key] = existing.copy(
            confidence = updatedConfidence,
            sampleCount = totalSamples,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    private fun validatePatterns() {
        // Remove low-confidence patterns
        val toRemove = patternRepository.entries.filter { (_, pattern) ->
            pattern.confidence < 0.3f || 
            (System.currentTimeMillis() - pattern.lastUpdated > 86400000 * 7)  // 7 days old
        }.map { it.key }
        
        toRemove.forEach { patternRepository.remove(it) }
    }
    
    private fun calculateContextSimilarity(ctx1: SituationContext, ctx2: SituationContext): Float {
        val factors = listOf(
            1f - kotlin.math.abs(ctx1.health - ctx2.health) / 100f,
            1f - kotlin.math.abs(ctx1.enemyCount - ctx2.enemyCount) / 10f,
            if (ctx1.weaponType == ctx2.weaponType) 1f else 0.5f,
            1f - kotlin.math.abs(ctx1.mapPosition.first - ctx2.mapPosition.first) / 1000f,
            1f - kotlin.math.abs(ctx1.mapPosition.second - ctx2.mapPosition.second) / 1000f
        )
        return factors.average().toFloat()
    }
    
    private fun assessPatternApplicability(pattern: LearnedPattern, context: SituationContext): Float {
        val similarity = calculateContextSimilarity(pattern.context, context)
        val successRate = if (pattern.typicalOutcome.reward > 0) 1f else 0.5f
        val recency = exp(-0.0001 * (System.currentTimeMillis() - pattern.lastUpdated).toFloat())
        
        return (similarity * 0.4f + successRate * 0.4f + recency * 0.2f)
    }
    
    private fun calculateAdaptation(patternCtx: SituationContext, currentCtx: SituationContext): AdaptationParams {
        return AdaptationParams(
            timingAdjustment = patternCtx.reactionTime - currentCtx.reactionTime,
            positionOffset = Pair(
                currentCtx.mapPosition.first - patternCtx.mapPosition.first,
                currentCtx.mapPosition.second - patternCtx.mapPosition.second
            ),
            riskAdjustment = currentCtx.riskLevel - patternCtx.riskLevel
        )
    }
    
    private fun calculateStability(rewardVariance: Float): Float {
        // Lower variance = higher stability
        return exp(-0.5f * rewardVariance)
    }
    
    private fun calculateGoodness(actual: ActualOutcome, expected: ExpectedOutcome): Boolean {
        return actual.reward >= expected.minAcceptableReward && !actual.isTerminal
    }
    
    private fun calculateTimeliness(action: ExecutedAction): Boolean {
        // Action was timely if executed within expected window
        return true  // Simplified
    }
    
    private fun calculateRiskLevel(action: ExecutedAction, expected: ExpectedOutcome): Boolean {
        return expected.probability < 0.5f || action.context.riskLevel > 0.7f
    }
    
    private fun calculateProfit(actual: ActualOutcome): Boolean {
        return actual.reward > 0
    }
    
    private fun identifyImprovements(action: ExecutedAction, actual: ActualOutcome): List<String> {
        val improvements = mutableListOf<String>()
        if (actual.reward < 0) improvements.add("better_timing")
        if (actual.isTerminal) improvements.add("avoid_situation")
        return improvements
    }
    
    private fun generateLesson(
        action: ExecutedAction,
        expected: ExpectedOutcome,
        actual: ActualOutcome
    ): String {
        return when {
            actual.reward > expected.expectedReward -> "Action exceeded expectations"
            actual.reward < 0 -> "Action failed - avoid similar"
            else -> "Action performed as expected"
        }
    }
    
    private fun determineFailureType(expected: ExpectedOutcome, actual: ActualOutcome): FailureType {
        return when {
            actual.isTerminal -> FailureType.FATAL
            actual.reward < -0.5f -> FailureType.SEVERE
            actual.reward < 0 -> FailureType.INEFFECTIVE
            else -> FailureType.MINOR
        }
    }
    
    private fun analyzeMovementPattern(actions: List<EnemyAction>): MovementPattern {
        // Analyze enemy movement patterns
        return MovementPattern.STRAIGHT  // Simplified
    }
    
    private fun analyzeTimingPattern(actions: List<EnemyAction>): TimingPattern {
        return TimingPattern.REGULAR  // Simplified
    }
    
    private fun analyzeDecisionPattern(actions: List<EnemyAction>): DecisionPattern {
        return DecisionPattern.AGGRESSIVE  // Simplified
    }
    
    private fun calculateConsistency(vararg patterns: Any): Float {
        // Check consistency across pattern types
        return 0.7f  // Placeholder
    }
    
    private fun determinePatternType(movement: MovementPattern, timing: TimingPattern): String {
        return "${movement}_${timing}".lowercase()
    }
    
    private fun predictNextAction(actions: List<EnemyAction>, pattern: MovementPattern): String {
        // Predict based on pattern continuation
        return "continue_${pattern}".lowercase()
    }
    
    private fun suggestCounterStrategy(movement: MovementPattern, timing: TimingPattern): String {
        return when {
            movement == MovementPattern.STRAIGHT && timing == TimingPattern.REGULAR -> 
                "preaim_straight"
            movement == MovementPattern.ZIGZAG -> "lead_target"
            else -> "standard_engage"
        }
    }
    
    private fun findSimilarExperiences(context: SituationContext): List<RichExperience> {
        return experienceBuffer.filter { exp ->
            contextSimilarity(exp.context, context) > 0.7f
        }
    }
    
    private fun contextDistance(ctx1: SituationContext, ctx2: SituationContext): Float {
        val healthDiff = kotlin.math.abs(ctx1.health - ctx2.health)
        val posDiff = sqrt(
            (ctx1.mapPosition.first - ctx2.mapPosition.first).pow(2) +
            (ctx1.mapPosition.second - ctx2.mapPosition.second).pow(2)
        )
        return healthDiff + posDiff / 100f
    }
    
    private fun contextSimilarity(ctx1: SituationContext, ctx2: SituationContext): Float {
        val dist = contextDistance(ctx1, ctx2)
        return exp(-0.01f * dist)
    }
    
    private fun buildContextKey(context: SituationContext): String {
        return "${context.mapId}_${context.weaponType}_${context.health.toInt()}"
    }
    
    private fun mergeContexts(ctx1: SituationContext, ctx2: SituationContext): SituationContext {
        return SituationContext(
            mapId = ctx1.mapId,
            health = (ctx1.health + ctx2.health) / 2,
            ammo = (ctx1.ammo + ctx2.ammo) / 2,
            enemyCount = (ctx1.enemyCount + ctx2.enemyCount) / 2,
            weaponType = ctx1.weaponType,
            mapPosition = Pair(
                (ctx1.mapPosition.first + ctx2.mapPosition.first) / 2,
                (ctx1.mapPosition.second + ctx2.mapPosition.second) / 2
            ),
            riskLevel = (ctx1.riskLevel + ctx2.riskLevel) / 2,
            inCover = ctx1.inCover || ctx2.inCover,
            reactionTime = (ctx1.reactionTime + ctx2.reactionTime) / 2
        )
    }
    
    private fun startConsolidationLoop() {
        scope.launch {
            while (isActive) {
                delay(30000)  // Every 30 seconds
                consolidatePatterns()
            }
        }
    }
    
    private fun startEvaluationLoop() {
        scope.launch {
            while (isActive) {
                delay(5000)  // Every 5 seconds
                
                // Periodic learning update
                if (experienceBuffer.size >= batchSize / 2) {
                    performLearningUpdate()
                }
            }
        }
    }
    
    private fun updatePatternConfidence(batch: List<RichExperience>) {
        // Update pattern confidence based on new outcomes
        batch.forEach { exp ->
            val key = buildContextKey(exp.context)
            patternRepository[key]?.let { pattern ->
                val newConfidence = pattern.confidence * 0.95f + 
                    (if (exp.outcome.reward > 0) 0.05f else 0f)
                patternRepository[key] = pattern.copy(
                    confidence = newConfidence,
                    lastUpdated = System.currentTimeMillis()
                )
            }
        }
    }
    
    private fun Float.pow(exp: Int): Float = kotlin.math.pow(this, exp.toFloat())
    
    // Data classes
    
    data class RichExperience(
        val timestamp: Long,
        val context: SituationContext,
        val actionType: String,
        val actionIndex: Int,
        val stateFeatures: FloatArray,
        val nextStateFeatures: FloatArray?,
        val outcome: Outcome,
        val policyLogProb: Float,
        val valueEstimate: Float
    )
    
    data class SituationContext(
        val mapId: String,
        val health: Float,
        val ammo: Int,
        val enemyCount: Int,
        val weaponType: String,
        val mapPosition: Pair<Float, Float>,
        val riskLevel: Float,
        val inCover: Boolean,
        val reactionTime: Float
    )
    
    data class Outcome(
        val reward: Float,
        val isTerminal: Boolean
    )
    
    data class ExecutedAction(
        val type: String,
        val timestamp: Long,
        val context: ErrorContext,
        val result: Outcome
    )
    
    data class ErrorContext(
        val situation: SituationContext,
        val actionTaken: String,
        val result: Outcome
    )
    
    data class ExpectedOutcome(
        val expectedReward: Float,
        val minAcceptableReward: Float,
        val probability: Float
    )
    
    data class ActualOutcome(
        val reward: Float,
        val isTerminal: Boolean,
        val details: String = ""
    )
    
    data class SelfEvaluation(
        val timestamp: Long,
        val action: ExecutedAction,
        val expectedOutcome: ExpectedOutcome,
        val actualOutcome: ActualOutcome,
        val wasGood: Boolean,
        val wasTimely: Boolean,
        val wasRisky: Boolean,
        val wasProfitable: Boolean,
        val couldBeBetter: List<String>,
        val lesson: String
    )
    
    data class LearnedPattern(
        val context: SituationContext,
        val preferredAction: String,
        val typicalOutcome: Outcome,
        val confidence: Float,
        val sampleCount: Int,
        val lastUpdated: Long
    )
    
    data class PatternSuggestion(
        val pattern: LearnedPattern,
        val confidence: Float,
        val expectedOutcome: Outcome,
        val adaptationNeeded: AdaptationParams
    )
    
    data class AdaptationParams(
        val timingAdjustment: Float,
        val positionOffset: Pair<Float, Float>,
        val riskAdjustment: Float
    )
    
    data class ConsolidationResult(
        val newPatterns: List<ConsolidatedPattern>,
        val updatedPatterns: Int,
        val totalPatterns: Int,
        val consolidationTimeMs: Long
    )
    
    data class ConsolidatedPattern(
        val key: String,
        val pattern: LearnedPattern,
        val sourceExperiences: Int,
        val confidence: Float
    )
    
    data class LearningUpdateResult(
        val updated: Boolean,
        val policyLoss: Float,
        val valueLoss: Float,
        val averageReward: Float = 0f,
        val stability: Float,
        val updateTimeMs: Long = 0,
        val samplesUsed: Int = 0,
        val reason: String = ""
    )
    
    data class DetectedPattern(
        val enemyId: String,
        val patternType: String,
        val confidence: Float,
        val predictedNextAction: String,
        val counterStrategy: String
    )
    
    data class TransferResult(
        val sourceExperiences: Int,
        val transferableExperiences: Int,
        val suggestedAction: String?,
        val confidence: Float
    )
    
    data class LearningStats(
        val totalUpdates: Int,
        val patternsLearned: Int,
        val patternsInRepository: Int,
        val averageReward: Float,
        val learningStability: Float,
        val errorCount: Int,
        val evaluationCount: Int,
        val experienceBufferSize: Int
    )
    
    data class PatternMatch(
        val key: String,
        val pattern: LearnedPattern,
        val similarity: Float,
        val applicability: Float
    )
    
    data class WeightedExperience(
        val experience: RichExperience,
        val weight: Float
    )
    
    enum class MovementPattern { STRAIGHT, ZIGZAG, CIRCLE, UNPREDICTABLE }
    enum class TimingPattern { REGULAR, IRREGULAR, BURST, PAUSE }
    enum class DecisionPattern { AGGRESSIVE, DEFENSIVE, CAMPER, RUSHER }
    enum class FailureType { MINOR, INEFFECTIVE, SEVERE, FATAL }
    enum class ErrorSeverity { LOW, MEDIUM, HIGH, CRITICAL }
}
