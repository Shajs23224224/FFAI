package com.ffai.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Service for running ML models (TensorFlow Lite, ONNX).
 * Stub implementation - full functionality to be added.
 */
class MLInferenceService : Service() {
    
    override fun onBind(intent: Intent): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
