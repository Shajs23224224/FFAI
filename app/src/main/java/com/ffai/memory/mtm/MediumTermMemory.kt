package com.ffai.memory.mtm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ffai.perception.ScreenAnalyzer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Medium-Term Memory (MTM) - SQLite-backed storage for match data
 * Stores: Encounters, deaths, tactical decisions, enemy patterns
 */
class MediumTermMemory(context: Context) {
    
    private val dbHelper = MTMDatabaseHelper(context)
    private val db: SQLiteDatabase = dbHelper.writableDatabase
    private val json = Json { ignoreUnknownKeys = true }
    
    companion object {
        const val DATABASE_NAME = "ffai_mtm.db"
        const val DATABASE_VERSION = 1
        
        // Table names
        const val TABLE_ENCOUNTERS = "encounters"
        const val TABLE_DEATHS = "deaths"
        const val TABLE_DECISIONS = "decisions"
        const val TABLE_ENEMY_PATTERNS = "enemy_patterns"
        const val TABLE_LOOT_EVENTS = "loot_events"
        const val TABLE_ZONE_HISTORY = "zone_history"
    }
    
    /**
     * Record encounter with enemy
     */
    fun recordEncounter(enemy: ScreenAnalyzer.Enemy, outcome: EncounterOutcome) {
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("enemy_id", enemy.id)
            put("enemy_type", enemy.type)
            put("distance", enemy.distance)
            put("health", enemy.health)
            put("weapon", enemy.weaponType ?: "unknown")
            put("outcome", outcome.name)
            put("duration_ms", outcome.durationMs)
        }
        
