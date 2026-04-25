package com.ffai.memory.ltm

import android.content.Context
import kotlinx.serialization.Serializable
nimport kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Long-Term Memory (LTM) - Persistent storage for learned patterns
 * Stores: Player profiles, map knowledge, RL models, learned behaviors
 */
class LongTermMemory(context: Context) {
    private val filesDir = context.filesDir
    private val ltmDir = File(filesDir, "ltm").apply { mkdirs() }
    
    private val json = Json {
        prettyPrint = false  // Save space on A21S
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    // In-memory cache for frequently accessed data
    private val playerProfileCache = ConcurrentHashMap<String, PlayerProfile>()
    private val mapKnowledgeCache = ConcurrentHashMap<String, MapKnowledge>()
    
    private val maxCacheSize = 50  // Limited for A21S memory constraints
    
    init {
        loadIndex()
        Timber.d("LTM initialized at: ${ltmDir.absolutePath}")
    }
    
    /**
     * Save player profile to persistent storage
     */
    fun savePlayerProfile(profile: PlayerProfile) {
        playerProfileCache[profile.playerId] = profile
        
        val file = File(ltmDir, "player_${profile.playerId}.json")
        file.writeText(json.encodeToString(PlayerProfile.serializer(), profile))
        
        // Maintain cache size
        if (playerProfileCache.size > maxCacheSize) {
            val oldest = playerProfileCache.entries.minByOrNull { it.value.lastEncounter }?.key
            oldest?.let { playerProfileCache.remove(it) }
        }
        
        updateIndex()
    }
    
    /**
     * Load player profile
     */
    fun loadPlayerProfile(playerId: String): PlayerProfile? {
        // Check cache first
        playerProfileCache[playerId]?.let { return it }
        
        // Load from disk
        val file = File(ltmDir, "player_${playerId}.json")
        if (!file.exists()) return null
        
        return try {
            val profile = json.decodeFromString(PlayerProfile.serializer(), file.readText())
            playerProfileCache[playerId] = profile
            profile
        } catch (e: Exception) {
            Timber.e(e, "Failed to load player profile: $playerId")
            null
        }
    }
    
    /**
     * Save map knowledge
     */
    fun saveMapKnowledge(knowledge: MapKnowledge) {
        mapKnowledgeCache[knowledge.mapId] = knowledge
        
        val file = File(ltmDir, "map_${knowledge.mapId}.bin")
        // Use binary format for map data (more compact)
        file.writeBytes(serializeMapKnowledge(knowledge))
        
        updateIndex()
    }
    
    /**
     * Load map knowledge
     */
    fun loadMapKnowledge(mapId: String): MapKnowledge? {
        mapKnowledgeCache[mapId]?.let { return it }
        
        val file = File(ltmDir, "map_${mapId}.bin")
        if (!file.exists()) return null
        
        return try {
            val knowledge = deserializeMapKnowledge(file.readBytes())
            mapKnowledgeCache[mapId] = knowledge
            knowledge
        } catch (e: Exception) {
            Timber.e(e, "Failed to load map knowledge: $mapId")
            null
        }
    }
    
    /**
     * Get all known player profiles
     */
    fun getAllPlayerProfiles(): List<PlayerProfile> {
        return ltmDir.listFiles { file -> file.name.startsWith("player_") }
            ?.mapNotNull { file ->
                val playerId = file.name.removePrefix("player_").removeSuffix(".json")
                loadPlayerProfile(playerId)
            } ?: emptyList()
    }
    
    /**
     * Get all map knowledge
     */
    fun getAllMapKnowledge(): List<MapKnowledge> {
        return ltmDir.listFiles { file -> file.name.startsWith("map_") }
            ?.mapNotNull { file ->
                val mapId = file.name.removePrefix("map_").removeSuffix(".bin")
                loadMapKnowledge(mapId)
            } ?: emptyList()
    }
    
    /**
     * Find similar players based on behavior
     */
    fun findSimilarPlayers(reference: PlayerProfile, limit: Int = 5): List<PlayerProfile> {
        return getAllPlayerProfiles()
            .filter { it.playerId != reference.playerId }
            .map { profile ->
                val similarity = calculateSimilarity(reference, profile)
                profile to similarity
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
    
    /**
     * Get learning statistics
     */
    fun getLearningStats(): LearningStats {
        return LearningStats(
            totalProfiles = playerProfileCache.size,
            totalMaps = mapKnowledgeCache.size,
            storageUsedMB = calculateStorageUsed(),
            lastUpdate = System.currentTimeMillis()
        )
    }
    
    /**
     * Clear all data
     */
    fun clear() {
        playerProfileCache.clear()
        mapKnowledgeCache.clear()
        ltmDir.listFiles()?.forEach { it.delete() }
        File(ltmDir, "index.json").delete()
        Timber.i("LTM cleared")
    }
    
    /**
     * Close and cleanup
     */
    fun close() {
        playerProfileCache.clear()
        mapKnowledgeCache.clear()
        Timber.d("LTM closed")
    }
    
    private fun calculateSimilarity(p1: PlayerProfile, p2: PlayerProfile): Float {
        // Cosine similarity on behavior vectors
        val v1 = floatArrayOf(
            p1.averageAggression,
            p1.averageAccuracy,
            p1.preferredRange,
            p1.movementSpeed,
            p1.reactionTime / 1000f  // Normalize to 0-1
        )
        val v2 = floatArrayOf(
            p2.averageAggression,
            p2.averageAccuracy,
            p2.preferredRange,
            p2.movementSpeed,
            p2.reactionTime / 1000f
        )
        
        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f
        
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            norm1 += v1[i] * v1[i]
            norm2 += v2[i] * v2[i]
        }
        
        return if (norm1 > 0 && norm2 > 0) {
            dotProduct / (kotlin.math.sqrt(norm1) * kotlin.math.sqrt(norm2))
        } else 0f
    }
    
    private fun serializeMapKnowledge(knowledge: MapKnowledge): ByteArray {
        // Simple binary serialization for map data
        // In production, use protobuf for efficiency
        return json.encodeToString(MapKnowledge.serializer(), knowledge).toByteArray()
    }
    
    private fun deserializeMapKnowledge(data: ByteArray): MapKnowledge {
        return json.decodeFromString(MapKnowledge.serializer(), data.toString(Charsets.UTF_8))
    }
    
    private fun calculateStorageUsed(): Float {
        var totalSize = 0L
        ltmDir.listFiles()?.forEach { totalSize += it.length() }
        return totalSize / (1024f * 1024f)  // Convert to MB
    }
    
    private fun loadIndex() {
        val indexFile = File(ltmDir, "index.json")
        if (indexFile.exists()) {
            try {
                val index = json.decodeFromString(LTMIndex.serializer(), indexFile.readText())
                Timber.d("Loaded index: ${index.profileCount} profiles, ${index.mapCount} maps")
            } catch (e: Exception) {
                Timber.w("Failed to load index: ${e.message}")
            }
        }
    }
    
    private fun updateIndex() {
        val index = LTMIndex(
            profileCount = playerProfileCache.size,
            mapCount = mapKnowledgeCache.size,
            lastUpdated = System.currentTimeMillis()
        )
        File(ltmDir, "index.json").writeText(json.encodeToString(LTMIndex.serializer(), index))
    }
    
    // Data classes
    @Serializable
    data class PlayerProfile(
        val playerId: String,
        val firstSeen: Long,
        var lastEncounter: Long,
        var encounterCount: Int = 0,
        var killsBy: Int = 0,
        var killsOn: Int = 0,
        
        // Behavior metrics
        var averageAggression: Float = 0.5f,
        var averageAccuracy: Float = 0.5f,
        var preferredRange: Float = 0.5f,  // 0=close, 1=long
        var movementSpeed: Float = 0.5f,
        var reactionTime: Float = 300f,  // ms
        
        // Playstyle classification
        var isRusher: Boolean = false,
        var isCamper: Boolean = false,
        var isSniper: Boolean = false,
        
        // Weapon preferences
        var preferredWeapons: List<String> = emptyList(),
        
        // Hotspots (where they like to fight)
        var hotspots: List<Hotspot> = emptyList(),
        
        // Temporal patterns
        var activeTimes: List<Int> = emptyList(),  // Hour of day (0-23)
        
        // Skill rating (calculated)
        var skillRating: Float = 0.5f,
        var threatLevel: Float = 0.5f
    )
    
    @Serializable
    data class MapKnowledge(
        val mapId: String,
        val lastPlayed: Long,
        var gamesPlayed: Int = 0,
        
        // High-level features
        var hotDropZones: List<Zone> = emptyList(),
        var safeRotations: List<Path> = emptyList(),
        var goodLootSpots: List<Zone> = emptyList(),
        
        // Tactical data
        var deathLocations: List<DeathPoint> = emptyList(),
        var killLocations: List<KillPoint> = emptyList(),
        var coverPositions: List<Cover> = emptyList(),
        
        // Zone-specific
        var zoneShrinkPatterns: List<ShrinkPattern> = emptyList(),
        
        // Vehicle spawns (approximate)
        var vehicleSpawns: List<Zone> = emptyList(),
        
        // Statistics
        var averageSurvivalTime: Float = 0f,
        var averagePlacement: Float = 25f,
        var winCount: Int = 0
    )
    
    @Serializable
    data class Zone(
        val x: Float,
        val y: Float,
        val radius: Float,
        val dangerLevel: Float = 0.5f,
        val valueScore: Float = 0.5f
    )
    
    @Serializable
    data class Path(
        val points: List<Zone>,
        val safety: Float,
        val distance: Float
    )
    
    @Serializable
    data class DeathPoint(
        val x: Float,
        val y: Float,
        val timestamp: Long,
        val cause: String
    )
    
    @Serializable
    data class KillPoint(
        val x: Float,
        val y: Float,
        val timestamp: Long,
        val weapon: String
    )
    
    @Serializable
    data class Cover(
        val x: Float,
        val y: Float,
        val type: String,  // rock, tree, building, etc.
        val durability: Float  // How much protection it offers
    )
    
    @Serializable
    data class ShrinkPattern(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val phase: Int
    )
    
    @Serializable
    data class Hotspot(
        val x: Float,
        val y: Float,
        val frequency: Int
    )
    
    @Serializable
    data class LTMIndex(
        val profileCount: Int,
        val mapCount: Int,
        val lastUpdated: Long
    )
    
    data class LearningStats(
        val totalProfiles: Int,
        val totalMaps: Int,
        val storageUsedMB: Float,
        val lastUpdate: Long
    )
}
