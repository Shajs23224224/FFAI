package com.ffai.core.orchestrator

import android.content.Context
import com.ffai.config.DeviceProfile
import com.ffai.config.FFAIConfig
import com.ffai.config.PerformanceLimits
import com.ffai.core.scheduler.TaskScheduler
import com.ffai.core.intent.IntentPlanner
import com.ffai.core.policy.PolicySelector
import com.ffai.gestures.GestureController
import com.ffai.gestures.synthesis.GestureSynthesizer
import com.ffai.camera.CameraController
import com.ffai.economy.EconomyManager
import com.ffai.humanization.parametric.ParametricHumanizationEngine
import com.ffai.profiling.EnemyProfiler
import com.ffai.resilience.ResilienceManager
import com.ffai.meta.MetaEvaluator
import com.ffai.learning.rl.ReinforcementLearningEngine
import com.ffai.learning.adaptive.AdaptiveLearningEngine
import com.ffai.learning.error.ErrorMemory
import com.ffai.movement.MovementController
import com.ffai.reasoning.ReasoningEngine
import com.ffai.memory.ultra.UltraShortTermMemory
import com.ffai.memory.stm.ShortTermMemory
import com.ffai.memory.mtm.MediumTermMemory
import com.ffai.memory.ltm.LongTermMemory
import com.ffai.memory.retrieval.MemoryRetrievalEngine
import com.ffai.personality.PersonalityEngine
import com.ffai.perception.ScreenAnalyzer
import com.ffai.prediction.PredictionEngine
import com.ffai.tactics.TacticalDecisionEngine
import com.ffai.tactics.TacticType
import com.ffai.combat.CombatEngine
import com.ffai.timing.MultiRateTimingManager
import com.ffai.util.ThermalManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Central Orchestrator - Refactored with advanced architecture
 * Features:
 * - Intent/Policy/Execution separation
 * - Multi-rate timing per subsystem
 * - Advanced memory hierarchy (USTM/STM/MTM/LTM)
 * - Adaptive learning with error memory
 * - Parametric humanization
 */
