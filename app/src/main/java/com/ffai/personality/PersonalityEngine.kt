package com.ffai.personality

import android.content.Context
import com.ffai.config.FFAIConfig
import com.ffai.memory.ltm.LongTermMemory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Personality Engine - Manages AI behavior profiles
 * Defines how the AI plays: aggressive, defensive, stealthy, etc.
 */
class PersonalityEngine(
    private val context: Context,
    private val longTermMemory: LongTermMemory
) {
    private val _currentPersonality = MutableStateFlow(PersonalityProfile.BALANCED)
    val currentPersonality: StateFlow<PersonalityProfile> = _currentPersonality
    
    private val _currentMode = MutableStateFlow(FFAIConfig.PersonalityMode.BALANCED)
    val currentMode: StateFlow<FFAIConfig.PersonalityMode> = _currentMode
    
    private val personalityHistory = mutableListOf<PersonalitySwitch>()
    
    init {
        loadPersonalityPreference()
        Timber.d("Personality Engine initialized: ${_currentPersonality.value.name}")
    }
    
    /**
     * Switch to a different personality mode
     */
    fun switchMode(mode: FFAIConfig.PersonalityMode) {
        val oldProfile = _currentPersonality.value
        val newProfile = getProfileForMode(mode)
        
        _currentPersonality.value = newProfile
        _currentMode.value = mode
        
        // Record switch
        personalityHistory.add(PersonalitySwitch(
            timestamp = System.currentTimeMillis(),
            from = oldProfile.name,
            to = newProfile.name,
            trigger = determineSwitchTrigger(mode)
        ))
        
        // Save preference
        FFAIConfig.setPersonalityMode(mode)
        
        Timber.i("Personality switched: ${oldProfile.name} -> ${newProfile.name}")
    }
    
    /**
     * Auto-switch personality based on game situation
     */
    fun autoAdjust(situation: GameSituation) {
        val recommendedMode = when {
            situation.healthPercent < 20 -> FFAIConfig.PersonalityMode.ULTRA_DEFENSIVE
            situation.isLastZone -> FFAIConfig.PersonalityMode.BALANCED
            situation.hasHighGround -> FFAIConfig.PersonalityMode.AGGRESSIVE
            situation.isOutnumbered -> FFAIConfig.PersonalityMode.DEFENSIVE
            situation.hasSniper && situation.distance > 100 -> FFAIConfig.PersonalityMode.SNIPER
            situation.timeAlive < 60 -> FFAIConfig.PersonalityMode.STEALTH
            else -> null
        }
        
        recommendedMode?.let {
            if (it != _currentMode.value) {
                switchMode(it)
            }
        }
    }
    
    /**
     * Get profile for specific mode
     */
    fun getProfileForMode(mode: FFAIConfig.PersonalityMode): PersonalityProfile {
        return when (mode) {
            FFAIConfig.PersonalityMode.ULTRA_DEFENSIVE -> PersonalityProfile.ULTRA_DEFENSIVE
            FFAIConfig.PersonalityMode.DEFENSIVE -> PersonalityProfile.DEFENSIVE
            FFAIConfig.PersonalityMode.BALANCED -> PersonalityProfile.BALANCED
            FFAIConfig.PersonalityMode.AGGRESSIVE -> PersonalityProfile.AGGRESSIVE
            FFAIConfig.PersonalityMode.ULTRA_AGGRESSIVE -> PersonalityProfile.ULTRA_AGGRESSIVE
            FFAIConfig.PersonalityMode.STEALTH -> PersonalityProfile.STEALTH
            FFAIConfig.PersonalityMode.SNIPER -> PersonalityProfile.SNIPER
            FFAIConfig.PersonalityMode.RUSHER -> PersonalityProfile.RUSHER
        }
    }
    
    /**
     * Get current personality for decision making
     */
    fun getCurrentPersonality(): PersonalityProfile = _currentPersonality.value
    
    /**
     * Blend two personalities (for smooth transitions)
     */
    fun blendPersonalities(
        p1: PersonalityProfile,
        p2: PersonalityProfile,
        weight: Float  // 0 = p1, 1 = p2
    ): PersonalityProfile {
        return PersonalityProfile(
            name = "${p1.name}_blend_${p2.name}",
            aggression = lerp(p1.aggression, p2.aggression, weight),
            caution = lerp(p1.caution, p2.caution, weight),
            greed = lerp(p1.greed, p2.greed, weight),
            patience = lerp(p1.patience, p2.patience, weight),
            riskTolerance = lerp(p1.riskTolerance, p2.riskTolerance, weight),
            reactionSpeed = lerp(p1.reactionSpeed, p2.reactionSpeed, weight),
            aimPrecision = lerp(p1.aimPrecision, p2.aimPrecision, weight),
            movementStyle = if (weight < 0.5) p1.movementStyle else p2.movementStyle,
            preferredRange = lerp(p1.preferredRange, p2.preferredRange, weight),
            lootPriority = lerp(p1.lootPriority, p2.lootPriority, weight),
            engagementThreshold = lerp(p1.engagementThreshold, p2.engagementThreshold, weight),
            disengageThreshold = lerp(p1.disengageThreshold, p2.disengageThreshold, weight),
            campLikelihood = lerp(p1.campLikelihood, p2.campLikelihood, weight),
            rushLikelihood = lerp(p1.rushLikelihood, p2.rushLikelihood, weight)
        )
    }
    
    /**
     * Get personality stats
     */
    fun getStats(): PersonalityStats {
        return PersonalityStats(
            currentMode = _currentMode.value,
            currentProfile = _currentPersonality.value,
            switchCount = personalityHistory.size,
            history = personalityHistory.takeLast(10)
        )
    }
    
    private fun loadPersonalityPreference() {
        val savedMode = FFAIConfig.personalityMode.value
        _currentPersonality.value = getProfileForMode(savedMode)
        _currentMode.value = savedMode
    }
    
    private fun determineSwitchTrigger(mode: FFAIConfig.PersonalityMode): String {
        return when (mode) {
            FFAIConfig.PersonalityMode.ULTRA_DEFENSIVE -> "critical_health"
            FFAIConfig.PersonalityMode.DEFENSIVE -> "outnumbered"
            FFAIConfig.PersonalityMode.STEALTH -> "early_game"
            else -> "manual"
        }
    }
    
    private fun lerp(a: Float, b: Float, t: Float): Float {
        return a + (b - a) * t.coerceIn(0f, 1f)
    }
    
    // Data classes
    data class PersonalityProfile(
        val name: String,
        val aggression: Float,        // 0-1: Likelihood to engage
        val caution: Float,          // 0-1: Defensive behavior
        val greed: Float,           // 0-1: Loot priority
        val patience: Float,        // 0-1: Waiting tactics
        val riskTolerance: Float,   // 0-1: Risk taking
        val reactionSpeed: Float,   // 0-1: Quick responses
        val aimPrecision: Float,    // 0-1: Aim quality
        val movementStyle: MovementStyle,
        val preferredRange: Float,  // 0-1: Close to long distance
        val lootPriority: Float,    // 0-1: Loot importance
        val engagementThreshold: Float,  // HP% to start fighting
        val disengageThreshold: Float,   // HP% to stop fighting
        val campLikelihood: Float,  // 0-1: Camping tendency
        val rushLikelihood: Float   // 0-1: Rushing tendency
    ) {
        enum class MovementStyle {
            TURTLE,    // Very slow, defensive
            CAUTIOUS,  // Careful movements
            STANDARD,  // Normal
            DYNAMIC,   // Fast, adaptive
            AGGRESSIVE // Fast, push-oriented
        }
        
        companion object {
            val ULTRA_DEFENSIVE = PersonalityProfile(
                name = "Ultra Defensive",
                aggression = 0.1f,
                caution = 0.95f,
                greed = 0.3f,
                patience = 0.9f,
                riskTolerance = 0.05f,
                reactionSpeed = 0.7f,
                aimPrecision = 0.8f,
                movementStyle = MovementStyle.TURTLE,
                preferredRange = 0.8f,
                lootPriority = 0.2f,
                engagementThreshold = 0.8f,
                disengageThreshold = 0.5f,
                campLikelihood = 0.9f,
                rushLikelihood = 0.0f
            )
            
            val DEFENSIVE = PersonalityProfile(
                name = "Defensive",
                aggression = 0.3f,
                caution = 0.8f,
                greed = 0.4f,
                patience = 0.8f,
                riskTolerance = 0.2f,
                reactionSpeed = 0.75f,
                aimPrecision = 0.85f,
                movementStyle = MovementStyle.CAUTIOUS,
                preferredRange = 0.7f,
                lootPriority = 0.4f,
                engagementThreshold = 0.7f,
                disengageThreshold = 0.4f,
                campLikelihood = 0.6f,
                rushLikelihood = 0.1f
            )
            
            val BALANCED = PersonalityProfile(
                name = "Balanced",
                aggression = 0.5f,
                caution = 0.5f,
                greed = 0.5f,
                patience = 0.5f,
                riskTolerance = 0.5f,
                reactionSpeed = 0.8f,
                aimPrecision = 0.9f,
                movementStyle = MovementStyle.STANDARD,
                preferredRange = 0.5f,
                lootPriority = 0.5f,
                engagementThreshold = 0.5f,
                disengageThreshold = 0.3f,
                campLikelihood = 0.3f,
                rushLikelihood = 0.3f
            )
            
            val AGGRESSIVE = PersonalityProfile(
                name = "Aggressive",
                aggression = 0.8f,
                caution = 0.2f,
                greed = 0.6f,
                patience = 0.2f,
                riskTolerance = 0.7f,
                reactionSpeed = 0.9f,
                aimPrecision = 0.85f,
                movementStyle = MovementStyle.DYNAMIC,
                preferredRange = 0.3f,
                lootPriority = 0.4f,
                engagementThreshold = 0.3f,
                disengageThreshold = 0.15f,
                campLikelihood = 0.05f,
                rushLikelihood = 0.7f
            )
            
            val ULTRA_AGGRESSIVE = PersonalityProfile(
                name = "Ultra Aggressive",
                aggression = 0.95f,
                caution = 0.05f,
                greed = 0.4f,
                patience = 0.1f,
                riskTolerance = 0.95f,
                reactionSpeed = 0.95f,
                aimPrecision = 0.8f,
                movementStyle = MovementStyle.AGGRESSIVE,
                preferredRange = 0.1f,
                lootPriority = 0.2f,
                engagementThreshold = 0.2f,
                disengageThreshold = 0.05f,
                campLikelihood = 0.0f,
                rushLikelihood = 0.95f
            )
            
            val STEALTH = PersonalityProfile(
                name = "Stealth",
                aggression = 0.3f,
                caution = 0.9f,
                greed = 0.5f,
                patience = 0.95f,
                riskTolerance = 0.15f,
                reactionSpeed = 0.6f,
                aimPrecision = 0.95f,
                movementStyle = MovementStyle.CAUTIOUS,
                preferredRange = 0.9f,
                lootPriority = 0.6f,
                engagementThreshold = 0.9f,
                disengageThreshold = 0.6f,
                campLikelihood = 0.7f,
                rushLikelihood = 0.0f
            )
            
            val SNIPER = PersonalityProfile(
                name = "Sniper",
                aggression = 0.4f,
                caution = 0.7f,
                greed = 0.5f,
                patience = 0.9f,
                riskTolerance = 0.3f,
                reactionSpeed = 0.7f,
                aimPrecision = 0.98f,
                movementStyle = MovementStyle.CAUTIOUS,
                preferredRange = 0.95f,
                lootPriority = 0.5f,
                engagementThreshold = 0.7f,
                disengageThreshold = 0.4f,
                campLikelihood = 0.6f,
                rushLikelihood = 0.0f
            )
            
            val RUSHER = PersonalityProfile(
                name = "Rusher",
                aggression = 0.9f,
                caution = 0.15f,
                greed = 0.3f,
                patience = 0.1f,
                riskTolerance = 0.8f,
                reactionSpeed = 0.95f,
                aimPrecision = 0.75f,
                movementStyle = MovementStyle.AGGRESSIVE,
                preferredRange = 0.05f,
                lootPriority = 0.2f,
                engagementThreshold = 0.15f,
                disengageThreshold = 0.1f,
                campLikelihood = 0.0f,
                rushLikelihood = 0.9f
            )
        }
    }
    
    data class GameSituation(
        val healthPercent: Float,
        val isLastZone: Boolean,
        val hasHighGround: Boolean,
        val isOutnumbered: Boolean,
        val hasSniper: Boolean,
        val distance: Float,
        val timeAlive: Long
    )
    
    data class PersonalitySwitch(
        val timestamp: Long,
        val from: String,
        val to: String,
        val trigger: String
    )
    
    data class PersonalityStats(
        val currentMode: FFAIConfig.PersonalityMode,
        val currentProfile: PersonalityProfile,
        val switchCount: Int,
        val history: List<PersonalitySwitch>
    )
}
