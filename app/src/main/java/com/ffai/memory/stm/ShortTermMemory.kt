package com.ffai.memory.stm

import com.ffai.perception.ScreenAnalyzer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Short-Term Memory (STM) - High-speed circular buffer for recent game state
 * Optimized for A21S: Limited capacity, fast access, automatic eviction
 */
class ShortTermMemory(
    val capacity: Int = 300,  // ~30 seconds at 10fps
    val retentionMs: Long = 30000
) {
    private val buffer = ArrayDeque<GameStateSnapshot>(capacity)
    private val timestamps = ArrayDeque<Long>(capacity)
    private val accessLock = Any()
    
    data class GameStateSnapshot(
        val timestamp: Long = System.currentTimeMillis(),
        val perception: ScreenAnalyzer.PerceptionResult,
        val playerAction: String?,
        val frameHash: Int  // For quick comparison
    )
    
    private val writeCount = AtomicLong(0)
    private val dropCount = AtomicLong(0)
    
    /**
     * Push new state into buffer (thread-safe)
     */
    fun push(perception: ScreenAnalyzer.PerceptionResult, action: String? = null) {
        val snapshot = GameStateSnapshot(
            perception = perception,
            playerAction = action,
            frameHash = calculateFrameHash(perception)
        )
        
        synchronized(accessLock) {
            if (buffer.size >= capacity) {
                buffer.removeFirst()
                timestamps.removeFirst()
                dropCount.incrementAndGet()
            }
            buffer.addLast(snapshot)
            timestamps.addLast(snapshot.timestamp)
            writeCount.incrementAndGet()
            
            // Cleanup old entries beyond retention time
            cleanupOldEntries()
        }
    }
    
    /**
     * Get recent states within time window
     */
    fun recent(windowMs: Long): List<GameStateSnapshot> {
        val cutoff = System.currentTimeMillis() - windowMs
        synchronized(accessLock) {
            return buffer.filter { it.timestamp >= cutoff }
        }
    }
    
    /**
     * Get last N states
     */
    fun last(n: Int = 1): List<GameStateSnapshot> {
        synchronized(accessLock) {
            return buffer.takeLast(n.coerceAtMost(buffer.size))
        }
    }
    
    /**
     * Get current buffer size
     */
    fun size(): Int = synchronized(accessLock) { buffer.size }
    
    /**
     * Check if buffer is full
     */
    fun isFull(): Boolean = synchronized(accessLock) { buffer.size >= capacity }
    
    /**
     * Clear all entries
     */
    fun clear() {
        synchronized(accessLock) {
            buffer.clear()
            timestamps.clear()
        }
    }
    
    /**
     * Get state at specific time (approximate, nearest neighbor)
     */
    fun getAtTime(timestamp: Long): GameStateSnapshot? {
        synchronized(accessLock) {
            return buffer.minByOrNull { kotlin.math.abs(it.timestamp - timestamp) }
        }
    }
    
    /**
     * Get statistics
     */
    fun getStats(): Stats {
        return Stats(
            currentSize = size(),
            capacity = capacity,
            totalWrites = writeCount.get(),
            totalDrops = dropCount.get(),
            oldestTimestamp = synchronized(accessLock) { timestamps.firstOrNull() },
            newestTimestamp = synchronized(accessLock) { timestamps.lastOrNull() }
        )
    }
    
    data class Stats(
        val currentSize: Int,
        val capacity: Int,
        val totalWrites: Long,
        val totalDrops: Long,
        val oldestTimestamp: Long?,
        val newestTimestamp: Long?
    )
    
    private fun cleanupOldEntries() {
        val cutoff = System.currentTimeMillis() - retentionMs
        while (timestamps.isNotEmpty() && timestamps.first() < cutoff) {
            buffer.removeFirst()
            timestamps.removeFirst()
        }
    }
    
    private fun calculateFrameHash(perception: ScreenAnalyzer.PerceptionResult): Int {
        // Simple hash for quick comparison
        var hash = perception.detectedEnemies.size * 31
        hash += perception.playerState.health
        hash += (perception.playerState.ammoCount * 17)
        return hash
    }
    
    /**
     * Detect significant state changes
     */
    fun detectChanges(threshold: Int = 100): List<StateChange> {
        val changes = mutableListOf<StateChange>()
        synchronized(accessLock) {
            if (buffer.size < 2) return changes
            
            val iterator = buffer.iterator()
            var previous = iterator.next()
            
            while (iterator.hasNext()) {
                val current = iterator.next()
                val hashDiff = kotlin.math.abs(current.frameHash - previous.frameHash)
                
                if (hashDiff > threshold) {
                    changes.add(StateChange(
                        timestamp = current.timestamp,
                        previousState = previous,
                        currentState = current,
                        magnitude = hashDiff
                    ))
                }
                previous = current
            }
        }
        return changes
    }
    
    data class StateChange(
        val timestamp: Long,
        val previousState: GameStateSnapshot,
        val currentState: GameStateSnapshot,
        val magnitude: Int
    )
}