class CentralOrchestrator(
    private val context: Context,
    private val deviceProfile: DeviceProfile,
    private val thermalManager: ThermalManager,
    private val personalityEngine: PersonalityEngine,
    private val longTermMemory: LongTermMemory
) {
    // Configuration
    private val limits = PerformanceLimits.getForTier(deviceProfile.optimizationTier)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + 
        CoroutineName("Orchestrator-Main"))
    
    // Multi-rate timing manager
    private lateinit var timingManager: MultiRateTimingManager
    
    // Advanced Memory System
    private lateinit var ultraShortTermMemory: UltraShortTermMemory
    private lateinit var shortTermMemory: ShortTermMemory
    private lateinit var mediumTermMemory: MediumTermMemory
    private lateinit var memoryRetrieval: MemoryRetrievalEngine
    
    // Core Modules
    private lateinit var screenAnalyzer: ScreenAnalyzer
    private lateinit var predictionEngine: PredictionEngine
    private lateinit var tacticalEngine: TacticalDecisionEngine
    private lateinit var combatEngine: CombatEngine
    
    // Execution Layer
    private lateinit var gestureController: GestureController
    private lateinit var cameraController: CameraController
    private lateinit var movementController: MovementController
    private lateinit var gestureSynthesizer: GestureSynthesizer
    
    // Intent/Policy Layer
    private lateinit var intentPlanner: IntentPlanner
    private lateinit var policySelector: PolicySelector
    
    // Learning & Adaptation
    private lateinit var rlEngine: ReinforcementLearningEngine
    private lateinit var adaptiveLearning: AdaptiveLearningEngine
    private lateinit var errorMemory: ErrorMemory
    
    // Support Systems
    private lateinit var economyManager: EconomyManager
    private lateinit var humanizationEngine: ParametricHumanizationEngine
    private lateinit var enemyProfiler: EnemyProfiler
    private lateinit var resilienceManager: ResilienceManager
    private lateinit var metaEvaluator: MetaEvaluator
    private lateinit var reasoningEngine: ReasoningEngine
    private lateinit var taskScheduler: TaskScheduler
    
    // State Management
    private val _systemState = MutableStateFlow(SystemState.IDLE)
    val systemState: StateFlow<SystemState> = _systemState
    
    private val _currentAction = MutableStateFlow<AIAction>(AIAction.None)
    val currentAction: StateFlow<AIAction> = _currentAction
    
    private val _currentIntent = MutableStateFlow<IntentPlanner.TacticalIntent?>(null)
    val currentIntent: StateFlow<IntentPlanner.TacticalIntent?> = _currentIntent
    
    private val isRunning = AtomicBoolean(false)
    private val lastCycleTime = AtomicLong(0)
    private val cycleCount = AtomicLong(0)
    
    // Safety rules - simple flag-based system
    private val emergencyStop = AtomicBoolean(false)
    
    // Mission tracking
    private val currentMission: String? = null
    
    enum class SystemState {
        IDLE, SCANNING, ENGAGING, REPOSITIONING, LOOTING, HEALING, RECOVERING, ERROR
    }
    
    enum class ModuleType {
        PERCEPTION, MEMORY, PREDICTION, TACTICS, COMBAT, ECONOMY, 
        HUMANIZATION, PROFILING, RESILIENCE
    }
    
    data class ModuleScore(
        val confidence: Float,
        val urgency: Float,
        val priority: Priority,
        val suggestedAction: AIAction?
    )
    
    enum class Priority(val value: Int) {
        CRITICAL(100), HIGH(75), MEDIUM(50), LOW(25), BACKGROUND(10)
    }
    
    sealed class AIAction {
        object None : AIAction()
        data class Move(val direction: Vector2, val speed: MovementSpeed) : AIAction()
        data class Aim(val target: Vector2, val predictionOffset: Vector2) : AIAction()
        data class Fire(val mode: FireMode, val durationMs: Long) : AIAction()
        data class UseAbility(val ability: AbilityType) : AIAction()
        data class TakeCover(val coverPosition: Vector2) : AIAction()
        data class Loot(val itemPosition: Vector2) : AIAction()
        data class Heal(val itemSlot: Int) : AIAction()
        data class Reload(val weaponSlot: Int) : AIAction()
        data class Scan(val area: Rect) : AIAction()
        data class Compound(val actions: List<AIAction>, val delays: List<Long>) : AIAction()
        
        enum class MovementSpeed { WALK, RUN, SPRINT }
        enum class FireMode { TAP, BURST, SPRAY }
        enum class AbilityType { GRENADE, SMOKE, FLASH, HEAL, SHIELD, SPEED }
    }
    
    data class Vector2(val x: Float, val y: Float)
    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float)
    
    data class DecisionWeights(
        var perceptionWeight: Float = 0.15f,
        var memoryWeight: Float = 0.10f,
        var predictionWeight: Float = 0.15f,
        var tacticsWeight: Float = 0.20f,
        var combatWeight: Float = 0.20f,
        var economyWeight: Float = 0.05f,
        var humanizationWeight: Float = 0.10f,
        var profilingWeight: Float = 0.05f
    ) {
        fun normalize() {
            val total = perceptionWeight + memoryWeight + predictionWeight + 
                       tacticsWeight + combatWeight + economyWeight + 
                       humanizationWeight + profilingWeight
            if (total > 0) {
                perceptionWeight /= total
                memoryWeight /= total
                predictionWeight /= total
                tacticsWeight /= total
                combatWeight /= total
                economyWeight /= total
                humanizationWeight /= total
                profilingWeight /= total
            }
        }
    }
    
    init {
        initializeModules()
        startOrchestrationLoop()
    }
    
    private fun initializeModules() {
        Timber.d("Initializing advanced architecture for ${deviceProfile.name}")
        
        // Multi-rate timing manager
        timingManager = MultiRateTimingManager(deviceProfile)
        
        // Advanced Memory System
        ultraShortTermMemory = UltraShortTermMemory(
            capacity = 50,  // ~5 seconds at 10fps
            retentionMs = 5000
        )
        shortTermMemory = ShortTermMemory(capacity = 300, retentionMs = 30000)
        mediumTermMemory = MediumTermMemory(context)
        memoryRetrieval = MemoryRetrievalEngine(
            ustm = ultraShortTermMemory,
            stm = shortTermMemory,
            mtm = mediumTermMemory,
            ltm = longTermMemory
        )
        
        // Core Modules
        screenAnalyzer = ScreenAnalyzer(
            context = context,
            analysisWidth = limits.screenAnalysisResolution,
            targetFps = 1000 / limits.targetLatencyMs
        )
        
        predictionEngine = PredictionEngine(
            horizonMs = limits.predictionHorizonMs,
            maxTrackedEntities = limits.maxEnemiesTracked
        )
        
        combatEngine = CombatEngine(
            deviceProfile = deviceProfile,
            recoilCompensation = true,
            targetSmoothing = limits.cameraSmoothingFrames
        )
        
        // Execution Layer
        gestureController = GestureController(context, deviceProfile)
        cameraController = CameraController(deviceProfile)
        movementController = MovementController(context, deviceProfile)
        gestureSynthesizer = GestureSynthesizer(deviceProfile)
        
        // Learning & Adaptation
        rlEngine = ReinforcementLearningEngine(
            context = context,
            deviceProfile = deviceProfile,
            batchSize = if (deviceProfile.optimizationTier <= DeviceProfile.OptimizationTier.LIGHT) 16 else 32,
            learningRate = 0.001f
        )
        
        adaptiveLearning = AdaptiveLearningEngine(
            context = context,
            deviceProfile = deviceProfile,
            memoryRetrieval = memoryRetrieval,
            rlEngine = rlEngine
        )
        
        errorMemory = ErrorMemory()
        
        // Intent/Policy Layer
        intentPlanner = IntentPlanner(personalityEngine, memoryRetrieval)
        policySelector = PolicySelector(intentPlanner, memoryRetrieval, adaptiveLearning)
        
        // Support systems
        economyManager = EconomyManager()
        humanizationEngine = ParametricHumanizationEngine(deviceProfile, personalityEngine)
        enemyProfiler = EnemyProfiler(longTermMemory)
        resilienceManager = ResilienceManager()
        metaEvaluator = MetaEvaluator()
        
        // Reasoning with tiered complexity
        reasoningEngine = ReasoningEngine(
            shortTermHorizonMs = 500,
            midTermHorizonMs = 5000,
            longTermHorizonMs = 30000,
            complexity = when(deviceProfile.optimizationTier) {
                DeviceProfile.OptimizationTier.ULTRA_LIGHT -> 0.3f
                DeviceProfile.OptimizationTier.LIGHT -> 0.5f
                else -> 1.0f
            }
        )
        
        // Tactical decision engine
        tacticalEngine = TacticalDecisionEngine(
            personalityEngine = personalityEngine,
            reasoningEngine = reasoningEngine,
            riskTolerance = FFAIConfig.aggressionLevel.value
        )
        
        // Task scheduler
        taskScheduler = TaskScheduler(deviceProfile)
        
        // Configure subsystem timings
        configureSubsystemTimings()
        
        Timber.d("All modules initialized with advanced architecture")
    }
    
    private fun configureSubsystemTimings() {
        // Decision cycle at 30Hz
        timingManager.configureSubsystem(
            MultiRateTimingManager.Subsystem.DECISION,
            30f,
            MultiRateTimingManager.TimingPriority.HIGH
        )
        
        // Perception async at 10Hz
        timingManager.configureSubsystem(
            MultiRateTimingManager.Subsystem.PERCEPTION,
            10f,
            MultiRateTimingManager.TimingPriority.HIGH
        )
        
        // Camera smooth at 60Hz
        timingManager.configureSubsystem(
            MultiRateTimingManager.Subsystem.CAMERA,
            60f,
            MultiRateTimingManager.TimingPriority.CRITICAL
        )
        
        // Gesture execution at 60Hz
        timingManager.configureSubsystem(
            MultiRateTimingManager.Subsystem.GESTURE,
            60f,
            MultiRateTimingManager.TimingPriority.CRITICAL
        )
        
        // Memory event-based
        timingManager.configureSubsystem(
            MultiRateTimingManager.Subsystem.MEMORY,
            0f,  // Event-based
            MultiRateTimingManager.TimingPriority.MEDIUM
        )
        
        // Learning background at 0.2Hz
        timingManager.configureSubsystem(
            MultiRateTimingManager.Subsystem.LEARNING,
            0.2f,
            MultiRateTimingManager.TimingPriority.LOW
        )
        
        // Prediction at 20Hz
        timingManager.configureSubsystem(
            MultiRateTimingManager.Subsystem.PREDICTION,
            20f,
            MultiRateTimingManager.TimingPriority.MEDIUM
        )
        
        // Humanization at 30Hz
        timingManager.configureSubsystem(
            MultiRateTimingManager.Subsystem.HUMANIZATION,
            30f,
            MultiRateTimingManager.TimingPriority.LOW
        )
    }
    
    private fun startOrchestrationLoop() {
        // Start timing manager for multi-rate operation
        timingManager.startAll()
        
        // Register callbacks for each subsystem
        
        // Perception subsystem (10Hz, async)
        mainScope.launch {
            timingManager.getEventChannel(MultiRateTimingManager.Subsystem.PERCEPTION)?.let { channel ->
                for (event in channel) {
                    if (isActive && isRunning.get()) {
                        executePerceptionCycle()
                    }
                }
            }
        }
        
        // Decision subsystem (30Hz)
        mainScope.launch {
            timingManager.getEventChannel(MultiRateTimingManager.Subsystem.DECISION)?.let { channel ->
                for (event in channel) {
                    if (isActive && isRunning.get() && FFAIConfig.inferenceEnabled.value) {
                        try {
                            // Check thermal state
                            if (thermalManager.shouldThrottle()) {
                                delay(thermalManager.getThrottleDelay())
                                _systemState.value = SystemState.RECOVERING
                                continue
                            }
                            
                            val cycleStart = System.nanoTime()
                            
                            // Execute main decision cycle with new architecture
                            executeAdvancedDecisionCycle()
                            
                            // Calculate cycle metrics
                            val cycleTime = (System.nanoTime() - cycleStart) / 1_000_000
                            lastCycleTime.set(cycleTime)
                            cycleCount.incrementAndGet()
                            
                        } catch (e: Exception) {
                            Timber.e(e, "Error in decision cycle")
                            resilienceManager.recordError(e)
                            _systemState.value = SystemState.ERROR
                        }
                    }
                }
            }
        }
        
        // Camera smoothing (60Hz)
        mainScope.launch {
            timingManager.getEventChannel(MultiRateTimingManager.Subsystem.CAMERA)?.let { channel ->
                for (event in channel) {
                    if (isActive && isRunning.get()) {
                        cameraController.update()
                    }
                }
            }
        }
        
        // Learning subsystem (0.2Hz)
        mainScope.launch {
            timingManager.getEventChannel(MultiRateTimingManager.Subsystem.LEARNING)?.let { channel ->
                for (event in channel) {
                    if (isActive && isRunning.get()) {
                        adaptiveLearning.performLearningUpdate()
                    }
                }
            }
        }
        
        // Idle check
        mainScope.launch {
            while (isActive) {
                if (!isRunning.get()) {
                    delay(100)
                } else {
                    delay(1000)
                }
            }
        }
    }
    
    private fun executePerceptionCycle() {
        val perceptionResult = screenAnalyzer.analyze()
        
        // Update Ultra-Short Term Memory immediately (fast reaction)
        ultraShortTermMemory.push(
            perception = perceptionResult,
            immediateContext = UltraShortTermMemory.ImmediateContext(
                mapId = "current_map",
                currentWeapon = perceptionResult.playerState.currentWeapon,
                position = Pair(0f, 0f),
                velocity = Pair(0f, 0f),
                lastAction = _currentAction.value::class.simpleName,
                inCombat = perceptionResult.detectedEnemies.isNotEmpty(),
                frameNumber = (cycleCount.get() % Int.MAX_VALUE).toInt()
            )
        )
        
        // Update STM/MTM for longer-term tracking
        shortTermMemory.push(perceptionResult)
        mediumTermMemory.record(perceptionResult)
        
        // Trigger memory update event
        timingManager.triggerEvent(MultiRateTimingManager.Subsystem.MEMORY, perceptionResult)
        
        // Check for immediate threats via USTM
        if (ultraShortTermMemory.isImmediateThreat()) {
            Timber.w("Immediate threat detected via USTM")
        }
    }
    
    private suspend fun executeAdvancedDecisionCycle() {
        // Get most recent perception
        val perceptionResult = shortTermMemory.last() ?: return
        
        // Phase 1: Predictions
        val predictions = predictionEngine.predict(
            enemies = perceptionResult.detectedEnemies,
            timeHorizonMs = limits.predictionHorizonMs
        )
        
        // Phase 2: Update profiles
        perceptionResult.detectedEnemies.forEach { enemy ->
            enemyProfiler.updateProfile(enemy, predictions[enemy.id])
        }
        
        // Phase 3: Intent Planning
        val gameContext = IntentPlanner.GameContext(
            mapId = "current_map",
            playerState = perceptionResult.playerState,
            enemies = perceptionResult.detectedEnemies,
            allies = perceptionResult.detectedAllies,
            loot = perceptionResult.detectedLoot,
            cover = perceptionResult.detectedCover,
            safeZone = perceptionResult.safeZone
        )
        
        val intent = intentPlanner.determineIntent(gameContext)
        _currentIntent.value = intent
        
        // Phase 4: Policy Selection
        val selectionContext = PolicySelector.SelectionContext(
            health = perceptionResult.playerState.health,
            enemiesDetected = perceptionResult.detectedEnemies.isNotEmpty(),
            inCover = perceptionResult.playerState.inCover,
            lootNearby = perceptionResult.detectedLoot.isNotEmpty(),
            spaceToFlank = true,
            ammo = perceptionResult.playerState.ammoCount
        )
        
        val policySelection = policySelector.selectPolicy(intent, selectionContext)
        val suggestedAction = policySelection.selectedPolicy.actions.firstOrNull()?.let {
            mapPolicyActionToAIAction(it, perceptionResult, predictions)
        } ?: AIAction.None
        
        // Phase 5: Humanization
        val humanizationContext = ParametricHumanizationEngine.HumanizationContext(
            enemiesNearby = perceptionResult.detectedEnemies.size,
            healthPercent = perceptionResult.playerState.health,
            isUnderFire = false,
            timeRemaining = perceptionResult.safeZone.timeToShrink,
            consecutiveMisses = 0,
            currentStreak = 0
        )
        
        val humanizedAction = humanizationEngine.humanize(suggestedAction, humanizationContext)
        executeHumanizedAction(humanizedAction, perceptionResult)
        
        // Phase 6: Learning
        learnFromCycle(suggestedAction, intent, perceptionResult)
        
        _currentAction.value = suggestedAction
        _systemState.value = determineAdvancedSystemState(perceptionResult, suggestedAction)
    }
    
    private fun mapPolicyActionToAIAction(
        policyAction: PolicySelector.PolicyAction,
        perception: ScreenAnalyzer.PerceptionResult,
        predictions: Map<String, PredictionEngine.Prediction>
    ): AIAction = when (policyAction) {
        PolicySelector.PolicyAction.MOVE_FORWARD -> AIAction.Move(Vector2(0f, -1f), AIAction.MovementSpeed.RUN)
        PolicySelector.PolicyAction.AIM -> AIAction.Aim(
            target = selectTarget(perception.detectedEnemies, predictions)?.let {
                Vector2(it.screenPosition.x, it.screenPosition.y)
            } ?: Vector2(0.5f, 0.5f),
            predictionOffset = Vector2(0f, 0f)
        )
        PolicySelector.PolicyAction.FIRE_BURST -> AIAction.Fire(AIAction.FireMode.BURST, 150)
        PolicySelector.PolicyAction.FIRE_TAP -> AIAction.Fire(AIAction.FireMode.TAP, 50)
        PolicySelector.PolicyAction.USE_HEAL -> AIAction.Heal(0)
        PolicySelector.PolicyAction.FIND_COVER -> AIAction.TakeCover(
            perception.detectedCover.firstOrNull()?.let {
                Vector2(it.position.x, it.position.y)
            } ?: Vector2(0f, 0f)
        )
        else -> AIAction.None
    }
    
    private fun executeHumanizedAction(
        action: ParametricHumanizationEngine.HumanizedAction,
        perception: ScreenAnalyzer.PerceptionResult
    ) {
        when (action) {
            is ParametricHumanizationEngine.HumanizedAction.Aim -> {
                cameraController.aim(action.target, action.predictionOffset)
            }
            is ParametricHumanizationEngine.HumanizedAction.Fire -> {
                combatEngine.fire(
                    when (action.mode) {
                        AIAction.FireMode.TAP -> CombatEngine.FireMode.TAP
                        AIAction.FireMode.BURST -> CombatEngine.FireMode.BURST
                        AIAction.FireMode.SPRAY -> CombatEngine.FireMode.SPRAY
                    },
                    action.adjustedDuration
                )
            }
            is ParametricHumanizationEngine.HumanizedAction.Move -> {
                // Movement via gesture
            }
            else -> {}
        }
    }
    
    private fun learnFromCycle(
        action: AIAction,
        intent: IntentPlanner.TacticalIntent,
        perception: ScreenAnalyzer.PerceptionResult
    ) {
        val experience = AdaptiveLearningEngine.RichExperience(
            timestamp = System.currentTimeMillis(),
            context = AdaptiveLearningEngine.SituationContext(
                mapId = "current_map",
                health = perception.playerState.health.toFloat(),
                ammo = perception.playerState.ammoCount,
                enemyCount = perception.detectedEnemies.size,
                weaponType = perception.playerState.currentWeapon,
                mapPosition = Pair(0f, 0f),
                riskLevel = 0.5f,
                inCover = perception.playerState.inCover,
                reactionTime = 150f
            ),
            actionType = action::class.simpleName ?: "unknown",
            actionIndex = 0,
            stateFeatures = floatArrayOf(
                perception.playerState.health / 100f,
                perception.playerState.ammoCount / 100f
            ),
            nextStateFeatures = null,
            outcome = AdaptiveLearningEngine.Outcome(
                reward = 0.1f,
                isTerminal = false
            ),
            policyLogProb = 0f,
            valueEstimate = intent.confidence
        )
        adaptiveLearning.learnFromExperience(experience)
    }
    
    private fun selectTarget(
        enemies: List<ScreenAnalyzer.Enemy>,
        predictions: Map<String, PredictionEngine.Prediction>
    ): ScreenAnalyzer.Enemy? {
        if (enemies.isEmpty()) return null
        return enemies.maxWithOrNull(compareBy(
            { it.threatLevel },
            { -it.distance },
            { -it.health }
        ))
    }
    
    private fun determineAdvancedSystemState(
        perception: ScreenAnalyzer.PerceptionResult,
        action: AIAction
    ): SystemState = when {
        perception.playerState.health < 30 -> SystemState.HEALING
        action is AIAction.Fire -> SystemState.ENGAGING
        action is AIAction.TakeCover -> SystemState.REPOSITIONING
        action is AIAction.Scan -> SystemState.SCANNING
        else -> SystemState.IDLE
    }
    
    fun start() {
        isRunning.set(true)
        _systemState.value = SystemState.SCANNING
        Timber.i("Orchestrator started")
    }
    
    fun stop() {
        isRunning.set(false)
        _systemState.value = SystemState.IDLE
        Timber.i("Orchestrator stopped")
    }
    
    fun shutdown() {
        isRunning.set(false)
        mainScope.cancel()
        rlEngine.saveModel()
        longTermMemory.close()
        mediumTermMemory.close()
        Timber.i("Orchestrator shutdown complete")
    }
    
    // Data classes for context
    data class TacticalContext(
        val playerState: ScreenAnalyzer.PlayerState,
        val enemies: List<ScreenAnalyzer.Enemy>,
        val allies: List<ScreenAnalyzer.Ally>,
        val loot: List<ScreenAnalyzer.LootItem>,
        val cover: List<ScreenAnalyzer.Cover>,
        val safeZone: ScreenAnalyzer.SafeZone,
        val predictions: Map<String, PredictionEngine.Prediction>,
        val recentDeaths: List<MediumTermMemory.DeathRecord>,
        val availableResources: EconomyManager.Resources
    )
    
    data class CombatContext(
        val target: ScreenAnalyzer.Enemy?,
        val weapon: String,
        val ammo: Int,
        val health: Int,
        val distance: Float,
        val inCover: Boolean
    )
    
    data class CombatDecision(
        val shouldEngage: Boolean,
        val confidence: Float,
        val action: AIAction?
    )
    
    data class TacticalDecision(
        val confidence: Float,
        val urgency: Float,
        val priority: Priority,
        val suggestedAction: AIAction
    )
    
    data class Experience(
        val state: TacticalContext,
        val action: AIAction,
        val reward: Float,
        val nextState: TacticalContext?
    )
}
