package com.ffai

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.ffai.config.A21SOptimizer
import com.ffai.config.DeviceProfile
import com.ffai.config.FFAIConfig
import com.ffai.core.orchestrator.CentralOrchestrator
import com.ffai.memory.ltm.LongTermMemory
import com.ffai.personality.PersonalityEngine
import com.ffai.service.FFAIAccessibilityService
import com.ffai.util.ThermalManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * FFAI Application - Entry point for the AI System
 * Optimized for Samsung Galaxy A21S (Exynos 850)
 */
class FFAIApplication : Application() {

    companion object {
        lateinit var instance: FFAIApplication
            private set
    }

    // Global coroutine scope for the application
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Main thread handler for UI operations
    val mainHandler = Handler(Looper.getMainLooper())
    
    // Core system components
    lateinit var orchestrator: CentralOrchestrator
        private set
    lateinit var personalityEngine: PersonalityEngine
        private set
    lateinit var deviceProfile: DeviceProfile
        private set
    lateinit var thermalManager: ThermalManager
        private set
    lateinit var longTermMemory: LongTermMemory
        private set
    
    // Service reference (set when service binds)
    var accessibilityService: FFAIAccessibilityService? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Initialize logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        Timber.i("=== FFAI Initialization ===")
        Timber.i("Device: ${android.os.Build.MODEL}")
        Timber.i("Android Version: ${android.os.Build.VERSION.RELEASE}")
        
        // Detect and configure device profile
        deviceProfile = A21SOptimizer.detectDeviceProfile(this)
        Timber.i("Device Profile: ${deviceProfile.name}")
        
        // Initialize thermal management
        thermalManager = ThermalManager(this, deviceProfile)
        
        // Initialize configuration
        FFAIConfig.initialize(this, deviceProfile)
        
        // Initialize long-term memory
        longTermMemory = LongTermMemory(this)
        
        // Initialize personality engine
        personalityEngine = PersonalityEngine(this, longTermMemory)
        
        // Initialize orchestrator (core of the system)
        orchestrator = CentralOrchestrator(
            context = this,
            deviceProfile = deviceProfile,
            thermalManager = thermalManager,
            personalityEngine = personalityEngine,
            longTermMemory = longTermMemory
        )
        
        Timber.i("=== FFAI Ready ===")
    }

    override fun onTerminate() {
        super.onTerminate()
        orchestrator.shutdown()
        Timber.i("=== FFAI Terminated ===")
    }
}
