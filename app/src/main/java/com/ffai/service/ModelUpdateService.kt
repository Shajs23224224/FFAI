package com.ffai.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Service for downloading and updating ML models.
 * Stub implementation - full functionality to be added.
 */
class ModelUpdateService : Service() {
    
    override fun onBind(intent: Intent): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
