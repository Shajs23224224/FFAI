package com.ffai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ffai.FFAIApplication
import com.ffai.config.FFAIConfig
import timber.log.Timber
import kotlinx.coroutines.*

/**
 * FFAI Accessibility Service - Core service for game interaction
 * Handles screen detection, gesture injection, and coordinates all AI modules
 */
class FFAIAccessibilityService : AccessibilityService() {
    
    companion object {
        const val ACTION_START = "com.ffai.ACTION_START"
        const val ACTION_STOP = "com.ffai.ACTION_STOP"
        const val ACTION_SCREEN_CAPTURE_PERMISSION = "com.ffai.ACTION_SCREEN_CAPTURE"
        
        private const val TAG = "FFAIAccessibility"
        private const val GESTURE_DURATION_MS = 100L
        private const val SWIPE_DURATION_MS = 300L
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var isActive = false
    private var screenCaptureService: ScreenCaptureService? = null
    private var overlayView: android.view.View? = null
    private var windowManager: WindowManager? = null
    
    // Performance tracking
    private var gestureCount = 0
    private var lastGestureTime = 0L
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.i("FFAI Accessibility Service connected")
        
        // Configure service info
        serviceInfo = serviceInfo.apply {
            // Enable all event types for comprehensive detection
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_VIEW_SCROLLED
            
            // Request necessary capabilities
            flags = android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                   android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
                   android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            
            // Feedback type
            feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            
            // Notification timeout
            notificationTimeout = 100
        }
        
        // Register with application
        FFAIApplication.instance.accessibilityService = this
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isActive) return
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowChange(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleContentChange(event)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Track user clicks for learning
                handleUserClick(event)
            }
        }
    }
    
    override fun onInterrupt() {
        Timber.w("Service interrupted")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startService()
            ACTION_STOP -> stopService()
            ACTION_SCREEN_CAPTURE_PERMISSION -> {
                // Handle screen capture permission result
                handleScreenCaptureResult(intent)
            }
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopService()
        FFAIApplication.instance.accessibilityService = null
        scope.cancel()
        Timber.i("FFAI Accessibility Service destroyed")
    }
    
    private fun startService() {
        if (isActive) return
        
        isActive = true
        Timber.i("Starting FFAI service...")
        
        // Start screen capture
        startScreenCapture()
        
        // Create overlay for visual feedback
        createOverlay()
        
        // Start orchestrator
        FFAIApplication.instance.orchestrator.start()
        
        // Start foreground service notification
        startForeground()
        
        Timber.i("FFAI service started successfully")
    }
    
    private fun stopService() {
        if (!isActive) return
        
        isActive = false
        Timber.i("Stopping FFAI service...")
        
        // Stop orchestrator
        FFAIApplication.instance.orchestrator.stop()
        
        // Stop screen capture
        screenCaptureService?.stop()
        screenCaptureService = null
        
        // Remove overlay
        removeOverlay()
        
        // Stop foreground
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        Timber.i("FFAI service stopped")
    }
    
    private fun startScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) 
            as MediaProjectionManager
        
        // Request permission (will be handled by activity)
        val intent = projectionManager.createScreenCaptureIntent()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        
        // Store reference for when permission is granted
        // Actual implementation would use a callback
    }
    
    private fun handleScreenCaptureResult(intent: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) 
            as MediaProjectionManager
        
        val resultCode = intent.getIntExtra("result_code", -1)
        val data = intent.getParcelableExtra<Intent>("data")
        
        if (resultCode == RESULT_OK && data != null) {
            val projection = projectionManager.getMediaProjection(resultCode, data)
            screenCaptureService = ScreenCaptureService(this, projection)
            screenCaptureService?.start()
        }
    }
    
    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 10
            y = 100
        }
        
        // Create simple status view
        val textView = android.widget.TextView(this).apply {
            text = "FFAI: Active"
            setTextColor(android.graphics.Color.GREEN)
            setBackgroundColor(android.graphics.Color.argb(128, 0, 0, 0))
            setPadding(10, 10, 10, 10)
        }
        
        overlayView = textView
        windowManager?.addView(overlayView, params)
    }
    
    private fun removeOverlay() {
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
    }
    
    /**
     * Execute tap gesture at screen coordinates
     */
    fun executeTap(x: Float, y: Float, durationMs: Long = GESTURE_DURATION_MS) {
        if (!isActive) return
        
        val path = Path().apply {
            moveTo(x, y)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        
        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureCount++
                Timber.v("Tap completed at ($x, $y)")
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Timber.w("Tap cancelled at ($x, $y)")
            }
        }, mainHandler)
        
        if (!result) {
            Timber.e("Failed to dispatch tap gesture")
        }
    }
    
    /**
     * Execute swipe gesture
     */
    fun executeSwipe(
        startX: Float, 
        startY: Float, 
        endX: Float, 
        endY: Float,
        durationMs: Long = SWIPE_DURATION_MS
    ) {
        if (!isActive) return
        
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        
        dispatchGesture(gesture, null, mainHandler)
    }
    
    /**
     * Execute continuous gesture (for camera control)
     */
    fun executeContinuousGesture(
        points: List<Pair<Float, Float>>,
        durationMs: Long
    ) {
        if (!isActive || points.size < 2) return
        
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) {
                lineTo(points[i].first, points[i].second)
            }
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        
        dispatchGesture(gesture, null, mainHandler)
    }
    
    /**
     * Execute multi-point gesture (multitouch)
     */
    fun executeMultiTouch(
        touchPoints: List<TouchPoint>,
        durationMs: Long
    ) {
        if (!isActive) return
        
        val builder = GestureDescription.Builder()
        
        touchPoints.forEach { point ->
            val path = Path().apply {
                moveTo(point.x, point.y)
            }
            builder.addStroke(GestureDescription.StrokeDescription(
                path, 
                point.startDelayMs, 
                point.durationMs.coerceAtMost(durationMs)
            ))
        }
        
        dispatchGesture(builder.build(), null, mainHandler)
    }
    
    /**
     * Press back button
     */
    fun pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }
    
    /**
     * Press home button
     */
    fun pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }
    
    /**
     * Press recent apps
     */
    fun pressRecentApps() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }
    
    /**
     * Get root node of current window
     */
    fun getCurrentWindowRoot(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }
    
    /**
     * Find node by text
     */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        
        return findNodeRecursive(root) { node ->
            node.text?.toString()?.contains(text, ignoreCase = true) == true
        }
    }
    
    /**
     * Find click button by text and click it
     */
    fun clickButtonByText(text: String): Boolean {
        val node = findNodeByText(text) ?: return false
        
        return if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            // Find clickable parent
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                parent = parent.parent
            }
            false
        }
    }
    
    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findNodeRecursive(child, predicate)
            if (result != null) return result
        }
        
        return null
    }
    
    private fun handleWindowChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: return
        
        Timber.d("Window changed: $packageName / $className")
        
        // Detect if we're in game
        if (isGamePackage(packageName)) {
            FFAIApplication.instance.orchestrator.let {
                // Resume full operation
            }
        }
    }
    
    private fun handleContentChange(event: AccessibilityEvent) {
        // Analyze content changes for game events
        // This is lightweight - heavy analysis happens in ScreenAnalyzer
    }
    
    private fun handleUserClick(event: AccessibilityEvent) {
        // Record user action for RL learning
        // Store in memory for behavior cloning
    }
    
    private fun isGamePackage(packageName: String): Boolean {
        return packageName.contains("freefire") || 
               packageName.contains("garena") ||
               packageName.contains("ff") ||
               packageName.contains("battleground") ||
               packageName.contains("pubg")
    }
    
    private fun startForeground() {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "ffai_service"
            val channel = android.app.NotificationChannel(
                channelId,
                "FFAI Core Service",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) 
                as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
            
            android.app.Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }.apply {
            setContentTitle("FFAI Running")
            setContentText("AI assistance active")
            setSmallIcon(android.R.drawable.ic_menu_info_details)
            setOngoing(true)
        }.build()
        
        startForeground(1, notification)
    }
    
    // Data classes
    data class TouchPoint(
        val x: Float,
        val y: Float,
        val startDelayMs: Long = 0,
        val durationMs: Long = 100
    )
    
    // Statistics
    fun getGestureStats(): GestureStats {
        return GestureStats(
            totalGestures = gestureCount,
            averageInterval = if (gestureCount > 1) {
                (System.currentTimeMillis() - lastGestureTime) / gestureCount
            } else 0
        )
    }
    
    data class GestureStats(
        val totalGestures: Int,
        val averageInterval: Long
    )
}
