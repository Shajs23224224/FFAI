package com.ffai.memory.retrieval

import com.ffai.memory.ltm.LongTermMemory
import com.ffai.memory.mtm.MediumTermMemory
import com.ffai.memory.stm.ShortTermMemory
import com.ffai.memory.ultra.UltraShortTermMemory
import com.ffai.perception.ScreenAnalyzer
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Memory Retrieval Engine - Fast context-aware memory search
 * Features: O(1) critical access, similarity search, context-based retrieval
 */
class MemoryRetrievalEngine(
    private val ustm: UltraShortTermMemory,
    private val stm: ShortTermMemory,
    private val mtm: MediumTermMemory,
    private val ltm: LongTermMemory
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Index structures for fast lookup
    private val contextIndex = ConcurrentHashMap<String, MutableList<MemoryPointer>>()
    private val similarityIndex = ConcurrentHashMap<String, FloatArray>()
    private val priorityCache = PriorityBlockingQueue<IndexedMemory>(100) { a, b ->
        b.priorityScore.compareTo(a.priorityScore)
    }
    
    // O(1) access caches
    private val criticalMemoryCache = ConcurrentHashMap<String, Any>()
    private val recentContextCache = mutableMapOf<String, CurrentContext>()
    
    // Search configuration
    private val maxSearchResults = 20
    private val similarityThreshold = 0.7f
    private val searchTimeoutMs = 50L  // Max time for search
    
    init {
        startIndexingLoop()
    }
    
    /**
     * O(1) access to critical memories
     */
    fun <T> getCritical(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return criticalMemoryCache[key] as? T
    }
    
    /**
     * Search by exact context match (fast O(1) average)
     */
    fun searchByContext(contextQuery: ContextQuery): List<RetrievedMemory> {
        val startTime = System.nanoTime()
        
        val results = mutableListOf<RetrievedMemory>()
        
        // Build composite key from context
        val keys = buildContextKeys(contextQuery)
        
        // O(1) lookup per key
        keys.forEach { key ->
            contextIndex[key]?.let { pointers ->
                pointers.forEach { pointer ->
                    val memory = retrieveByPointer(pointer)
                    memory?.let {
                        results.add(RetrievedMemory(
                            memory = it,
                            relevanceScore = calculateRelevance(it, contextQuery),
                            accessSpeed = "O(1)",
                            sourceLayer = pointer.layer
                        ))
                    }
                }
            }
        }
        
        // Sort by relevance and return top N
        val sorted = results.sortedByDescending { it.relevanceScore }.take(maxSearchResults)
        
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        Timber.v("Context search: ${sorted.size} results in ${elapsedMs}ms")
        
        return sorted
    }
    
    /**
     * Similarity search using cosine similarity
     */
    fun searchBySimilarity(
        currentSituation: SituationVector,
        minSimilarity: Float = similarityThreshold
    ): List<RetrievedMemory> {
        val startTime = System.nanoTime()
        val results = mutableListOf<RetrievedMemory>()
        
        // Search across all layers
        val allVectors = gatherAllVectors()
        
        allVectors.forEach { (pointer, vector) ->
            val similarity = cosineSimilarity(currentSituation.toVector(), vector)
            if (similarity >= minSimilarity) {
                val memory = retrieveByPointer(pointer)
                memory?.let {
                    results.add(RetrievedMemory(
                        memory = it,
                        relevanceScore = similarity,
                        accessSpeed = "O(n)",
                        sourceLayer = pointer.layer
                    ))
                }
            }
        }
        
        val sorted = results.sortedByDescending { it.relevanceScore }.take(maxSearchResults)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        
        Timber.v("Similarity search: ${sorted.size} results in ${elapsedMs}ms")
        return sorted
    }
    
    /**
     * Find similar situations to current context
     */
    fun findSimilarSituations(limit: Int = 5): List<SituationMatch> {
        val currentContext = buildCurrentContext()
        
        // Search LTM for player profiles with similar behavior
        val allProfiles = ltm.getAllPlayerProfiles()
        
        return allProfiles.map { profile ->
            val similarity = calculateProfileSimilarity(currentContext, profile)
            SituationMatch(
                profile = profile,
                similarityScore = similarity,
                relevantPatterns = extractRelevantPatterns(profile, currentContext)
            )
        }
        .sortedByDescending { it.similarityScore }
        .take(limit)
    }
    
    /**
     * Get memories prioritized by relevance, recency, and impact
     */
    fun getPrioritizedMemories(
        context: CurrentContext,
        count: Int = 10
    ): List<PrioritizedMemory> {
        val memories = mutableListOf<PrioritizedMemory>()
        
        // Score and collect from all layers
        collectFromUSTM(context, memories)
        collectFromSTM(context, memories)
        collectFromMTM(context, memories)
        collectFromLTM(context, memories)
        
        // Sort by composite score
        return memories.sortedByDescending { 
            calculateCompositeScore(it, context) 
        }.take(count)
    }
    
    /**
     * Transfer learning: find transferable knowledge between similar situations
     */
    fun findTransferableKnowledge(
        sourceContext: ContextQuery,
        targetContext: ContextQuery
    ): List<TransferableKnowledge> {
        val sourceMemories = searchByContext(sourceContext)
        val transferable = mutableListOf<TransferableKnowledge>()
        
        sourceMemories.forEach { memory ->
            val applicability = assessApplicability(memory, targetContext)
            if (applicability > 0.6f) {
                transferable.add(TransferableKnowledge(
                    sourceMemory = memory,
                    targetContext = targetContext,
                    applicabilityScore = applicability,
                    adaptationNeeded = calculateAdaptation(memory, targetContext)
                ))
            }
        }
        
        return transferable.sortedByDescending { it.applicabilityScore }
    }
    
    /**
     * Compress and consolidate similar memories
     */
    fun compressSimilarMemories(threshold: Float = 0.85f): CompressionResult {
        val startTime = System.nanoTime()
        val consolidated = mutableListOf<ConsolidatedMemory>()
        val duplicatesRemoved = mutableListOf<MemoryPointer>()
        
        // Group similar memories
        val allVectors = gatherAllVectors()
        val groups = mutableListOf<MutableList<Pair<MemoryPointer, FloatArray>>>()
        
        allVectors.forEach { (pointer, vector) ->
            var added = false
            for (group in groups) {
                val similarity = cosineSimilarity(vector, group.first().second)
                if (similarity >= threshold) {
                    group.add(pointer to vector)
                    added = true
                    break
                }
            }
            if (!added) {
                groups.add(mutableListOf(pointer to vector))
            }
        }
        
        // Consolidate each group
        groups.filter { it.size > 1 }.forEach { group ->
            val representative = createConsolidatedMemory(group)
            consolidated.add(representative)
            
            // Mark duplicates for removal
            group.drop(1).forEach { duplicatesRemoved.add(it.first) }
        }
        
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        
        return CompressionResult(
            groupsFound = groups.size,
            consolidated = consolidated.size,
            duplicatesRemoved = duplicatesRemoved.size,
            timeMs = elapsedMs
        )
    }
    
    /**
     * Selective forgetting based on priority and age
     */
    fun selectiveForgetting(
        maxAgeMs: Long = 86400000,  // 24 hours
        minPriority: Float = 0.3f
    ): ForgettingResult {
        val forgotten = mutableListOf<String>()
        val retained = mutableListOf<String>()
        val currentTime = System.currentTimeMillis()
        
        // Scan and evaluate all indexed memories
        val iterator = priorityCache.iterator()
        while (iterator.hasNext()) {
            val indexed = iterator.next()
            val age = currentTime - indexed.timestamp
            
            val shouldForget = when {
                age > maxAgeMs && indexed.priorityScore < minPriority -> true
                indexed.accessCount == 0 && age > maxAgeMs / 2 -> true
                indexed.impactScore < 0.1f && age > maxAgeMs / 4 -> true
                else -> false
            }
            
            if (shouldForget) {
                forgotten.add(indexed.id)
                iterator.remove()
                removeFromIndices(indexed)
            } else {
                retained.add(indexed.id)
            }
        }
        
        return ForgettingResult(
            totalForgotten = forgotten.size,
            totalRetained = retained.size,
            forgottenIds = forgotten,
            memoryFreedEstimate = forgotten.size * 1024  // Approximate bytes
        )
    }
    
    /**
     * Predict next likely situation based on memory patterns
     */
    fun predictNextSituation(
        currentContext: CurrentContext,
        timeHorizonMs: Long = 5000
    ): SituationPrediction {
        // Find similar past sequences
        val similarSequences = findSimilarSequences(currentContext)
        
        // Analyze what happened next in those sequences
        val nextSituations = mutableMapOf<String, Int>()
        similarSequences.forEach { sequence ->
            val next = sequence.getOrNull(1)
            next?.let {
                nextSituations[it.situationType] = 
                    nextSituations.getOrDefault(it.situationType, 0) + 1
            }
        }
        
        // Calculate probabilities
        val total = similarSequences.size.toFloat()
        val predictions = nextSituations.map { (type, count) ->
            PredictedSituation(
                situationType = type,
                probability = count / total,
                confidence = calculateConfidence(count, total.toInt())
            )
        }.sortedByDescending { it.probability }
        
        return SituationPrediction(
            mostLikely = predictions.firstOrNull(),
            allPredictions = predictions,
            predictionHorizonMs = timeHorizonMs,
            basedOnSequences = similarSequences.size
        )
    }
    
    // Private helper methods
    
    private fun buildContextKeys(query: ContextQuery): List<String> {
        val keys = mutableListOf<String>()
        
        query.mapId?.let { keys.add("map:$it") }
        query.weaponType?.let { keys.add("weapon:$it") }
        query.healthRange?.let { 
            keys.add("health:${it.start.toInt()}-${it.endInclusive.toInt()}") 
        }
        query.enemyCount?.let { keys.add("enemies:$it") }
        query.phase?.let { keys.add("phase:$it") }
        query.riskLevel?.let { keys.add("risk:$it") }
        
        // Composite keys
        if (query.mapId != null && query.weaponType != null) {
            keys.add("${query.mapId}:${query.weaponType}")
        }
        
        return keys
    }
    
    private fun retrieveByPointer(pointer: MemoryPointer): Any? {
        return when (pointer.layer) {
            MemoryLayer.USTM -> null  // USTM doesn't persist
            MemoryLayer.STM -> stm.last(1).firstOrNull()
            MemoryLayer.MTM -> mtm.getEnemyPattern(pointer.id)
            MemoryLayer.LTM -> ltm.loadPlayerProfile(pointer.id)
        }
    }
    
    private fun calculateRelevance(memory: Any, query: ContextQuery): Float {
        var score = 0.5f
        
        // Boost score based on matching fields
        when (memory) {
            is LongTermMemory.PlayerProfile -> {
                query.weaponType?.let {
                    if (memory.preferredWeapons.contains(it)) score += 0.2f
                }
            }
            is MediumTermMemory.EnemyPattern -> {
                if (memory.aggressionScore > 0.7f && query.riskLevel == "high") {
                    score += 0.3f
                }
            }
        }
        
        return score.coerceIn(0f, 1f)
    }
    
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in a.indices.coerceAtMost(b.indices)) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        return if (normA > 0 && normB > 0) {
            dot / (sqrt(normA) * sqrt(normB))
        } else 0f
    }
    
    private fun gatherAllVectors(): List<Pair<MemoryPointer, FloatArray>> {
        val vectors = mutableListOf<Pair<MemoryPointer, FloatArray>>()
        
        similarityIndex.forEach { (id, vector) ->
            val layer = determineLayerFromId(id)
            vectors.add(MemoryPointer(id, layer) to vector)
        }
        
        return vectors
    }
    
    private fun determineLayerFromId(id: String): MemoryLayer {
        return when {
            id.startsWith("player_") -> MemoryLayer.LTM
            id.startsWith("pattern_") -> MemoryLayer.MTM
            else -> MemoryLayer.STM
        }
    }
    
    private fun buildCurrentContext(): CurrentContext {
        return CurrentContext(
            mapId = recentContextCache["current_map"]?.mapId ?: "unknown",
            health = recentContextCache["current_health"]?.health ?: 100f,
            ammo = recentContextCache["current_ammo"]?.ammo ?: 0,
            enemiesNearby = recentContextCache["current_enemies"]?.enemyCount ?: 0,
            weaponType = recentContextCache["current_weapon"]?.weaponType ?: "unknown",
            timestamp = System.currentTimeMillis()
        )
    }
    
    private fun calculateProfileSimilarity(
        context: CurrentContext,
        profile: LongTermMemory.PlayerProfile
    ): Float {
        val factors = mutableListOf<Float>()
        
        // Compare aggression level
        val currentAggression = detectCurrentAggression()
        factors.add(1f - abs(currentAggression - profile.averageAggression))
        
        // Compare weapon preference
        val weaponMatch = if (context.weaponType in profile.preferredWeapons) 1f else 0.5f
        factors.add(weaponMatch)
        
        // Time of day similarity
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeMatch = if (currentHour in profile.activeTimes) 1f else 0.7f
        factors.add(timeMatch)
        
        return factors.average().toFloat()
    }
    
    private fun detectCurrentAggression(): Float {
        // Detect current playstyle from USTM
        return 0.5f  // Simplified
    }
    
    private fun extractRelevantPatterns(
        profile: LongTermMemory.PlayerProfile,
        context: CurrentContext
    ): List<String> {
        val patterns = mutableListOf<String>()
        
        if (profile.isRusher && context.enemiesNearby > 0) {
            patterns.add("rusher_behavior")
        }
        if (profile.isCamper && context.enemiesNearby == 0) {
            patterns.add("camper_behavior")
        }
        if (profile.averageAccuracy > 0.7f) {
            patterns.add("high_accuracy")
        }
        
        return patterns
    }
    
    private fun calculateCompositeScore(
        memory: PrioritizedMemory,
        context: CurrentContext
    ): Float {
        // Relevance + Recency + Impact + Success rate
        val relevance = memory.relevance
        val recency = exp(-0.001 * (System.currentTimeMillis() - memory.timestamp))
        val impact = memory.impact
        val success = memory.successRate
        
        return (relevance * 0.3f + recency * 0.25f + impact * 0.25f + success * 0.2f)
    }
    
    private fun assessApplicability(
        memory: RetrievedMemory,
        targetContext: ContextQuery
    ): Float {
        // Assess how applicable a memory is to a new context
        var score = memory.relevanceScore
        
        // Penalize if contexts are very different
        targetContext.mapId?.let { targetMap ->
            // Would need to compare map similarity
            // Simplified: penalize different maps
            score *= 0.8f
        }
        
        return score
    }
    
    private fun calculateAdaptation(
        memory: RetrievedMemory,
        targetContext: ContextQuery
    ): AdaptationNeeded {
        return AdaptationNeeded(
            weaponAdjustment = memory.sourceLayer != MemoryLayer.LTM,
            positionOffset = Pair(0f, 0f),  // Would calculate from map differences
            timingAdjustment = 0f
        )
    }
    
    private fun createConsolidatedMemory(
        group: List<Pair<MemoryPointer, FloatArray>>
    ): ConsolidatedMemory {
        // Create representative memory from group
        val representativeVector = group.map { it.second }
            .reduce { acc, vec -> 
                FloatArray(acc.size) { i -> (acc[i] + vec[i]) / 2f }
            }
        
        return ConsolidatedMemory(
            id = "consolidated_${System.currentTimeMillis()}",
            count = group.size,
            representativeVector = representativeVector,
            sourcePointers = group.map { it.first }
        )
    }
    
    private fun removeFromIndices(indexed: IndexedMemory) {
        contextIndex.values.forEach { list ->
            list.removeIf { it.id == indexed.id }
        }
        similarityIndex.remove(indexed.id)
    }
    
    private fun findSimilarSequences(context: CurrentContext): List<List<Any>> {
        // Find sequences in MTM/LTM that are similar to current context
        // Simplified implementation
        return emptyList()
    }
    
    private fun calculateConfidence(count: Int, total: Int): Float {
        return when {
            total < 5 -> 0.3f
            count < 2 -> 0.4f
            count.toFloat() / total < 0.3f -> 0.5f
            count.toFloat() / total < 0.5f -> 0.7f
            else -> 0.9f
        }
    }
    
    private fun collectFromUSTM(context: CurrentContext, memories: MutableList<PrioritizedMemory>) {
        val ustmContext = ustm.getImmediateContext(2000)
        ustmContext.recentSnapshots.forEach { snapshot ->
            memories.add(PrioritizedMemory(
                memory = snapshot,
                relevance = snapshot.priority.ordinal / 4f,
                recency = 1f,  // Very recent
                impact = if (snapshot.criticalFlags.isNotEmpty()) 1f else 0.5f,
                successRate = 0.5f,  // Unknown for USTM
                timestamp = snapshot.timestamp,
                layer = MemoryLayer.USTM
            ))
        }
    }
    
    private fun collectFromSTM(context: CurrentContext, memories: MutableList<PrioritizedMemory>) {
        val recent = stm.recent(10000)
        recent.forEach { snapshot ->
            memories.add(PrioritizedMemory(
                memory = snapshot,
                relevance = 0.6f,
                recency = 0.8f,
                impact = 0.4f,
                successRate = 0.5f,
                timestamp = snapshot.timestamp,
                layer = MemoryLayer.STM
            ))
        }
    }
    
    private fun collectFromMTM(context: CurrentContext, memories: MutableList<PrioritizedMemory>) {
        val recentDeaths = mtm.getRecentDeaths(10)
        recentDeaths.forEach { death ->
            memories.add(PrioritizedMemory(
                memory = death,
                relevance = 0.8f,
                recency = 0.6f,
                impact = 1f,  // Death is high impact
                successRate = 0f,  // Death = failure
                timestamp = death.timestamp,
                layer = MemoryLayer.MTM
            ))
        }
    }
    
    private fun collectFromLTM(context: CurrentContext, memories: MutableList<PrioritizedMemory>) {
        // Get relevant player profiles
        val profiles = ltm.getAllPlayerProfiles().take(5)
        profiles.forEach { profile ->
            memories.add(PrioritizedMemory(
                memory = profile,
                relevance = calculateProfileSimilarity(context, profile),
                recency = 0.3f,  // LTM is less recent
                impact = profile.threatLevel,
                successRate = profile.skillRating,
                timestamp = profile.lastEncounter,
                layer = MemoryLayer.LTM
            ))
        }
    }
    
    private fun startIndexingLoop() {
        scope.launch {
            while (isActive) {
                delay(5000)  // Re-index every 5 seconds
                updateIndices()
            }
        }
    }
    
    private fun updateIndices() {
        // Update similarity index with new data from STM/MTM/LTM
        // Simplified - would need actual implementation
    }
    
    // Data classes
    
    data class ContextQuery(
        val mapId: String? = null,
        val weaponType: String? = null,
        val healthRange: ClosedFloatingPointRange<Float>? = null,
        val enemyCount: Int? = null,
        val phase: String? = null,
        val riskLevel: String? = null,
        val position: Pair<Float, Float>? = null
    )
    
    data class SituationVector(
        val health: Float,
        val ammo: Float,
        val enemyDistance: Float,
        val enemyCount: Float,
        val inCover: Float,
        val zoneDistance: Float
    ) {
        fun toVector(): FloatArray = floatArrayOf(
            health, ammo, enemyDistance, enemyCount, inCover, zoneDistance
        )
    }
    
    data class RetrievedMemory(
        val memory: Any,
        val relevanceScore: Float,
        val accessSpeed: String,
        val sourceLayer: MemoryLayer
    )
    
    data class PrioritizedMemory(
        val memory: Any,
        val relevance: Float,
        val recency: Float,
        val impact: Float,
        val successRate: Float,
        val timestamp: Long,
        val layer: MemoryLayer
    )
    
    data class SituationMatch(
        val profile: LongTermMemory.PlayerProfile,
        val similarityScore: Float,
        val relevantPatterns: List<String>
    )
    
    data class TransferableKnowledge(
        val sourceMemory: RetrievedMemory,
        val targetContext: ContextQuery,
        val applicabilityScore: Float,
        val adaptationNeeded: AdaptationNeeded
    )
    
    data class AdaptationNeeded(
        val weaponAdjustment: Boolean,
        val positionOffset: Pair<Float, Float>,
        val timingAdjustment: Float
    )
    
    data class CompressionResult(
        val groupsFound: Int,
        val consolidated: Int,
        val duplicatesRemoved: Int,
        val timeMs: Long
    )
    
    data class ConsolidatedMemory(
        val id: String,
        val count: Int,
        val representativeVector: FloatArray,
        val sourcePointers: List<MemoryPointer>
    )
    
    data class ForgettingResult(
        val totalForgotten: Int,
        val totalRetained: Int,
        val forgottenIds: List<String>,
        val memoryFreedEstimate: Int
    )
    
    data class SituationPrediction(
        val mostLikely: PredictedSituation?,
        val allPredictions: List<PredictedSituation>,
        val predictionHorizonMs: Long,
        val basedOnSequences: Int
    )
    
    data class PredictedSituation(
        val situationType: String,
        val probability: Float,
        val confidence: Float
    )
    
    data class MemoryPointer(
        val id: String,
        val layer: MemoryLayer,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    data class IndexedMemory(
        val id: String,
        val timestamp: Long,
        val priorityScore: Float,
        val accessCount: Int,
        val impactScore: Float
    )
    
    data class CurrentContext(
        val mapId: String,
        val health: Float,
        val ammo: Int,
        val enemiesNearby: Int,
        val weaponType: String,
        val timestamp: Long
    )
    
    enum class MemoryLayer {
        USTM, STM, MTM, LTM
    }
}
