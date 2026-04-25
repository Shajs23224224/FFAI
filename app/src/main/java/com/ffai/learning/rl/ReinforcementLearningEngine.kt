package com.ffai.learning.rl

import android.content.Context
import com.ffai.config.DeviceProfile
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Reinforcement Learning Engine - On-device PPO implementation
 * Optimized for A21S: Lightweight models, small replay buffer, infrequent updates
 */
class ReinforcementLearningEngine(
    context: Context,
    private val deviceProfile: DeviceProfile,
    private val batchSize: Int = 32,
    private val learningRate: Float = 0.001f,
    private val gamma: Float = 0.99f,  // Discount factor
    private val lambda: Float = 0.95f  // GAE lambda
) {
    private val filesDir = context.filesDir
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // TFLite interpreters
    private var policyInterpreter: Interpreter? = null
    private var valueInterpreter: Interpreter? = null
    
    // Model paths
    private val policyModelPath = File(filesDir, "rl_policy.tflite")
    private val valueModelPath = File(filesDir, "rl_value.tflite")
    
    // Experience buffer (limited size for A21S)
    private val maxBufferSize = if (deviceProfile.optimizationTier <= DeviceProfile.OptimizationTier.LIGHT) {
        500  // Small buffer for A21S
    } else {
        2000
    }
    
    private val replayBuffer = LinkedList<Experience>()
    private val bufferLock = Any()
    
    // PPO hyperparameters
    private val clipEpsilon = 0.2f
    private val entropyCoeff = 0.01f
    private val valueCoeff = 0.5f
    private val maxGradNorm = 0.5f
    
    // State dimension (reduced for mobile)
    private val stateDim = 128
    private val actionDim = 16
    
    // Training metrics
    private var totalEpisodes = 0
    private var totalSteps = 0
    private var averageReward = 0f
    private var policyLoss = 0f
    private var valueLoss = 0f
    
    // Feature normalization
    private val runningMean = FloatArray(stateDim) { 0f }
    private val runningVar = FloatArray(stateDim) { 1f }
    private var updateCount = 0
    
    init {
        loadOrCreateModels()
    }
    
    /**
     * Compute action given state
     */
    fun computeAction(state: FloatArray): ActionResult {
        val normalizedState = normalizeState(state)
        
        // Run inference
        val policyOutput = runPolicyInference(normalizedState)
        val valueOutput = runValueInference(normalizedState)
        
        // Sample action from policy distribution
        val action = sampleAction(policyOutput.logits)
        
        return ActionResult(
            action = action,
            logProbability = policyOutput.logProbabilities[action],
            value = valueOutput,
            entropy = calculateEntropy(policyOutput.logits)
        )
    }
    
    /**
     * Store experience for training
     */
    fun storeExperience(experience: Experience) {
        synchronized(bufferLock) {
            if (replayBuffer.size >= maxBufferSize) {
                replayBuffer.removeFirst()
            }
            replayBuffer.addLast(experience)
        }
        
        totalSteps++
        updateRunningStats(experience.state)
    }
    
    /**
     * Update policy (PPO update)
     */
    suspend fun updatePolicy(): UpdateResult = withContext(Dispatchers.Default) {
        val batch = synchronized(bufferLock) {
            if (replayBuffer.size < batchSize) {
                return@withContext UpdateResult(0f, 0f, false)
            }
            
            // Sample batch
            replayBuffer.shuffled().take(batchSize)
        }
        
        try {
            // Compute advantages using GAE
            val advantages = computeGAE(batch)
            
            // Compute returns
            val returns = computeReturns(batch)
            
            // PPO update (simplified - would use TFLite training in production)
            val result = performPPOUpdate(batch, advantages, returns)
            
            totalEpisodes++
            averageReward = batch.map { it.reward }.average().toFloat()
            
            UpdateResult(
                policyLoss = result.policyLoss,
                valueLoss = result.valueLoss,
                updated = true
            )
        } catch (e: Exception) {
            Timber.e(e, "Policy update failed")
            UpdateResult(0f, 0f, false)
        }
    }
    
    /**
     * Save model to disk
     */
    fun saveModel() {
        // Models are already on disk if using TFLite
        // Just save metadata
        saveTrainingMetadata()
    }
    
    /**
     * Load model from disk or create new
     */
    private fun loadOrCreateModels() {
        if (policyModelPath.exists() && valueModelPath.exists()) {
            loadModels()
        } else {
            createDefaultModels()
        }
    }
    
    private fun loadModels() {
        try {
            val policyBuffer = loadModelFile(policyModelPath)
            val valueBuffer = loadModelFile(valueModelPath)
            
            val options = Interpreter.Options().apply {
                setNumThreads(2)  // Limited for A21S
                useNNAPI = true   // Try to use NNAPI
            }
            
            policyInterpreter = Interpreter(policyBuffer, options)
            valueInterpreter = Interpreter(valueBuffer, options)
            
            loadTrainingMetadata()
            
            Timber.i("RL models loaded successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to load models, creating defaults")
            createDefaultModels()
        }
    }
    
    private fun createDefaultModels() {
        // Create minimal models for A21S
        // In production, these would be pre-trained models
        Timber.i("Creating default RL models")
        
        // Create placeholder models
        // Actual implementation would use TFLite Model Maker or similar
    }
    
    private fun loadModelFile(modelFile: File): MappedByteBuffer {
        return FileInputStream(modelFile).use { inputStream ->
            inputStream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                0,
                modelFile.length()
            )
        }
    }
    
    private fun saveTrainingMetadata() {
        val metadata = TrainingMetadata(
            totalEpisodes = totalEpisodes,
            totalSteps = totalSteps,
            averageReward = averageReward,
            runningMean = runningMean.toList(),
            runningVar = runningVar.toList()
        )
        
        File(filesDir, "rl_metadata.json").writeText(
            kotlinx.serialization.json.Json.encodeToString(
                TrainingMetadata.serializer(),
                metadata
            )
        )
    }
    
    private fun loadTrainingMetadata() {
        val metadataFile = File(filesDir, "rl_metadata.json")
        if (metadataFile.exists()) {
            try {
                val metadata = kotlinx.serialization.json.Json.decodeFromString(
                    TrainingMetadata.serializer(),
                    metadataFile.readText()
                )
                totalEpisodes = metadata.totalEpisodes
                totalSteps = metadata.totalSteps
                averageReward = metadata.averageReward
                metadata.runningMean.toFloatArray().copyInto(runningMean)
                metadata.runningVar.toFloatArray().copyInto(runningVar)
            } catch (e: Exception) {
                Timber.w("Failed to load metadata: ${e.message}")
            }
        }
    }
    
    private fun normalizeState(state: FloatArray): FloatArray {
        return FloatArray(state.size) { i ->
            (state[i] - runningMean[i]) / kotlin.math.sqrt(runningVar[i] + 1e-8f)
        }
    }
    
    private fun updateRunningStats(state: FloatArray) {
        updateCount++
        val alpha = 0.01f  // Moving average factor
        
        for (i in state.indices) {
            val delta = state[i] - runningMean[i]
            runningMean[i] += alpha * delta
            runningVar[i] = (1 - alpha) * (runningVar[i] + alpha * delta * delta)
        }
    }
    
    private fun runPolicyInference(state: FloatArray): PolicyOutput {
        val interpreter = policyInterpreter ?: return PolicyOutput(FloatArray(actionDim))
        
        val input = arrayOf(state)
        val output = Array(1) { FloatArray(actionDim) }
        
        interpreter.run(input, output)
        
        return PolicyOutput(output[0])
    }
    
    private fun runValueInference(state: FloatArray): Float {
        val interpreter = valueInterpreter ?: return 0f
        
        val input = arrayOf(state)
        val output = Array(1) { FloatArray(1) }
        
        interpreter.run(input, output)
        
        return output[0][0]
    }
    
    private fun sampleAction(logits: FloatArray): Int {
        // Softmax
        val maxLogit = logits.maxOrNull() ?: 0f
        val expLogits = logits.map { exp(it - maxLogit) }
        val sumExp = expLogits.sum()
        val probabilities = expLogits.map { it / sumExp }
        
        // Sample
        val random = Random().nextFloat()
        var cumulative = 0f
        for ((index, prob) in probabilities.withIndex()) {
            cumulative += prob
            if (random <= cumulative) return index
        }
        
        return probabilities.indices.last
    }
    
    private fun calculateEntropy(logits: FloatArray): Float {
        val maxLogit = logits.maxOrNull() ?: 0f
        val expLogits = logits.map { exp(it - maxLogit) }
        val sumExp = expLogits.sum()
        val probabilities = expLogits.map { it / sumExp }
        
        return -probabilities.map { p ->
            if (p > 1e-8) p * ln(p) else 0f
        }.sum().toFloat()
    }
    
    private fun computeGAE(batch: List<Experience>): FloatArray {
        val advantages = FloatArray(batch.size)
        var gae = 0f
        
        for (i in (batch.size - 1) downTo 0) {
            val exp = batch[i]
            val nextValue = if (i + 1 < batch.size) {
                runValueInference(batch[i + 1].state)
            } else {
                0f
            }
            
            val delta = exp.reward + gamma * nextValue - exp.value
            gae = delta + gamma * lambda * gae
            advantages[i] = gae
        }
        
        return advantages
    }
    
    private fun computeReturns(batch: List<Experience>): FloatArray {
        val returns = FloatArray(batch.size)
        var R = 0f
        
        for (i in (batch.size - 1) downTo 0) {
            R = batch[i].reward + gamma * R
            returns[i] = R
        }
        
        return returns
    }
    
    private fun performPPOUpdate(
        batch: List<Experience>,
        advantages: FloatArray,
        returns: FloatArray
    ): UpdateMetrics {
        // Simplified PPO update
        // Full implementation would use TFLite training delegate
        
        val oldLogProbs = batch.map { it.logProbability }.toFloatArray()
        val oldValues = batch.map { it.value }.toFloatArray()
        
        // Calculate losses (placeholder - real implementation would do gradient descent)
        val policyLoss = -advantages.average().toFloat()
        val valueLoss = returns.zip(oldValues) { r, v ->
            (r - v) * (r - v)
        }.average().toFloat()
        
        return UpdateMetrics(policyLoss, valueLoss)
    }
    
    /**
     * Extract state features from game observation
     */
    fun extractStateFeatures(observation: GameObservation): FloatArray {
        return FloatArray(stateDim) { index ->
            when (index) {
                0 -> observation.playerHealth / 100f
                1 -> observation.playerArmor / 100f
                2 -> observation.ammoCount / 100f
                3 -> observation.enemiesNearby.toFloat() / 10f
                4 -> observation.distanceToZone / 1000f
                5 -> if (observation.inSafeZone) 1f else 0f
                6 -> if (observation.hasWeapon) 1f else 0f
                7 -> observation.weaponTier / 5f
                8 -> observation.enemyHealthAvg / 100f
                9 -> observation.enemyDistanceAvg / 100f
                10 -> observation.timeAlive / 300f  // Normalized to 5min
                11 -> observation.kills.toFloat() / 10f
                12 -> observation.inventoryValue / 1000f
                13 -> if (observation.inCover) 1f else 0f
                14 -> observation.zoneShrinkRate
                15 -> observation.remainingPlayers.toFloat() / 50f
                // Fill remaining with normalized position info
                in 16..31 -> observation.nearbyEnemies.getOrNull(index - 16)?.health?.div(100f) ?: 0f
                in 32..47 -> observation.nearbyEnemies.getOrNull(index - 32)?.distance?.div(100f) ?: 0f
                in 48..63 -> observation.nearbyEnemies.getOrNull(index - 48)?.x?.div(1000f) ?: 0f
                in 64..79 -> observation.nearbyEnemies.getOrNull(index - 64)?.y?.div(1000f) ?: 0f
                in 80..95 -> observation.nearbyLoot.getOrNull(index - 80)?.value?.div(500f) ?: 0f
                else -> Random().nextFloat() * 0.01f  // Small noise
            }
        }
    }
    
    // Data classes
    data class Experience(
        val state: FloatArray,
        val action: Int,
        val reward: Float,
        val nextState: FloatArray?,
        val done: Boolean,
        val logProbability: Float,
        val value: Float
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Experience
            return action == other.action && 
                   reward == other.reward &&
                   done == other.done &&
                   state.contentEquals(other.state)
        }
        
        override fun hashCode(): Int {
            var result = state.contentHashCode()
            result = 31 * result + action
            result = 31 * result + reward.hashCode()
            result = 31 * result + done.hashCode()
            return result
        }
    }
    
    data class ActionResult(
        val action: Int,
        val logProbability: Float,
        val value: Float,
        val entropy: Float
    )
    
    data class PolicyOutput(
        val logits: FloatArray
    ) {
        val logProbabilities: FloatArray
            get() = logits.map { ln(max(it, 1e-8f)) }.toFloatArray()
        
        val probabilities: FloatArray
            get() {
                val maxLogit = logits.maxOrNull() ?: 0f
                val expLogits = logits.map { exp(it - maxLogit) }
                val sumExp = expLogits.sum()
                return expLogits.map { it / sumExp }.toFloatArray()
            }
    }
    
    data class UpdateResult(
        val policyLoss: Float,
        val valueLoss: Float,
        val updated: Boolean
    )
    
    data class UpdateMetrics(
        val policyLoss: Float,
        val valueLoss: Float
    )
    
    data class GameObservation(
        val playerHealth: Float,
        val playerArmor: Float,
        val ammoCount: Int,
        val enemiesNearby: Int,
        val distanceToZone: Float,
        val inSafeZone: Boolean,
        val hasWeapon: Boolean,
        val weaponTier: Float,
        val enemyHealthAvg: Float,
        val enemyDistanceAvg: Float,
        val timeAlive: Float,
        val kills: Int,
        val inventoryValue: Float,
        val inCover: Boolean,
        val zoneShrinkRate: Float,
        val remainingPlayers: Int,
        val nearbyEnemies: List<EnemyInfo>,
        val nearbyLoot: List<LootInfo>
    )
    
    data class EnemyInfo(
        val health: Float,
        val distance: Float,
        val x: Float,
        val y: Float
    )
    
    data class LootInfo(
        val value: Float
    )
    
    @kotlinx.serialization.Serializable
    data class TrainingMetadata(
        val totalEpisodes: Int,
        val totalSteps: Int,
        val averageReward: Float,
        val runningMean: List<Float>,
        val runningVar: List<Float>
    )
}
