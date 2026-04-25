package com.ffai.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * FFAI Configuration - Runtime adjustable parameters
 */
object FFAIConfig {
    
    private lateinit var prefs: SharedPreferences
    private lateinit var deviceProfile: DeviceProfile
    private lateinit var limits: PerformanceLimits.Limits
    
    // Observable configuration states
    private val _inferenceEnabled = MutableStateFlow(true)
    val inferenceEnabled: StateFlow<Boolean> = _inferenceEnabled
    
    private val _personalityMode = MutableStateFlow(PersonalityMode.BALANCED)
    val personalityMode: StateFlow<PersonalityMode> = _personalityMode
    
    private val _aggressionLevel = MutableStateFlow(0.5f)
    val aggressionLevel: StateFlow<Float> = _aggressionLevel
    
    private val _assistLevel = MutableStateFlow(AssistLevel.SEMI)
    val assistLevel: StateFlow<AssistLevel> = _assistLevel
    
    fun initialize(context: Context, profile: DeviceProfile) {
        prefs = context.getSharedPreferences("ffai_config", Context.MODE_PRIVATE)
        deviceProfile = profile
        limits = PerformanceLimits.getForTier(profile.optimizationTier)
        
        // Load saved preferences
        _inferenceEnabled.value = prefs.getBoolean("inference_enabled", true)
        _personalityMode.value = PersonalityMode.valueOf(
            prefs.getString("personality_mode", PersonalityMode.BALANCED.name)!!
        )
        _aggressionLevel.value = prefs.getFloat("aggression_level", 0.5f)
        _assistLevel.value = AssistLevel.valueOf(
            prefs.getString("assist_level", AssistLevel.SEMI.name)!!
        )
    }
    
    fun getDeviceProfile(): DeviceProfile = deviceProfile
    fun getLimits(): PerformanceLimits.Limits = limits
    
    fun setInferenceEnabled(enabled: Boolean) {
        _inferenceEnabled.value = enabled
        prefs.edit().putBoolean("inference_enabled", enabled).apply()
    }
    
    fun setPersonalityMode(mode: PersonalityMode) {
        _personalityMode.value = mode
        prefs.edit().putString("personality_mode", mode.name).apply()
    }
    
    fun setAggressionLevel(level: Float) {
        _aggressionLevel.value = level.coerceIn(0f, 1f)
        prefs.edit().putFloat("aggression_level", level).apply()
    }
    
    fun setAssistLevel(level: AssistLevel) {
        _assistLevel.value = level
        prefs.edit().putString("assist_level", level.name).apply()
    }
    
    /**
     * Personality Modes for AI Behavior
     */
    enum class PersonalityMode {
        ULTRA_DEFENSIVE,  // Survival first, minimal risks
        DEFENSIVE,        // Conservative play
        BALANCED,         // Standard balanced gameplay
        AGGRESSIVE,       // Proactive hunting
        ULTRA_AGGRESSIVE, // High risk high reward
        STEALTH,          // Silent, patient gameplay
        SNIPER,           // Long range focus
        RUSHER            // Close combat focus
    }
    
    /**
     * Assist Levels - How much help the AI provides
     */
    enum class AssistLevel {
        NONE,       // No assistance, only data
        PASSIVE,    // Suggestions only
        SEMI,       // Assists with aiming/movement
        FULL,       // Fully automated gameplay
        CUSTOM      // User-defined mix
    }
    
    /**
     * Game-specific configurations for Free Fire
     */
    object GameConfig {
        // UI Element detection regions (normalized 0-1)
        const val MINIMAP_X = 0.02f
        const val MINIMAP_Y = 0.02f
        const val MINIMAP_SIZE = 0.20f
        
        const val HP_BAR_X = 0.35f
        const val HP_BAR_Y = 0.92f
        const val HP_BAR_WIDTH = 0.30f
        const val HP_BAR_HEIGHT = 0.03f
        
        const val AMMO_X = 0.75f
        const val AMMO_Y = 0.90f
        
        // Control zones
        const val JOYSTICK_X = 0.15f
        const val JOYSTICK_Y = 0.75f
        const val FIRE_BUTTON_X = 0.85f
        const val FIRE_BUTTON_Y = 0.75f
        const val AIM_BUTTON_X = 0.75f
        const val AIM_BUTTON_Y = 0.75f
        
        // Weapon types (for recoil compensation)
        val WEAPON_RECOIL_PATTERNS = mapOf(
            "mp40" to RecoilPattern(vertical = 0.8f, horizontal = 0.3f, recovery = 0.5f),
            "m1014" to RecoilPattern(vertical = 1.5f, horizontal = 0.8f, recovery = 0.3f),
            "ak" to RecoilPattern(vertical = 1.2f, horizontal = 0.6f, recovery = 0.4f),
            "m4a1" to RecoilPattern(vertical = 0.9f, horizontal = 0.4f, recovery = 0.6f),
            "awm" to RecoilPattern(vertical = 2.0f, horizontal = 0.2f, recovery = 0.2f),
            "default" to RecoilPattern(vertical = 1.0f, horizontal = 0.5f, recovery = 0.5f)
        )
        
        data class RecoilPattern(
            val vertical: Float,
            val horizontal: Float,
            val recovery: Float
        )
    }
}
