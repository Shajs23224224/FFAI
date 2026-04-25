package com.ffai.core.policy

import com.ffai.core.intent.IntentPlanner
import com.ffai.memory.retrieval.MemoryRetrievalEngine
import com.ffai.learning.adaptive.AdaptiveLearningEngine
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln

/**
 * Policy Selector - Selects specific actions based on tactical intent
 * Separates policy selection from intent planning and execution
 */
class PolicySelector(
    private val intentPlanner: IntentPlanner,
    private val memoryRetrieval: MemoryRetrievalEngine,
    private val adaptiveLearning: AdaptiveLearningEngine
) {
    // Available policies
    private val policies = ConcurrentHashMap<String, TacticalPolicy>()
    
    // Policy selection history
    private val selectionHistory = mutableListOf<PolicySelection>()
    
    // Current selection
    private var currentPolicy: TacticalPolicy? = null
    
    init {
        initializeDefaultPolicies()
    }
    
    /**
     * Select best policy for current intent and context
     */
    fun selectPolicy(
        intent: IntentPlanner.TacticalIntent,
        context: SelectionContext
    ): PolicySelection {
        val candidates = getCandidatePolicies(intent)
        
        if (candidates.isEmpty()) {
            Timber.w("No candidates for intent: ${intent.primaryGoal}")
            return PolicySelection.FALLBACK
        }
        
        // Score each candidate
        val scored = candidates.map { policy ->
            val score = calculatePolicyScore(policy, intent, context)
            ScoredPolicy(policy, score)
        }.sortedByDescending { it.score }
        
        // Get best candidate
        val best = scored.first()
        
        // Validate against safety rules
        val validated = validatePolicy(best.policy, context)
        
        if (!validated.valid) {
            Timber.w("Policy ${best.policy.name} failed validation: ${validated.reason}")
            // Try next best
            val alternative = scored.drop(1).firstOrNull { 
                validatePolicy(it.policy, context).valid 
            }
            
            if (alternative != null) {
                return makeSelection(alternative, intent, context, validated.reason)
            }
        }
        
        return makeSelection(best, intent, context)
    }
    
    /**
     * Validate action before execution (safety layer)
     */
    fun validateAction(
        action: PolicyAction,
        context: ValidationContext
    ): ActionValidation {
        val checks = listOf(
            validateSafety(action, context),
            validateFeasibility(action, context),
            validateConsistency(action, context),
            validateResourceAvailability(action, context)
        )
        
        val failures = checks.filterNot { it.passed }
        
        return if (failures.isEmpty()) {
            ActionValidation.VALID
        } else {
            ActionValidation(
                valid = false,
                failedChecks = failures.map { it.name },
                suggestedAlternative = suggestAlternative(action, failures, context)
            )
        }
    }
    
    /**
     * Get available actions for current context
     */
    fun getAvailableActions(context: ActionAvailabilityContext): List<AvailableAction> {
        return policies.values.flatMap { it.actions }
            .filter { isActionAvailable(it, context) }
            .map { action ->
                AvailableAction(
                    action = action,
                    confidence = estimateActionConfidence(action, context),
                    prerequisites = getPrerequisites(action),
                    estimatedSuccessRate = getSuccessRate(action, context)
                )
            }
            .sortedByDescending { it.estimatedSuccessRate }
    }
    
    /**
     * Adapt policy based on outcome feedback
     */
    fun adaptPolicy(
        policy: TacticalPolicy,
        outcome: PolicyOutcome,
        context: AdaptationContext
    ) {
        // Update policy weights based on success/failure
        val updatedPolicy = policy.copy(
            successRate = updateSuccessRate(policy.successRate, outcome.success),
            usageCount = policy.usageCount + 1,
            lastUsed = System.currentTimeMillis()
        )
        
        policies[policy.name] = updatedPolicy
        
        // Learn from this outcome
        adaptiveLearning.learnFromExperience(
            AdaptiveLearningEngine.RichExperience(
                timestamp = System.currentTimeMillis(),
                context = context.situation,
                actionType = policy.name,
                actionIndex = 0,
                stateFeatures = context.stateVector,
                nextStateFeatures = null,
                outcome = AdaptiveLearningEngine.Outcome(
                    reward = if (outcome.success) 1f else -1f,
                    isTerminal = outcome.isTerminal
                ),
                policyLogProb = 0f,
                valueEstimate = outcome.expectedValue
            )
        )
        
        Timber.i("Policy ${policy.name} adapted: success=${outcome.success}, rate=${updatedPolicy.successRate}")
    }
    
    /**
     * Register a new policy
     */
    fun registerPolicy(policy: TacticalPolicy) {
        policies[policy.name] = policy
        Timber.i("Registered policy: ${policy.name}")
    }
    
    /**
     * Get policy statistics
     */
    fun getStats(): PolicyStats {
        return PolicyStats(
            totalPolicies = policies.size,
            registeredIntents = policies.values.map { it.targetIntent }.distinct().size,
            totalSelections = selectionHistory.size,
            averageSuccessRate = policies.values.map { it.successRate }.average().toFloat(),
            mostUsedPolicy = policies.values.maxByOrNull { it.usageCount }?.name,
            validationPassRate = calculateValidationRate()
        )
    }
    
    // Private methods
    
    private fun initializeDefaultPolicies() {
        // Combat policies
        registerPolicy(TacticalPolicy(
            name = "aggressive_push",
            targetIntent = IntentPlanner.IntentType.PUSH,
            conditions = setOf(PolicyCondition.HEALTH_ABOVE_50, PolicyCondition.ENEMY_DETECTED),
            actions = listOf(
                PolicyAction.MOVE_FORWARD,
                PolicyAction.FIRE_BURST,
                PolicyAction.USE_COVER
            ),
            priority = 0.8f,
            riskLevel = RiskLevel.HIGH
        ))
        
        registerPolicy(TacticalPolicy(
            name = "defensive_hold",
            targetIntent = IntentPlanner.IntentType.HOLD,
            conditions = setOf(PolicyCondition.IN_COVER, PolicyCondition.ENEMY_DETECTED),
            actions = listOf(
                PolicyAction.HOLD_POSITION,
                PolicyAction.AIM,
                PolicyAction.FIRE_TAP
            ),
            priority = 0.6f,
            riskLevel = RiskLevel.LOW
        ))
        
        registerPolicy(TacticalPolicy(
            name = "emergency_retreat",
            targetIntent = IntentPlanner.IntentType.RETREAT,
            conditions = setOf(PolicyCondition.HEALTH_CRITICAL),
            actions = listOf(
                PolicyAction.MOVE_BACKWARD,
                PolicyAction.FIND_COVER,
                PolicyAction.USE_HEAL
            ),
            priority = 1.0f,
            riskLevel = RiskLevel.MEDIUM
        ))
        
        registerPolicy(TacticalPolicy(
            name = "flank_maneuver",
            targetIntent = IntentPlanner.IntentType.FLANK,
            conditions = setOf(PolicyCondition.ENEMY_DETECTED, PolicyCondition.SPACE_TO_FLANK),
            actions = listOf(
                PolicyAction.MOVE_SIDEWAYS,
                PolicyAction.USE_COVER,
                PolicyAction.FIRE_BURST
            ),
            priority = 0.7f,
            riskLevel = RiskLevel.MEDIUM
        ))
        
        registerPolicy(TacticalPolicy(
            name = "search_loot",
            targetIntent = IntentPlanner.IntentType.SEARCH,
            conditions = setOf(PolicyCondition.LOOT_NEARBY, PolicyCondition.NO_ENEMIES),
            actions = listOf(
                PolicyAction.MOVE_TO_LOOT,
                PolicyAction.INTERACT
            ),
            priority = 0.5f,
            riskLevel = RiskLevel.LOW
        ))
    }
    
    private fun getCandidatePolicies(intent: IntentPlanner.TacticalIntent): List<TacticalPolicy> {
        return policies.values.filter { policy ->
            policy.targetIntent == intent.primaryGoal ||
            intent.secondaryGoals.contains(policy.targetIntent)
        }
    }
    
    private fun calculatePolicyScore(
        policy: TacticalPolicy,
        intent: IntentPlanner.TacticalIntent,
        context: SelectionContext
    ): Float {
        var score = policy.priority * policy.successRate
        
        // Adjust for urgency
        score *= when (intent.urgency) {
            IntentPlanner.IntentUrgency.CRITICAL -> if (policy.riskLevel == RiskLevel.LOW) 0.5f else 1.5f
            IntentPlanner.IntentUrgency.HIGH -> 1.2f
            else -> 1f
        }
        
        // Adjust for confidence
        score *= intent.confidence
        
        // Adjust based on similar past success
        val similarSelections = selectionHistory.filter {
            it.policyName == policy.name && 
            it.outcome?.success == true
        }
        if (similarSelections.isNotEmpty()) {
            score *= 1.1f
        }
        
        // Penalize recently failed policies
        val recentFailures = selectionHistory.takeLast(10).filter {
            it.policyName == policy.name && it.outcome?.success == false
        }
        score *= exp(-0.5f * recentFailures.size)
        
        return score
    }
    
    private fun validatePolicy(policy: TacticalPolicy, context: SelectionContext): PolicyValidation {
        // Check all conditions
        val failed = policy.conditions.filter { !checkCondition(it, context) }
        
        return if (failed.isEmpty()) {
            PolicyValidation(true)
        } else {
            PolicyValidation(
                valid = false,
                reason = "Failed conditions: ${failed.joinToString()}"
            )
        }
    }
    
    private fun checkCondition(condition: PolicyCondition, context: SelectionContext): Boolean {
        return when (condition) {
            PolicyCondition.HEALTH_ABOVE_50 -> context.health > 50
            PolicyCondition.HEALTH_CRITICAL -> context.health < 30
            PolicyCondition.ENEMY_DETECTED -> context.enemiesDetected
            PolicyCondition.IN_COVER -> context.inCover
            PolicyCondition.LOOT_NEARBY -> context.lootNearby
            PolicyCondition.NO_ENEMIES -> !context.enemiesDetected
            PolicyCondition.SPACE_TO_FLANK -> context.spaceToFlank
            PolicyCondition.AMMO_SUFFICIENT -> context.ammo > 10
        }
    }
    
    private fun makeSelection(
        scored: ScoredPolicy,
        intent: IntentPlanner.TacticalIntent,
        context: SelectionContext,
        validationOverride: String? = null
    ): PolicySelection {
        currentPolicy = scored.policy
        
        val selection = PolicySelection(
            selectedPolicy = scored.policy,
            confidence = scored.score,
            alternatives = emptyList(),  // Could include runner-ups
            validationStatus = validationOverride ?: "VALID",
            intent = intent
        )
        
        selectionHistory.add(selection)
        
        return selection
    }
    
    private fun validateSafety(
        action: PolicyAction,
        context: ValidationContext
    ): CheckResult {
        val passed = when (action) {
            PolicyAction.MOVE_FORWARD -> !context.willTakeDamage
            PolicyAction.FIRE_BURST -> context.hasTarget && context.ammo > 0
            PolicyAction.USE_HEAL -> context.health < 100 && context.hasHealItem
            else -> true
        }
        return CheckResult("SAFETY", passed)
    }
    
    private fun validateFeasibility(
        action: PolicyAction,
        context: ValidationContext
    ): CheckResult {
        return CheckResult("FEASIBILITY", true)  // Simplified
    }
    
    private fun validateConsistency(
        action: PolicyAction,
        context: ValidationContext
    ): CheckResult {
        // Check if action contradicts recent actions
        return CheckResult("CONSISTENCY", true)  // Simplified
    }
    
    private fun validateResourceAvailability(
        action: PolicyAction,
        context: ValidationContext
    ): CheckResult {
        val passed = when (action) {
            PolicyAction.FIRE_BURST -> context.ammo >= 3
            PolicyAction.FIRE_SPRAY -> context.ammo >= 10
            PolicyAction.USE_HEAL -> context.hasHealItem
            PolicyAction.USE_ABILITY -> context.hasAbility
            else -> true
        }
        return CheckResult("RESOURCES", passed)
    }
    
    private fun suggestAlternative(
        action: PolicyAction,
        failures: List<CheckResult>,
        context: ValidationContext
    ): PolicyAction? {
        return when {
            failures.any { it.name == "RESOURCES" && action.name.contains("FIRE") } ->
                PolicyAction.FIRE_TAP
            failures.any { it.name == "SAFETY" } ->
                PolicyAction.FIND_COVER
            else -> null
        }
    }
    
    private fun isActionAvailable(action: PolicyAction, context: ActionAvailabilityContext): Boolean {
        return when (action) {
            PolicyAction.FIRE_BURST, PolicyAction.FIRE_SPRAY, PolicyAction.FIRE_TAP ->
                context.ammo > 0 && context.hasTarget
            PolicyAction.USE_HEAL ->
                context.healItems > 0
            PolicyAction.USE_ABILITY ->
                context.abilitiesReady > 0
            else -> true
        }
    }
    
    private fun estimateActionConfidence(action: PolicyAction, context: ActionAvailabilityContext): Float {
        return when (action) {
            PolicyAction.AIM -> 0.9f
            PolicyAction.FIRE_TAP -> 0.8f
            PolicyAction.FIRE_BURST -> 0.7f
            PolicyAction.FIRE_SPRAY -> 0.6f
            else -> 0.75f
        }
    }
    
    private fun getPrerequisites(action: PolicyAction): List<String> {
        return when (action) {
            PolicyAction.FIRE_BURST -> listOf("ammo>=3", "target_acquired")
            PolicyAction.FIRE_SPRAY -> listOf("ammo>=10", "target_acquired")
            PolicyAction.USE_HEAL -> listOf("health<100", "heal_item_available")
            else -> emptyList()
        }
    }
    
    private fun getSuccessRate(action: PolicyAction, context: ActionAvailabilityContext): Float {
        // Get from learning history
        return 0.7f  // Default
    }
    
    private fun updateSuccessRate(current: Float, success: Boolean): Float {
        val alpha = 0.1f  // Learning rate
        return current * (1 - alpha) + (if (success) 1f else 0f) * alpha
    }
    
    private fun calculateValidationRate(): Float {
        if (selectionHistory.isEmpty()) return 1f
        return selectionHistory.count { it.validationStatus == "VALID" }.toFloat() / selectionHistory.size
    }
    
    // Data classes
    
    data class TacticalPolicy(
        val name: String,
        val targetIntent: IntentPlanner.IntentType,
        val conditions: Set<PolicyCondition>,
        val actions: List<PolicyAction>,
        val priority: Float,
        val riskLevel: RiskLevel,
        val successRate: Float = 0.5f,
        val usageCount: Int = 0,
        val lastUsed: Long = 0
    )
    
    data class PolicySelection(
        val selectedPolicy: TacticalPolicy,
        val confidence: Float,
        val alternatives: List<TacticalPolicy>,
        val validationStatus: String,
        val intent: IntentPlanner.TacticalIntent,
        val outcome: PolicyOutcome? = null
    ) {
        companion object {
            val FALLBACK = PolicySelection(
                selectedPolicy = TacticalPolicy(
                    name = "fallback_hold",
                    targetIntent = IntentPlanner.IntentType.HOLD,
                    conditions = emptySet(),
                    actions = listOf(PolicyAction.HOLD_POSITION, PolicyAction.AIM),
                    priority = 0.5f,
                    riskLevel = RiskLevel.LOW
                ),
                confidence = 0.3f,
                alternatives = emptyList(),
                validationStatus = "FALLBACK",
                intent = IntentPlanner.TacticalIntent(
                    primaryGoal = IntentPlanner.IntentType.HOLD,
                    secondaryGoals = emptyList(),
                    dimensions = emptyMap(),
                    urgency = IntentPlanner.IntentUrgency.NORMAL,
                    expectedDurationMs = 1000,
                    conflicts = emptyList(),
                    confidence = 0.3f
                )
            )
        }
    }
    
    data class ScoredPolicy(
        val policy: TacticalPolicy,
        val score: Float
    )
    
    data class PolicyValidation(
        val valid: Boolean,
        val reason: String = ""
    )
    
    data class ActionValidation(
        val valid: Boolean,
        val failedChecks: List<String> = emptyList(),
        val suggestedAlternative: PolicyAction? = null
    ) {
        companion object {
            val VALID = ActionValidation(true)
        }
    }
    
    data class AvailableAction(
        val action: PolicyAction,
        val confidence: Float,
        val prerequisites: List<String>,
        val estimatedSuccessRate: Float
    )
    
    data class SelectionContext(
        val health: Int,
        val enemiesDetected: Boolean,
        val inCover: Boolean,
        val lootNearby: Boolean,
        val spaceToFlank: Boolean,
        val ammo: Int
    )
    
    data class ValidationContext(
        val willTakeDamage: Boolean,
        val hasTarget: Boolean,
        val ammo: Int,
        val health: Int,
        val hasHealItem: Boolean,
        val hasAbility: Boolean
    )
    
    data class ActionAvailabilityContext(
        val ammo: Int,
        val hasTarget: Boolean,
        val healItems: Int,
        val abilitiesReady: Int
    )
    
    data class AdaptationContext(
        val situation: AdaptiveLearningEngine.SituationContext,
        val stateVector: FloatArray
    )
    
    data class PolicyOutcome(
        val success: Boolean,
        val isTerminal: Boolean,
        val expectedValue: Float
    )
    
    data class PolicyStats(
        val totalPolicies: Int,
        val registeredIntents: Int,
        val totalSelections: Int,
        val averageSuccessRate: Float,
        val mostUsedPolicy: String?,
        val validationPassRate: Float
    )
    
    data class CheckResult(
        val name: String,
        val passed: Boolean
    )
    
    enum class PolicyAction {
        MOVE_FORWARD, MOVE_BACKWARD, MOVE_SIDEWAYS,
        HOLD_POSITION, FIND_COVER, USE_COVER,
        AIM, FIRE_TAP, FIRE_BURST, FIRE_SPRAY,
        USE_HEAL, USE_ABILITY, RELOAD,
        INTERACT, MOVE_TO_LOOT, SCAN_AREA
    }
    
    enum class PolicyCondition {
        HEALTH_ABOVE_50, HEALTH_CRITICAL,
        ENEMY_DETECTED, NO_ENEMIES,
        IN_COVER, SPACE_TO_FLANK,
        LOOT_NEARBY, AMMO_SUFFICIENT
    }
    
    enum class RiskLevel { LOW, MEDIUM, HIGH, EXTREME }
}