        db.insert(TABLE_ENCOUNTERS, null, values)
    }
    
    /**
     * Record death
     */
    fun recordDeath(
        cause: DeathCause,
        enemyId: String?,
        location: Pair<Float, Float>,
        inventoryValue: Int
    ) {
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("cause", cause.name)
            put("enemy_id", enemyId)
            put("pos_x", location.first)
            put("pos_y", location.second)
            put("inventory_value", inventoryValue)
        }
        
        db.insert(TABLE_DEATHS, null, values)
    }
    
    /**
     * Record tactical decision
     */
    fun recordDecision(
        situation: String,
        decision: String,
        confidence: Float,
        outcome: DecisionOutcome
    ) {
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("situation", situation)
            put("decision", decision)
            put("confidence", confidence)
            put("outcome", outcome.name)
            put("success_score", outcome.successScore)
        }
        
        db.insert(TABLE_DECISIONS, null, values)
    }
    
    /**
     * Update enemy pattern
     */
    fun updateEnemyPattern(enemyId: String, pattern: EnemyPattern) {
        val values = ContentValues().apply {
            put("enemy_id", enemyId)
            put("last_seen", System.currentTimeMillis())
            put("encounter_count", pattern.encounterCount)
            put("preferred_range", pattern.preferredRange)
            put("aggression_score", pattern.aggressionScore)
            put("pattern_data", json.encodeToString(pattern))
        }
        
        db.replace(TABLE_ENEMY_PATTERNS, null, values)
    }
    
    /**
     * Get enemy pattern
     */
    fun getEnemyPattern(enemyId: String): EnemyPattern? {
        val cursor = db.query(
            TABLE_ENEMY_PATTERNS,
            arrayOf("pattern_data"),
            "enemy_id = ?",
            arrayOf(enemyId),
            null, null, null
        )
        
        return cursor.use {
            if (it.moveToFirst()) {
                val jsonStr = it.getString(0)
                json.decodeFromString(jsonStr)
            } else null
        }
    }
    
    /**
     * Get recent deaths
     */
    fun getRecentDeaths(limit: Int = 10): List<DeathRecord> {
        val cursor = db.query(
            TABLE_DEATHS,
            null,
            null, null, null, null,
            "timestamp DESC",
            limit.toString()
        )
        
        return cursor.use {
            val deaths = mutableListOf<DeathRecord>()
            while (it.moveToNext()) {
                deaths.add(DeathRecord(
                    timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                    cause = DeathCause.valueOf(it.getString(it.getColumnIndexOrThrow("cause"))),
                    enemyId = it.getString(it.getColumnIndexOrThrow("enemy_id")),
                    location = Pair(
                        it.getFloat(it.getColumnIndexOrThrow("pos_x")),
                        it.getFloat(it.getColumnIndexOrThrow("pos_y"))
                    ),
                    inventoryValue = it.getInt(it.getColumnIndexOrThrow("inventory_value"))
                ))
            }
            deaths
        }
    }
    
    /**
     * Get death hotspots (locations with multiple deaths)
     */
    fun getDeathHotspots(radius: Float = 0.1f): List<Hotspot> {
        val cursor = db.rawQuery("""
            SELECT pos_x, pos_y, COUNT(*) as death_count
            FROM $TABLE_DEATHS
            WHERE timestamp > ?
            GROUP BY CAST(pos_x / ? AS INTEGER), CAST(pos_y / ? AS INTEGER)
            HAVING death_count >= 2
            ORDER BY death_count DESC
        """, arrayOf(
            (System.currentTimeMillis() - 86400000).toString(), // Last 24h
            radius.toString(),
            radius.toString()
        ))
        
        return cursor.use {
            val hotspots = mutableListOf<Hotspot>()
            while (it.moveToNext()) {
                hotspots.add(Hotspot(
                    x = it.getFloat(0),
                    y = it.getFloat(1),
                    deathCount = it.getInt(2)
                ))
            }
            hotspots
        }
    }
    
    /**
     * Get match statistics
     */
    fun getMatchStats(): MatchStats {
        val encounterCount = getCount(TABLE_ENCOUNTERS)
        val deathCount = getCount(TABLE_DEATHS)
        val decisionCount = getCount(TABLE_DECISIONS)
        
        val avgSuccessCursor = db.rawQuery("""
            SELECT AVG(success_score) FROM $TABLE_DECISIONS
            WHERE timestamp > ?
        """, arrayOf((System.currentTimeMillis() - 3600000).toString()))
        
        val avgSuccess = avgSuccessCursor.use {
            if (it.moveToFirst()) it.getFloat(0) else 0f
        }
        
        return MatchStats(
            totalEncounters = encounterCount,
            totalDeaths = deathCount,
            totalDecisions = decisionCount,
            averageDecisionSuccess = avgSuccess,
            uniqueEnemies = getUniqueEnemyCount()
        )
    }
    
    /**
     * Generic record method for perception result
     */
    fun record(perception: ScreenAnalyzer.PerceptionResult) {
        // Record encounters with new enemies
        perception.detectedEnemies.forEach { enemy ->
            val existingPattern = getEnemyPattern(enemy.id)
            if (existingPattern == null) {
                updateEnemyPattern(enemy.id, EnemyPattern(
                    enemyId = enemy.id,
                    firstSeen = System.currentTimeMillis(),
                    encounterCount = 1,
                    preferredRange = enemy.distance,
                    aggressionScore = calculateAggression(enemy)
                ))
            } else {
                updateEnemyPattern(enemy.id, existingPattern.copy(
                    encounterCount = existingPattern.encounterCount + 1,
                    lastSeen = System.currentTimeMillis(),
                    preferredRange = (existingPattern.preferredRange + enemy.distance) / 2
                ))
            }
        }
    }
    
    /**
     * Clear all data
     */
    fun clear() {
        dbHelper.resetDatabase(db)
        Timber.d("MTM cleared")
    }
    
    /**
     * Close database
     */
    fun close() {
        db.close()
        Timber.d("MTM database closed")
    }
    
    private fun getCount(table: String): Int {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $table", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
    
    private fun getUniqueEnemyCount(): Int {
        val cursor = db.rawQuery(
            "SELECT COUNT(DISTINCT enemy_id) FROM $TABLE_ENEMY_PATTERNS",
            null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
    
    private fun calculateAggression(enemy: ScreenAnalyzer.Enemy): Float {
        // Higher score = more aggressive
        return when {
            enemy.isFiring -> 0.9f
            enemy.isMovingTowards -> 0.7f
            enemy.distance < 10 -> 0.6f
            else -> 0.3f
        }
    }
    
    // Data classes
    enum class EncounterOutcome { WON, LOST, ESCAPED, DRAW }
    enum class DeathCause { ENEMY, ZONE, FALL, EXPLOSION, UNKNOWN }
    data class DecisionOutcome(val name: String, val successScore: Float)
    
    data class EnemyPattern(
        val enemyId: String,
        val firstSeen: Long,
        val lastSeen: Long = firstSeen,
        val encounterCount: Int,
        val preferredRange: Float,
        val aggressionScore: Float,
        val movementPattern: String = "unknown",
        val preferredWeapons: List<String> = emptyList()
    )
    
    data class DeathRecord(
        val timestamp: Long,
        val cause: DeathCause,
        val enemyId: String?,
        val location: Pair<Float, Float>,
        val inventoryValue: Int
    )
    
    data class Hotspot(
        val x: Float,
        val y: Float,
        val deathCount: Int
    )
    
    data class MatchStats(
        val totalEncounters: Int,
        val totalDeaths: Int,
        val totalDecisions: Int,
        val averageDecisionSuccess: Float,
        val uniqueEnemies: Int
    )
    
    // Database helper
    private class MTMDatabaseHelper(context: Context) : 
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE $TABLE_ENCOUNTERS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER,
                    enemy_id TEXT,
                    enemy_type TEXT,
                    distance REAL,
                    health INTEGER,
                    weapon TEXT,
                    outcome TEXT,
                    duration_ms INTEGER
                )
            """)
            
            db.execSQL("""
                CREATE TABLE $TABLE_DEATHS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER,
                    cause TEXT,
                    enemy_id TEXT,
                    pos_x REAL,
                    pos_y REAL,
                    inventory_value INTEGER
                )
            """)
            
            db.execSQL("""
                CREATE TABLE $TABLE_DECISIONS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER,
                    situation TEXT,
                    decision TEXT,
                    confidence REAL,
                    outcome TEXT,
                    success_score REAL
                )
            """)
            
            db.execSQL("""
                CREATE TABLE $TABLE_ENEMY_PATTERNS (
                    enemy_id TEXT PRIMARY KEY,
                    last_seen INTEGER,
                    encounter_count INTEGER,
                    preferred_range REAL,
                    aggression_score REAL,
                    pattern_data TEXT
                )
            """)
            
            db.execSQL("""
                CREATE TABLE $TABLE_LOOT_EVENTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER,
                    item_type TEXT,
                    item_value INTEGER,
                    location_x REAL,
                    location_y REAL
                )
            """)
            
            db.execSQL("""
                CREATE TABLE $TABLE_ZONE_HISTORY (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER,
                    zone_center_x REAL,
                    zone_center_y REAL,
                    zone_radius REAL,
                    player_in_zone INTEGER
                )
            """)
            
            // Indexes
            db.execSQL("CREATE INDEX idx_encounters_time ON $TABLE_ENCOUNTERS(timestamp)")
            db.execSQL("CREATE INDEX idx_deaths_time ON $TABLE_DEATHS(timestamp)")
            db.execSQL("CREATE INDEX idx_deaths_location ON $TABLE_DEATHS(pos_x, pos_y)")
        }
        
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Handle migrations
        }
        
        fun resetDatabase(db: SQLiteDatabase) {
            db.execSQL("DELETE FROM $TABLE_ENCOUNTERS")
            db.execSQL("DELETE FROM $TABLE_DEATHS")
            db.execSQL("DELETE FROM $TABLE_DECISIONS")
            db.execSQL("DELETE FROM $TABLE_ENEMY_PATTERNS")
            db.execSQL("DELETE FROM $TABLE_LOOT_EVENTS")
            db.execSQL("DELETE FROM $TABLE_ZONE_HISTORY")
        }
    }
}
