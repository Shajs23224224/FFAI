package com.ffai.memory.ultra

import com.ffai.perception.ScreenAnalyzer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Ultra-Short Term Memory (USTM) - Sub-second immediate reaction memory
 * Window: milliseconds to few seconds
 * Purpose: Instant reaction to immediate threats and opportunities
 * Optimized for A21S: Fixed-size circular buffer, lock-free reads
 */
class UltraShortTermMemory(
    private val capacity: Int = 50,  // ~5 seconds at 10fps
    private val retentionMs: Long = 5000  // 5 second window
) {
    // Lock-free circular buffer for ultra-fast access
    private val buffer = ArrayDeque<UltraFastSnapshot>(capacity)
    private val timestampQueue = ConcurrentLinkedQueue<Long>()
    
    // O(1) access cache for critical memories
    private val criticalCache = mutableMapOf<String, UltraFastSnapshot>()
    private val maxCriticalCacheSize = 10
    
    // Metrics
    private val accessCount = AtomicLong(0)
    private val hitCount = AtomicLong(0)
    private val lastAccessTime = AtomicLong(0)
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cleanupJob: Job? = null
    
    init {
        startCleanupLoop()
    }
    
    /**
     * Push new state with O(1) insertion
     */
    fun push(perception: ScreenAnalyzer.PerceptionResult, immediateContext: ImmediateContext) {
        val snapshot = UltraFastSnapshot(
            timestamp = System.currentTimeMillis(),
            perception = perception,
            context = immediateContext,
            priority = calculatePriority(perception, immediateContext),
            criticalFlags = extractCriticalFlags(perception, immediateContext)
        )
        
        synchronized(buffer) {
            if (buffer.size >= capacity) {
                val old = buffer.removeFirst()
                timestampQueue.poll()
                
                // Remove from critical cache if it was critical
                if (old.priority >= Priority.CRITICAL) {
                    criticalCache.entries.removeIf { it.value.timestamp == old.timestamp }
                }
            }
            
            buffer.addLast(snapshot)
            timestampQueue.offer(snapshot.timestamp)
            
            // O(1) cache for critical memories
            if (snapshot.priority >= Priority.HIGH) {
                addToCriticalCache(snapshot)
            }
        }
        
        Timber.v("USTM push: priority=${snapshot.priority}, criticalFlags=${snapshot.criticalFlags}")
    }
    
    /**
     * O(1) access to most recent critical memory
     */
    fun getMostRecentCritical(): UltraFastSnapshot? {
        accessCount.incrementAndGet()
        lastAccessTime.set(System.currentTimeMillis())
        
        return synchronized(buffer) {
            buffer.lastOrNull { it.priority >= Priority.HIGH }
        }
    }
    
    /**
     * O(1) access by critical flag (e.g., "enemy_very_close", "low_health")
     */
    fun getByCriticalFlag(flag: String): UltraFastSnapshot? {
        accessCount.incrementAndGet()
        
        // O(1) cache lookup first
        criticalCache[flag]?.let {
            if (isStillValid(it)) {
                hitCount.incrementAndGet()
                return it
            }
        }
        
        // Fallback to scan (rare)
        return synchronized(buffer) {
            buffer.findLast { it.criticalFlags.contains(flag) && isStillValid(it) }
        }
    }
    
    /**
     * Get immediate reaction context (what just happened)
     */
    fun getImmediateContext(windowMs: Long = 1000): ImmediateReactionContext {
        val cutoff = System.currentTimeMillis() - windowMs
        
        val recent = synchronized(buffer) {
            buffer.filter { it.timestamp >= cutoff }
        }
        
        return ImmediateReactionContext(
            recentSnapshots = recent,
            threatDetected = recent.any { it.criticalFlags.contains("enemy_detected") },
            healthDrop = calculateHealthDrop(recent),
            positionChange = calculatePositionChange(recent),
            actionJustTaken = recent.lastOrNull()?.context?.lastAction,
            consecutiveFailures = detectConsecutiveFailures(recent),
            reactionWindowOpen = recent.size >= 2 && 
                (recent.last().timestamp - recent.first().timestamp) < 500
        )
    }
    
    /**
     * Instant threat detection (O(1))
     */
    fun isImmediateThreat(): Boolean {
        return getByCriticalFlag("enemy_very_close") != null ||
               getByCriticalFlag("health_critical") != null ||
               getByCriticalFlag("under_fire") != null
    }
    
    /**
     * Get trend in last milliseconds (velocity, acceleration)
     */
    fun getInstantTrend(metric: TrendMetric, windowMs: Long = 500): TrendResult {
        val cutoff = System.currentTimeMillis() - windowMs
        
        val samples = synchronized(buffer) {
            buffer.filter { it.timestamp >= cutoff }.toList()
        }
        
        if (samples.size < 2) return TrendResult.STABLE
        
        return when (metric) {
            TrendMetric.ENEMY_DISTANCE -> analyzeDistanceTrend(samples)
            TrendMetric.HEALTH -> analyzeHealthTrend(samples)
            TrendMetric.ENEMY_COUNT -> analyzeEnemyCountTrend(samples)
            TrendMetric.AIM_QUALITY -> analyzeAimTrend(samples)
        }
    }
    
    /**
     * Context-aware search (fast approximation)
     */
    fun findByContext(
        mapId: String? = null,
        weaponType: String? = null,
        healthRange: ClosedFloatingPointRange<Float>? = null,
        enemyCount: Int? = null
    ): List<UltraFastSnapshot> {
        accessCount.incrementAndGet()
        
        return synchronized(buffer) {
            buffer.filter { snapshot ->
                var match = true
                
                mapId?.let { match = match && snapshot.context.mapId == it }
                weaponType?.let { match = match && snapshot.context.currentWeapon == it }
                healthRange?.let { match = match && 
                    snapshot.perception.playerState.health / 100f in it }
                enemyCount?.let { match = match && 
                    snapshot.perception.detectedEnemies.size == it }
                
                match && isStillValid(snapshot)
            }
        }
    }
    
    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            timestampQueue.clear()
            criticalCache.clear()
        }
    }
    
    fun getStats(): Stats {
        return Stats(
            totalEntries = synchronized(buffer) { buffer.size },
            capacity = capacity,
            criticalCacheSize = criticalCache.size,
            totalAccesses = accessCount.get(),
            cacheHits = hitCount.get(),
            hitRate = if (accessCount.get() > 0) {
                hitCount.get().toFloat() / accessCount.get()
            } else 0f,
            oldestTimestamp = synchronized(buffer) { buffer.firstOrNull()?.timestamp },
            newestTimestamp = synchronized(buffer) { buffer.lastOrNull()?.timestamp }
        )
    }
    
    fun close() {
        cleanupJob?.cancel()
        clear()
    }
    
    // Private methods
    
    private fun calculatePriority(
        perception: ScreenAnalyzer.PerceptionResult,
        context: ImmediateContext
    ): Priority {
        var score = 0
        
        // Enemy proximity
        perception.detectedEnemies.forEach { enemy ->
            when {
                enemy.distance < 5 -> score += 4  // Very close
                enemy.distance < 15 -> score += 2  // Close
                enemy.distance < 30 -> score += 1  // Medium
            }
            if (enemy.isFiring) score += 3
            if (enemy.isMovingTowards) score += 2
        }
        
        // Health status
        when {
            perception.playerState.health < 20 -> score += 4
            perception.playerState.health < 50 -> score += 2
            perception.playerState.health < 75 -> score += 1
        }
        
        // Ammo status
        if (perception.playerState.ammoCount == 0) score += 3
        else if (perception.playerState.ammoCount < 10) score += 1
        
        // Safe zone
        if (!perception.safeZone.isInside) score += 2
        
        return when {
            score >= 7 -> Priority.CRITICAL
            score >= 4 -> Priority.HIGH
            score >= 2 -> Priority.MEDIUM
            else -> Priority.LOW
        }
    }
    
    private fun extractCriticalFlags(
        perception: ScreenAnalyzer.PerceptionResult,
        context: ImmediateContext
    ): Set<String> {
        val flags = mutableSetOf<String>()
        
        perception.detectedEnemies.forEach { enemy ->
            when {
                enemy.distance < 5 -> flags.add("enemy_very_close")
                enemy.distance < 15 -> flags.add("enemy_close")
            }
            if (enemy.isFiring) flags.add("under_fire")
            if (enemy.threatLevel > 0.7f) flags.add("high_threat")
        }
        
        when {
            perception.playerState.health < 20 -> flags.add("health_critical")
            perception.playerState.health < 50 -> flags.add("health_low")
        }
        
        if (perception.playerState.ammoCount == 0) flags.add("no_ammo")
        if (!perception.safeZone.isInside) flags.add("outside_zone")
        if (context.inCombat) flags.add("in_combat")
        
        return flags
    }
    
    private fun addToCriticalCache(snapshot: UltraFastSnapshot) {
        snapshot.criticalFlags.forEach { flag ->
            criticalCache[flag] = snapshot
        }
        
        // Maintain cache size
        while (criticalCache.size > maxCriticalCacheSize) {
            val oldest = criticalCache.entries.minByOrNull { it.value.timestamp }
            oldest?.let { criticalCache.remove(it.key) }
        }
    }
    
    private fun isStillValid(snapshot: UltraFastSnapshot): Boolean {
        return System.currentTimeMillis() - snapshot.timestamp < retentionMs
    }
    
    private fun calculateHealthDrop(samples: List<UltraFastSnapshot>): Float {
        if (samples.size < 2) return 0f
        val first = samples.first().perception.playerState.health
        val last = samples.last().perception.playerState.health
        return (first - last).toFloat()
    }
    
    private fun calculatePositionChange(samples: List<UltraFastSnapshot>): Pair<Float, Float> {
        if (samples.size < 2) return Pair(0f, 0f)
        val first = samples.first().context.position
        val last = samples.last().context.position
        return Pair(last.first - first.first, last.second - first.second)
    }
    
    private fun detectConsecutiveFailures(samples: List<UltraFastSnapshot>): FailurePattern? {
        if (samples.size < 3) return null
        
        val actions = samples.mapNotNull { it.context.lastAction }
        if (actions.size < 3) return null
        
        // Detect repeated failure pattern
        val lastThree = actions.takeLast(3)
        if (lastThree[0] == lastThree[1] && lastThree[1] == lastThree[2]) {
            return FailurePattern(
                action = lastThree[0],
                count = actions.count { it == lastThree[0] },
                consecutive = true
            )
        }
        
        return null
    }
    
    private fun analyzeDistanceTrend(samples: List<UltraFastSnapshot>): TrendResult {
        val distances = samples.map { snapshot ->
            snapshot.perception.detectedEnemies.firstOrNull()?.distance ?: Float.MAX_VALUE
        }
        
        if (distances.size < 2) return TrendResult.STABLE
        
        val first = distances.first()
        val last = distances.last()
        val delta = last - first
        
        return when {
            delta < -5 -> TrendResult.DECREASING_FAST  // Enemy getting closer
            delta < -2 -> TrendResult.DECREASING
            delta > 5 -> TrendResult.INCREASING_FAST    // Enemy moving away
            delta > 2 -> TrendResult.INCREASING
            else -> TrendResult.STABLE
        }
    }
    
    private fun analyzeHealthTrend(samples: List<UltraFastSnapshot>): TrendResult {
        val healths = samples.map { it.perception.playerState.health.toFloat() }
        if (healths.size < 2) return TrendResult.STABLE
        
        val delta = healths.last() - healths.first()
        return when {
            delta < -15 -> TrendResult.DECREASING_FAST
            delta < -5 -> TrendResult.DECREASING
            delta > 15 -> TrendResult.INCREASING_FAST
            delta > 5 -> TrendResult.INCREASING
            else -> TrendResult.STABLE
        }
    }
    
    private fun analyzeEnemyCountTrend(samples: List<UltraFastSnapshot>): TrendResult {
        val counts = samples.map { it.perception.detectedEnemies.size }
        if (counts.size < 2) return TrendResult.STABLE
        
        val delta = counts.last() - counts.first()
        return when {
            delta > 1 -> TrendResult.INCREASING_FAST
            delta > 0 -> TrendResult.INCREASING
            delta < -1 -> TrendResult.DECREASING_FAST
            delta < 0 -> TrendResult.DECREASING
            else -> TrendResult.STABLE
        }
    }
    
    private fun analyzeAimTrend(samples: List<UltraFastSnapshot>): TrendResult {
        // Analyze aim quality trend
        return TrendResult.STABLE  // Simplified - would need aim quality metric
    }
    
    private fun startCleanupLoop() {
        cleanupJob = scope.launch {
            while (isActive) {
                delay(1000)  // Every second
                cleanupExpired()
            }
        }
    }
    
    private fun cleanupExpired() {
        val cutoff = System.currentTimeMillis() - retentionMs
        
        synchronized(buffer) {
            while (buffer.isNotEmpty() && buffer.first().timestamp < cutoff) {
                val removed = buffer.removeFirst()
                timestampQueue.poll()
                
                criticalCache.entries.removeIf { it.value.timestamp == removed.timestamp }
            }
        }
    }
    
    // Data classes
    
    data class UltraFastSnapshot(
        val timestamp: Long,
        val perception: ScreenAnalyzer.PerceptionResult,
        val context: ImmediateContext,
        val priority: Priority,
        val criticalFlags: Set<String>
    )
    
    data class ImmediateContext(
        val mapId: String,
        val currentWeapon: String,
        val position: Pair<Float, Float>,
        val velocity: Pair<Float, Float>,
        val lastAction: String?,
        val inCombat: Boolean,
        val frameNumber: Int
    )
    
    data class ImmediateReactionContext(
        val recentSnapshots: List<UltraFastSnapshot>,
        val threatDetected: Boolean,
        val healthDrop: Float,
        val positionChange: Pair<Float, Float>,
        val actionJustTaken: String?,
        val consecutiveFailures: FailurePattern?,
        val reactionWindowOpen: Boolean
    )
    
    data class FailurePattern(
        val action: String,
        val count: Int,
        val consecutive: Boolean
    )
    
    enum class Priority {
        LOW, MEDIUM, HIGH, CRITICAL
    }
    
    enum class TrendMetric {
        ENEMY_DISTANCE, HEALTH, ENEMY_COUNT, AIM_QUALITY
    }
    
    enum class TrendResult {
        INCREASING_FAST, INCREASING, STABLE, DECREASING, DECREASING_FAST
    }
    
    data class Stats(
        val totalEntries: Int,
        val capacity: Int,
        val criticalCacheSize: Int,
        val totalAccesses: Long,
        val cacheHits: Long,
        val hitRate: Float,
        val oldestTimestamp: Long?,
        val newestTimestamp: Long?
    )
}
