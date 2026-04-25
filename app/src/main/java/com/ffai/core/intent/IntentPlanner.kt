package com.ffai.core.intent

import com.ffai.memory.retrieval.MemoryRetrievalEngine
import com.ffai.perception.ScreenAnalyzer
import com.ffai.personality.PersonalityEngine
import com.ffai.tactics.TacticType
import timber.log.Timber
import java.util.concurrent.PriorityBlockingQueue

/**
 * Intent Planner - Determines high-level tactical intentions
 * Separates strategic planning from execution details
 */
class IntentPlanner(
    private val personalityEngine: PersonalityEngine,
    private val memoryRetrieval: MemoryRetrievalEngine
) {
    private val missionStack = mutableListOf<Mission>()
    private val currentIntent = mutableMapOf<IntentDimension, IntentValue>()
    private val intentHistory = mutableListOf<TimedIntent>()
    
    /**
     * Determine current tactical intent based on game state
     */
    fun determineIntent(context: GameContext): TacticalIntent {
        // Retrieve relevant memories for context
        val similarSituations = memoryRetrieval.findSimilarSituations(5)
        val errorCheck = memoryRetrieval.getPrioritizedMemories(
            MemoryRetrievalEngine.CurrentContext(
                mapId = context.mapId,
                health = context.playerState.health.toFloat(),
                ammo = context.playerState.ammoCount,
                enemiesNearby = context.enemies.size,
                weaponType = context.playerState.currentWeapon,
                timestamp = System.currentTimeMillis()
            )
        )
        
        // Calculate intent scores for each dimension
        val intents = calculateIntents(context, similarSituations)
        
        // Apply personality modifiers
        val personality = personalityEngine.getCurrentPersonality()
        val adjustedIntents = applyPersonality(intents, personality)
        
        // Resolve conflicts between competing intents
        val resolvedIntent = resolveIntentConflicts(adjustedIntents, context)
        
        // Update current intent
        currentIntent.clear()
        currentIntent.putAll(resolvedIntent.dimensions)
        
        // Record in history
        recordIntent(resolvedIntent)
        
        return resolvedIntent
    }
    
    /**
     * Push a mission onto the stack
     */
    fun pushMission(mission: Mission) {
        missionStack.add(mission)
        Timber.i("Mission pushed: ${mission.type} - ${mission.description}")
    }
    
    /**
     * Pop current mission
     */
    fun popMission(): Mission? {
        return if (missionStack.isNotEmpty()) {
            missionStack.removeAt(missionStack.size - 1).also {
                Timber.i("Mission completed/popped: ${it.type}")
            }
        } else null
    }
    
    /**
     * Get current active mission
     */
    fun getCurrentMission(): Mission? = missionStack.lastOrNull()
    
    /**
     * Check if current intent aligns with mission objectives
     */
    fun validateAgainstMission(intent: TacticalIntent): ValidationResult {
        val mission = getCurrentMission() ?: return ValidationResult.VALID
        
        // Check alignment
        val aligned = when (mission.type) {
            MissionType.SURVIVE -> intent.primaryGoal != IntentType.AGGRESSIVE_PUSH
            MissionType.ELIMINATE -> intent.primaryGoal == IntentType.ENGAGE ||
                                    intent.primaryGoal == IntentType.FLANK
            MissionType.LOOT -> intent.primaryGoal == IntentType.SEARCH ||
                               intent.primaryGoal == IntentType.SECURE_AREA
            MissionType.ROTATE -> intent.primaryGoal == IntentType.MOVE ||
                                 intent.primaryGoal == IntentType.REPOSITION
            MissionType.CLUTCH -> intent.urgency == IntentUrgency.CRITICAL
        }
        
        return if (aligned) {
            ValidationResult.VALID
        } else {
            ValidationResult(
                valid = false,
                reason = "Intent ${intent.primaryGoal} conflicts with mission ${mission.type}",
                suggestedCorrection = suggestCorrectedIntent(mission)
            )
        }
    }
    
    /**
     * Update intent based on changing conditions
     */
    fun adaptIntent(newContext: GameContext): IntentAdaptation {
        val oldIntent = currentIntent.toMap()
        val newIntent = determineIntent(newContext)
        
        val changes = mutableListOf<IntentChange>()
        
        IntentDimension.values().forEach { dim ->
            val oldVal = oldIntent[dim]?.value ?: 0f
            val newVal = newIntent.dimensions[dim]?.value ?: 0f
            
            if (kotlin.math.abs(newVal - oldVal) > 0.2f) {
                changes.add(IntentChange(
                    dimension = dim,
                    oldValue = oldVal,
                    newValue = newVal,
                    reason = inferChangeReason(dim, newContext)
                ))
            }
        }
        
        return IntentAdaptation(
            previousIntent = oldIntent,
            newIntent = newIntent.dimensions,
            changes = changes,
            adaptationTrigger = detectTrigger(newContext, changes)
        )
    }
    
    /**
     * Get intent statistics
     */
    fun getStats(): IntentStats {
        val recent = intentHistory.takeLast(100)
        
        return IntentStats(
            totalIntentsPlanned = intentHistory.size,
            missionStackDepth = missionStack.size,
            currentIntentDimensions = currentIntent.size,
            mostCommonGoal = recent.groupingBy { it.intent.primaryGoal }
                .eachCount()
                .maxByOrNull { it.value }?.key,
            averageUrgency = recent.map { it.intent.urgency.ordinal }.average().toFloat(),
            intentChangeRate = calculateChangeRate(recent)
        )
    }
    
    // Private methods
    
    private fun calculateIntents(
        context: GameContext,
        memories: List<MemoryRetrievalEngine.SituationMatch>
    ): Map<IntentDimension, IntentValue> {
        val intents = mutableMapOf<IntentDimension, IntentValue>()
        
        // Combat intent
        val combatScore = when {
            context.enemies.isEmpty() -> 0f
            context.playerState.health < 30 -> 0.3f  // Low health = defensive
            context.enemies.any { it.distance < 10 } -> 0.9f  // Close enemy
            context.enemies.any { it.threatLevel > 0.7f } -> 0.8f
            else -> 0.5f
        }
        intents[IntentDimension.COMBAT] = IntentValue(combatScore, IntentType.ENGAGE)
        
        // Survival intent
        val survivalScore = when {
            context.playerState.health < 20 -> 1f
            context.playerState.health < 50 -> 0.7f
            !context.safeZone.isInside -> 0.6f
            context.enemies.size > 2 -> 0.5f
            else -> 0.2f
        }
        intents[IntentDimension.SURVIVAL] = IntentValue(survivalScore, IntentType.SURVIVE)
        
        // Resource intent
        val resourceScore = when {
            context.playerState.ammoCount == 0 -> 0.9f
            context.playerState.ammoCount < 10 -> 0.6f
            context.loot.isNotEmpty() && context.enemies.isEmpty() -> 0.4f
            else -> 0.1f
        }
        intents[IntentDimension.RESOURCE] = IntentValue(resourceScore, IntentType.SEARCH)
        
        // Position intent
        val positionScore = when {
            !context.safeZone.isInside -> 0.8f
            !context.playerState.inCover && context.enemies.isNotEmpty() -> 0.7f
            context.enemies.any { it.isMovingTowards } -> 0.6f
            else -> 0.3f
        }
        intents[IntentDimension.POSITION] = IntentValue(positionScore, IntentType.REPOSITION)
        
        // Learn from memories
        memories.forEach { memory ->
            if (memory.similarityScore > 0.8f) {
                // Adjust based on past similar situations
                val profile = memory.profile
                if (profile.averageAggression > 0.7f) {
                    intents[IntentDimension.COMBAT] = intents[IntentDimension.COMBAT]!!
                        .copy(value = intents[IntentDimension.COMBAT]!!.value * 1.1f)
                }
            }
        }
        
        return intents
    }
    
    private fun applyPersonality(
        intents: Map<IntentDimension, IntentValue>,
        personality: PersonalityEngine.PersonalityProfile
    ): Map<IntentDimension, IntentValue> {
        return intents.mapValues { (dim, value) ->
            val modifier = when (dim) {
                IntentDimension.COMBAT -> personality.aggression
                IntentDimension.SURVIVAL -> personality.caution
                IntentDimension.RESOURCE -> personality.greed
                IntentDimension.POSITION -> if (personality.caution > 0.5f) 0.9f else 0.5f
            }
            value.copy(value = (value.value * 0.7f + modifier * 0.3f).coerceIn(0f, 1f))
        }
    }
    
    private fun resolveIntentConflicts(
        intents: Map<IntentDimension, IntentValue>,
        context: GameContext
    ): TacticalIntent {
        // Sort by priority/value
        val sorted = intents.entries.sortedByDescending { it.value.value }
        
        // Determine primary goal
        val primary = sorted.first()
        
        // Check for conflicts
        val conflicts = mutableListOf<IntentConflict>()
        
        // Survival vs Combat
        if (intents[IntentDimension.SURVIVAL]?.value!! > 0.7f &&
            intents[IntentDimension.COMBAT]?.value!! > 0.5f) {
            conflicts.add(IntentConflict(
                between = Pair(IntentDimension.SURVIVAL, IntentDimension.COMBAT),
                resolution = if (context.playerState.health < 30) "SURVIVAL_WINS" else "COMBAT_WINS",
                reason = "Health at ${context.playerState.health}"
            ))
        }
        
        // Determine urgency
        val urgency = when {
            context.playerState.health < 20 -> IntentUrgency.CRITICAL
            context.enemies.any { it.distance < 5 } -> IntentUrgency.HIGH
            !context.safeZone.isInside && context.safeZone.timeToShrink < 30 -> IntentUrgency.HIGH
            else -> IntentUrgency.NORMAL
        }
        
        return TacticalIntent(
            primaryGoal = primary.value.type,
            secondaryGoals = sorted.drop(1).take(2).map { it.value.type },
            dimensions = intents,
            urgency = urgency,
            expectedDurationMs = estimateDuration(primary.value.type),
            conflicts = conflicts,
            confidence = primary.value.value
        )
    }
    
    private fun recordIntent(intent: TacticalIntent) {
        intentHistory.add(TimedIntent(
            timestamp = System.currentTimeMillis(),
            intent = intent
        ))
        
        // Limit history size
        if (intentHistory.size > 1000) {
            intentHistory.removeAt(0)
        }
    }
    
    private fun suggestCorrectedIntent(mission: Mission): IntentType {
        return when (mission.type) {
            MissionType.SURVIVE -> IntentType.SURVIVE
            MissionType.ELIMINATE -> IntentType.ENGAGE
            MissionType.LOOT -> IntentType.SEARCH
            MissionType.ROTATE -> IntentType.REPOSITION
            MissionType.CLUTCH -> IntentType.SURVIVE
        }
    }
    
    private fun inferChangeReason(dim: IntentDimension, context: GameContext): String {
        return when (dim) {
            IntentDimension.COMBAT -> "Enemy presence changed: ${context.enemies.size} enemies"
            IntentDimension.SURVIVAL -> "Health changed to ${context.playerState.health}"
            IntentDimension.RESOURCE -> "Ammo status: ${context.playerState.ammoCount}"
            IntentDimension.POSITION -> "Safe zone status changed"
        }
    }
    
    private fun detectTrigger(context: GameContext, changes: List<IntentChange>): String {
        return when {
            context.playerState.health < 30 -> "CRITICAL_HEALTH"
            context.enemies.any { it.distance < 10 } -> "ENEMY_CLOSE"
            !context.safeZone.isInside -> "OUTSIDE_ZONE"
            changes.size > 2 -> "MULTIPLE_CHANGES"
            else -> "GRADUAL_CHANGE"
        }
    }
    
    private fun calculateChangeRate(recent: List<TimedIntent>): Float {
        if (recent.size < 2) return 0f
        
        val changes = recent.zipWithNext().count { (a, b) ->
            a.intent.primaryGoal != b.intent.primaryGoal
        }
        
        return changes.toFloat() / recent.size
    }
    
    private fun estimateDuration(intentType: IntentType): Long {
        return when (intentType) {
            IntentType.ENGAGE -> 3000L      // 3 seconds typical engagement
            IntentType.FLANK -> 5000L       // 5 seconds to flank
            IntentType.REPOSITION -> 2000L // 2 seconds to reposition
            IntentType.SEARCH -> 10000L    // 10 seconds to loot
            IntentType.SURVIVE -> 0L       // Continuous
            else -> 1000L
        }
    }
    
    // Data classes
    
    data class TacticalIntent(
        val primaryGoal: IntentType,
        val secondaryGoals: List<IntentType>,
        val dimensions: Map<IntentDimension, IntentValue>,
        val urgency: IntentUrgency,
        val expectedDurationMs: Long,
        val conflicts: List<IntentConflict>,
        val confidence: Float
    )
    
    data class IntentValue(
        val value: Float,
        val type: IntentType
    )
    
    data class Mission(
        val type: MissionType,
        val description: String,
        val priority: MissionPriority,
        val deadline: Long? = null,
        val successCriteria: () -> Boolean = { false }
    )
    
    data class IntentChange(
        val dimension: IntentDimension,
        val oldValue: Float,
        val newValue: Float,
        val reason: String
    )
    
    data class IntentAdaptation(
        val previousIntent: Map<IntentDimension, IntentValue>,
        val newIntent: Map<IntentDimension, IntentValue>,
        val changes: List<IntentChange>,
        val adaptationTrigger: String
    )
    
    data class ValidationResult(
        val valid: Boolean,
        val reason: String = "",
        val suggestedCorrection: IntentType? = null
    ) {
        companion object {
            val VALID = ValidationResult(true)
        }
    }
    
    data class IntentConflict(
        val between: Pair<IntentDimension, IntentDimension>,
        val resolution: String,
        val reason: String
    )
    
    data class TimedIntent(
        val timestamp: Long,
        val intent: TacticalIntent
    )
    
    data class IntentStats(
        val totalIntentsPlanned: Int,
        val missionStackDepth: Int,
        val currentIntentDimensions: Int,
        val mostCommonGoal: IntentType?,
        val averageUrgency: Float,
        val intentChangeRate: Float
    )
    
    data class GameContext(
        val mapId: String,
        val playerState: ScreenAnalyzer.PlayerState,
        val enemies: List<ScreenAnalyzer.Enemy>,
        val loot: List<ScreenAnalyzer.LootItem>,
        val safeZone: ScreenAnalyzer.SafeZone,
        val allies: List<ScreenAnalyzer.Ally>,
        val cover: List<ScreenAnalyzer.Cover>
    )
    
    enum class IntentDimension {
        COMBAT, SURVIVAL, RESOURCE, POSITION, INFORMATION, ECONOMY
    }
    
    enum class IntentType {
        ENGAGE, FLANK, SURVIVE, SEARCH, REPOSITION, HOLD, 
        RETREAT, PUSH, SECURE_AREA, GATHER_INTEL
    }
    
    enum class IntentUrgency {
        CRITICAL, HIGH, NORMAL, LOW, BACKGROUND
    }
    
    enum class MissionType {
        SURVIVE, ELIMINATE, LOOT, ROTATE, CLUTCH
    }
    
    enum class MissionPriority {
        CRITICAL, HIGH, NORMAL, LOW
    }
}
