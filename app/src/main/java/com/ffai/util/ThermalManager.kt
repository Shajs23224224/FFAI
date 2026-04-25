package com.ffai.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.ffai.config.A21SOptimizer
import com.ffai.config.DeviceProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile

/**
 * Thermal Manager - Monitors and controls device temperature
 * Critical for A21S which throttles easily
 */
class ThermalManager(
    private val context: Context,
    private val deviceProfile: DeviceProfile
) {
    private val _currentTemp = MutableStateFlow(0f)
    val currentTemp: StateFlow<Float> = _currentTemp
    
    private val _thermalState = MutableStateFlow(ThermalState.NORMAL)
    val thermalState: StateFlow<ThermalState> = _thermalState
    
    private val _cpuFrequency = MutableStateFlow(100)
    val cpuFrequency: StateFlow<Int> = _cpuFrequency
    
    enum class ThermalState {
        NORMAL,     // < 38°C
        ELEVATED,   // 38-40°C
        WARNING,    // 40-42°C
        CRITICAL    // > 42°C
    }
    
    private var consecutiveHighReadings = 0
    private val highThreshold = if (isA21S()) 38 else 40
    private val criticalThreshold = if (isA21S()) 42 else 45
    
    init {
        startThermalMonitoring()
    }
    
    private fun startThermalMonitoring() {
        Thread {
            while (true) {
                try {
                    val temp = readTemperature()
                    _currentTemp.value = temp
                    
                    updateThermalState(temp)
                    adjustPerformance(temp)
                    
                    // Check less frequently when cool
                    val checkInterval = when (_thermalState.value) {
                        ThermalState.NORMAL -> 5000L
                        ThermalState.ELEVATED -> 3000L
                        ThermalState.WARNING -> 2000L
                        ThermalState.CRITICAL -> 1000L
                    }
                    
                    Thread.sleep(checkInterval)
                } catch (e: Exception) {
                    Timber.w(e, "Error reading temperature")
                    Thread.sleep(5000)
                }
            }
        }.apply {
            name = "ThermalMonitor"
            isDaemon = true
            start()
        }
    }
    
    /**
     * Read battery temperature (most reliable on Android)
     */
    private fun readTemperature(): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.let {
            val temp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            temp / 10f  // Temperature in tenths of a degree
        } ?: readSystemTemperature()
    }
    
    /**
     * Fallback to system thermal zones
     */
    private fun readSystemTemperature(): Float {
        return try {
            // Try multiple thermal zones
            val thermalZones = listOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/class/thermal/thermal_zone2/temp",
                "/sys/class/power_supply/battery/temp"
            )
            
            for (zone in thermalZones) {
                val file = File(zone)
                if (file.exists()) {
                    val temp = RandomAccessFile(file, "r").use {
                        it.readLine().toFloat()
                    }
                    // Convert to Celsius if needed
                    return if (temp > 1000) temp / 1000f else temp
                }
            }
            
            35f  // Default assumption
        } catch (e: Exception) {
            35f
        }
    }
    
    private fun updateThermalState(temp: Float) {
        val newState = when {
            temp >= criticalThreshold -> ThermalState.CRITICAL
            temp >= highThreshold -> ThermalState.WARNING
            temp >= highThreshold - 2 -> ThermalState.ELEVATED
            else -> ThermalState.NORMAL
        }
        
        if (newState != _thermalState.value) {
            Timber.i("Thermal state changed: ${_thermalState.value} -> $newState (${temp}°C)")
            _thermalState.value = newState
        }
        
        // Track consecutive high readings
        if (temp >= highThreshold) {
            consecutiveHighReadings++
        } else {
            consecutiveHighReadings = 0
        }
    }
    
    private fun adjustPerformance(temp: Float) {
        when (_thermalState.value) {
            ThermalState.CRITICAL -> {
                _cpuFrequency.value = 30  // Reduce to 30%
            }
            ThermalState.WARNING -> {
                _cpuFrequency.value = 50
            }
            ThermalState.ELEVATED -> {
                _cpuFrequency.value = 75
            }
            ThermalState.NORMAL -> {
                _cpuFrequency.value = 100
            }
        }
    }
    
    /**
     * Check if we should throttle processing
     */
    fun shouldThrottle(): Boolean {
        return _thermalState.value >= ThermalState.WARNING || 
               consecutiveHighReadings >= 3
    }
    
    /**
     * Get recommended throttle delay
     */
    fun getThrottleDelay(): Long {
        return when (_thermalState.value) {
            ThermalState.CRITICAL -> 500L
            ThermalState.WARNING -> 200L
            ThermalState.ELEVATED -> 100L
            else -> 0L
        }
    }
    
    /**
     * Get maximum allowed concurrent operations
     */
    fun getMaxConcurrentOps(): Int {
        return when (_thermalState.value) {
            ThermalState.CRITICAL -> 1
            ThermalState.WARNING -> 2
            else -> 4
        }
    }
    
    /**
     * Check if device is charging (can handle higher temps)
     */
    fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
               status == BatteryManager.BATTERY_STATUS_FULL
    }
    
    /**
     * Check if in power save mode
     */
    fun isPowerSaveMode(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            powerManager.isPowerSaveMode
        } else {
            false
        }
    }
    
    /**
     * Check if thermal throttling is active
     */
    fun isThermalThrottling(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        } else {
            _thermalState.value >= ThermalState.WARNING
        }
    }
    
    private fun isA21S(): Boolean {
        return android.os.Build.MODEL.contains("A21", ignoreCase = true) ||
               android.os.Build.DEVICE.contains("a21", ignoreCase = true)
    }
    
    /**
     * Get thermal statistics
     */
    fun getThermalStats(): ThermalStats {
        return ThermalStats(
            currentTemp = _currentTemp.value,
            state = _thermalState.value,
            cpuThrottlePercent = 100 - _cpuFrequency.value,
            isThrottling = shouldThrottle(),
            isCharging = isCharging()
        )
    }
    
    data class ThermalStats(
        val currentTemp: Float,
        val state: ThermalState,
        val cpuThrottlePercent: Int,
        val isThrottling: Boolean,
        val isCharging: Boolean
    )
}
