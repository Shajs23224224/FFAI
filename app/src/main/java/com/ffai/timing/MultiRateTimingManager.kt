package com.ffai.timing

import com.ffai.config.DeviceProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * Multi-Rate Timing Manager - Independent clocks for each subsystem
 * Separates: Perception (async), Memory (event), Camera (smooth), Decision (60Hz)
 */
class MultiRateTimingManager(
    private val deviceProfile: DeviceProfile
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Subsystem timing configurations
    private val timingConfigs = ConcurrentHashMap<Subsystem, TimingConfig>()
    private val subsystemChannels = ConcurrentHashMap<Subsystem, Channel<TimingEvent>>()
    private val runningJobs = ConcurrentHashMap<Subsystem, Job>()
    private val isRunning = AtomicBoolean(false)
    
    // Metrics
    private val cycleCounts = ConcurrentHashMap<Subsystem, AtomicLong>()
    private val lastExecutionTimes = ConcurrentHashMap<Subsystem, AtomicLong>()
    private val jitterMeasurements = ConcurrentHashMap<Subsystem, MutableList<Long>>()
    
    init {
        initializeDefaultTimings()
        createChannels()
    }
    
    /**
     * Configure timing for a subsystem
     */
    fun configureSubsystem(
        subsystem: Subsystem,
        targetRateHz: Float,
        priority: TimingPriority,
        adaptive: Boolean = true
    ) {
        val periodMs = (1000f / targetRateHz).toLong()
        
        timingConfigs[subsystem] = TimingConfig(
            subsystem = subsystem,
            targetPeriodMs = periodMs,
            priority = priority,
            adaptive = adaptive,
            jitterToleranceMs = (periodMs * 0.1f).toLong().coerceAtLeast(1)
        )
        
        Timber.i("Configured $subsystem: ${targetRateHz}Hz (${periodMs}ms period)")
    }
    
    /**
     * Start all subsystems
     */
    fun startAll() {
        if (isRunning.get()) return
        isRunning.set(true)
        
        timingConfigs.forEach { (subsystem, config) ->
            startSubsystem(subsystem, config)
        }
        
        Timber.i("Multi-rate timing manager started")
    }
    
    /**
     * Stop all subsystems
     */
    fun stopAll() {
        isRunning.set(false)
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
        Timber.i("Multi-rate timing manager stopped")
    }
    
    /**
     * Pause a specific subsystem
     */
    fun pauseSubsystem(subsystem: Subsystem) {
        runningJobs[subsystem]?.cancel()
        runningJobs.remove(subsystem)
        Timber.d("Paused $subsystem")
    }
    
    /**
     * Resume a specific subsystem
     */
    fun resumeSubsystem(subsystem: Subsystem) {
        timingConfigs[subsystem]?.let { config ->
            startSubsystem(subsystem, config)
        }
    }
    
    /**
     * Get timing event channel for a subsystem
     */
    fun getEventChannel(subsystem: Subsystem): Channel<TimingEvent>? {
        return subsystemChannels[subsystem]
    }
    
    /**
     * Register a callback for a subsystem tick
     */
    fun onTick(subsystem: Subsystem, callback: suspend () -> Unit) {
        val channel = subsystemChannels[subsystem] ?: return
        
        scope.launch {
            for (event in channel) {
                if (isActive && isRunning.get()) {
                    try {
                        callback()
                    } catch (e: Exception) {
                        Timber.e(e, "Error in $subsystem callback")
                    }
                }
            }
        }
    }
    
    /**
     * Trigger event-based update (for memory subsystem)
     */
    fun triggerEvent(subsystem: Subsystem, eventData: Any? = null) {
        if (subsystem != Subsystem.MEMORY && subsystem != Subsystem.PERCEPTION) {
            Timber.w("Event trigger typically used for MEMORY or PERCEPTION, not $subsystem")
        }
        
        val channel = subsystemChannels[subsystem] ?: return
        
        scope.launch {
            channel.send(TimingEvent(
                timestamp = System.nanoTime(),
                type = EventType.EVENT_TRIGGERED,
                subsystem = subsystem,
                data = eventData
            ))
        }
    }
    
    /**
     * Adaptive rate adjustment based on performance
     */
    fun adaptRate(subsystem: Subsystem, performance: PerformanceMetrics) {
        val config = timingConfigs[subsystem] ?: return
        if (!config.adaptive) return
        
        val currentPeriod = config.targetPeriodMs
        var newPeriod = currentPeriod
        
        // Adjust based on CPU usage
        when {
            performance.cpuUsage > 0.9f -> {
                // Reduce rate if overloaded
                newPeriod = (currentPeriod * 1.2f).toLong()
                Timber.w("$subsystem: High CPU, reducing rate")
            }
            performance.cpuUsage < 0.5f && performance.jitterMs < config.jitterToleranceMs -> {
                // Increase rate if underutilized and stable
                newPeriod = (currentPeriod * 0.9f).toLong().coerceAtLeast(4)
            }
        }
        
        // Adjust based on temperature
        if (performance.temperature > 45f) {
            newPeriod = (newPeriod * 1.3f).toLong()
            Timber.w("$subsystem: High temp, reducing rate")
        }
        
        // Apply new timing if changed significantly
        if (kotlin.math.abs(newPeriod - currentPeriod) > 2) {
            timingConfigs[subsystem] = config.copy(targetPeriodMs = newPeriod)
            
            // Restart with new timing
            pauseSubsystem(subsystem)
            resumeSubsystem(subsystem)
            
            Timber.i("$subsystem: Adapted from ${1000f/currentPeriod}Hz to ${1000f/newPeriod}Hz")
        }
    }
    
    /**
     * Synchronize two subsystems (for coordinated actions)
     */
    suspend fun synchronize(
        primary: Subsystem,
        secondary: Subsystem,
        maxDelayMs: Long = 10
    ): Boolean {
        val primaryChannel = subsystemChannels[primary] ?: return false
        val secondaryChannel = subsystemChannels[secondary] ?: return false
        
        // Wait for both to tick within window
        val primaryEvent = primaryChannel.receive()
        
        return withTimeoutOrNull(maxDelayMs) {
            val secondaryEvent = secondaryChannel.receive()
            
            // Check if events are close enough
            val timeDiff = kotlin.math.abs(primaryEvent.timestamp - secondaryEvent.timestamp) / 1_000_000
            timeDiff <= maxDelayMs
        } ?: false
    }
    
    /**
     * Get timing statistics
     */
    fun getStats(): TimingStats {
        val stats = mutableMapOf<Subsystem, SubsystemStats>()
        
        Subsystem.values().forEach { subsystem ->
            val count = cycleCounts[subsystem]?.get() ?: 0
            val lastExec = lastExecutionTimes[subsystem]?.get() ?: 0
            val jitters = jitterMeasurements[subsystem] ?: emptyList()
            
            val avgJitter = if (jitters.isNotEmpty()) {
                jitters.average().toFloat()
            } else 0f
            
            val config = timingConfigs[subsystem]
            
            stats[subsystem] = SubsystemStats(
                cyclesExecuted = count,
                lastExecutionMs = lastExec,
                averageJitterMs = avgJitter,
                targetPeriodMs = config?.targetPeriodMs ?: 0,
                currentRateHz = if (config != null) 1000f / config.targetPeriodMs else 0f,
                isRunning = runningJobs[subsystem]?.isActive == true
            )
        }
        
        return TimingStats(stats)
    }
    
    /**
     * Measure end-to-end latency
     */
    fun measureLatency(startSubsystem: Subsystem, endSubsystem: Subsystem): Long? {
        val start = lastExecutionTimes[startSubsystem]?.get() ?: return null
        val end = lastExecutionTimes[endSubsystem]?.get() ?: return null
        return end - start
    }
    
    // Private methods
    
    private fun initializeDefaultTimings() {
        // Optimized for A21S - conservative defaults
        configureSubsystem(Subsystem.PERCEPTION, 10f, TimingPriority.HIGH, adaptive = true)
        configureSubsystem(Subsystem.MEMORY, 0f, TimingPriority.MEDIUM, adaptive = false)  // Event-based
        configureSubsystem(Subsystem.DECISION, 30f, TimingPriority.HIGH, adaptive = true)
        configureSubsystem(Subsystem.CAMERA, 60f, TimingPriority.CRITICAL, adaptive = false)
        configureSubsystem(Subsystem.GESTURE, 60f, TimingPriority.CRITICAL, adaptive = false)
        configureSubsystem(Subsystem.PREDICTION, 20f, TimingPriority.MEDIUM, adaptive = true)
        configureSubsystem(Subsystem.LEARNING, 0.2f, TimingPriority.LOW, adaptive = true)
        configureSubsystem(Subsystem.HUMANIZATION, 30f, TimingPriority.LOW, adaptive = false)
    }
    
    private fun createChannels() {
        Subsystem.values().forEach { subsystem ->
            subsystemChannels[subsystem] = Channel(Channel.BUFFERED)
            cycleCounts[subsystem] = AtomicLong(0)
            lastExecutionTimes[subsystem] = AtomicLong(0)
            jitterMeasurements[subsystem] = mutableListOf()
        }
    }
    
    private fun startSubsystem(subsystem: Subsystem, config: TimingConfig) {
        val channel = subsystemChannels[subsystem] ?: return
        
        val job = scope.launch(
            context = when (config.priority) {
                TimingPriority.CRITICAL -> Dispatchers.Default + CoroutineName("$subsystem-critical")
                TimingPriority.HIGH -> Dispatchers.Default + CoroutineName("$subsystem-high")
                TimingPriority.MEDIUM -> Dispatchers.Default + CoroutineName("$subsystem-medium")
                TimingPriority.LOW -> Dispatchers.IO + CoroutineName("$subsystem-low")
            }
        ) {
            when (subsystem) {
                Subsystem.MEMORY, Subsystem.PERCEPTION -> runEventLoop(channel, subsystem)
                else -> runPeriodicLoop(channel, subsystem, config)
            }
        }
        
        runningJobs[subsystem] = job
    }
    
    private suspend fun runPeriodicLoop(
        channel: Channel<TimingEvent>,
        subsystem: Subsystem,
        config: TimingConfig
    ) {
        var lastTick = System.nanoTime()
        
        while (isActive && isRunning.get()) {
            val targetTime = lastTick + config.targetPeriodMs * 1_000_000
            val currentTime = System.nanoTime()
            
            // Calculate sleep time
            val sleepNs = targetTime - currentTime
            if (sleepNs > 0) {
                delay(sleepNs / 1_000_000)
            }
            
            // Execute tick
            val executionStart = System.nanoTime()
            channel.send(TimingEvent(
                timestamp = executionStart,
                type = EventType.PERIODIC_TICK,
                subsystem = subsystem
            ))
            
            // Update metrics
            val executionTime = (System.nanoTime() - executionStart) / 1_000_000
            updateMetrics(subsystem, executionTime)
            
            lastTick = targetTime
            
            // Measure jitter
            val actualPeriod = executionStart - (lastTick - config.targetPeriodMs * 1_000_000)
            val jitter = kotlin.math.abs(actualPeriod - config.targetPeriodMs * 1_000_000) / 1_000_000
            recordJitter(subsystem, jitter)
        }
    }
    
    private suspend fun runEventLoop(channel: Channel<TimingEvent>, subsystem: Subsystem) {
        // Event-based subsystems wait for external triggers
        while (isActive && isRunning.get()) {
            delay(100)  // Check periodically
        }
    }
    
    private fun updateMetrics(subsystem: Subsystem, executionTimeMs: Long) {
        cycleCounts[subsystem]?.incrementAndGet()
        lastExecutionTimes[subsystem]?.set(System.currentTimeMillis())
    }
    
    private fun recordJitter(subsystem: Subsystem, jitterMs: Long) {
        val measurements = jitterMeasurements[subsystem] ?: return
        synchronized(measurements) {
            measurements.add(jitterMs)
            if (measurements.size > 100) {
                measurements.removeAt(0)
            }
        }
    }
    
    // Data classes
    
    data class TimingConfig(
        val subsystem: Subsystem,
        val targetPeriodMs: Long,
        val priority: TimingPriority,
        val adaptive: Boolean,
        val jitterToleranceMs: Long
    )
    
    data class TimingEvent(
        val timestamp: Long,
        val type: EventType,
        val subsystem: Subsystem,
        val data: Any? = null
    )
    
    data class PerformanceMetrics(
        val cpuUsage: Float,
        val temperature: Float,
        val jitterMs: Long,
        val droppedFrames: Int
    )
    
    data class TimingStats(
        val subsystems: Map<Subsystem, SubsystemStats>
    ) {
        fun getOverallRate(): Float {
            val running = subsystems.values.count { it.isRunning }
            return if (running > 0) {
                subsystems.values.filter { it.isRunning }
                    .map { it.currentRateHz }
                    .average()
                    .toFloat()
            } else 0f
        }
    }
    
    data class SubsystemStats(
        val cyclesExecuted: Long,
        val lastExecutionMs: Long,
        val averageJitterMs: Float,
        val targetPeriodMs: Long,
        val currentRateHz: Float,
        val isRunning: Boolean
    )
    
    enum class Subsystem {
        PERCEPTION,      // Screen analysis (10Hz, async)
        MEMORY,          // Event-based updates
        DECISION,        // Tactical decisions (30Hz)
        CAMERA,          // Camera control (60Hz, smooth)
        GESTURE,         // Gesture execution (60Hz)
        PREDICTION,      // Enemy prediction (20Hz)
        LEARNING,        // RL updates (0.2Hz)
        HUMANIZATION     // Humanization layer (30Hz)
    }
    
    enum class TimingPriority {
        CRITICAL,   // Camera, Gesture - must not miss deadlines
        HIGH,       // Decision, Perception - important
        MEDIUM,     // Prediction, Memory - can tolerate some delay
        LOW         // Learning, Humanization - background tasks
    }
    
    enum class EventType {
        PERIODIC_TICK,
        EVENT_TRIGGERED,
        SYNCHRONIZED,
        ADAPTIVE_CHANGE
    }
}
