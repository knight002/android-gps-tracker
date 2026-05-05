package com.example.gpstracker

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.gpstracker.data.AppDatabase
import com.example.gpstracker.data.LocationPoint
import com.example.gpstracker.data.Session
import com.example.gpstracker.utils.DistanceCalculator
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TrackingService : LifecycleService() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var currentSessionId: Long = -1
    private var pointCount = 0
    private var updateJob: Job? = null
    private lateinit var prefs: SharedPreferences

    companion object {
        const val CHANNEL_ID = "gps_tracking_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_BROADCAST_STATE_CHANGED = "com.example.gpstracker.TRACKING_STATE_CHANGED"
        const val EXTRA_IS_TRACKING = "is_tracking"
        const val EXTRA_IS_PAUSED = "is_paused"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val PREFS_NAME = "gps_tracker_prefs"
        const val KEY_IS_TRACKING = "is_tracking"
        const val KEY_IS_PAUSED = "is_paused"
        const val KEY_MOVEMENT_THRESHOLD = "movement_threshold"
        const val KEY_DWELL_TIMEOUT = "dwell_timeout_minutes"

        const val DEFAULT_DWELL_TIMEOUT_MINUTES = 1.0
        const val DEFAULT_MOVEMENT_THRESHOLD_M = 20.0

        fun getMovementThreshold(context: Context): Double {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getFloat(KEY_MOVEMENT_THRESHOLD, DEFAULT_MOVEMENT_THRESHOLD_M.toFloat()).toDouble()
        }

        fun getDwellTimeoutMs(context: Context): Long {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val minutes = prefs.getFloat(KEY_DWELL_TIMEOUT, DEFAULT_DWELL_TIMEOUT_MINUTES.toFloat()).toDouble()
            return (minutes * 60 * 1000).toLong()
        }

        fun isServiceRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (TrackingService::class.java.name == service.service.className) {
                    return true
                }
            }
            return false
        }

        fun isPaused(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_IS_PAUSED, false)
        }

        fun broadcastStateChanged(context: Context, isTracking: Boolean, isPaused: Boolean, lat: Double = 0.0, lng: Double = 0.0) {
            val intent = Intent(ACTION_BROADCAST_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_TRACKING, isTracking)
                putExtra(EXTRA_IS_PAUSED, isPaused)
                putExtra(EXTRA_LATITUDE, lat)
                putExtra(EXTRA_LONGITUDE, lng)
                setPackage(context.packageName)
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }

    private var isPaused = false
    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var pausedLat: Double? = null
    private var pausedLng: Double? = null
    private var lastMovementTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when GPS tracking is active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(isPaused: Boolean): Notification {
        val title = if (isPaused) "GPS Tracking (Paused)" else "GPS Tracking Active"
        val content = if (isPaused) "Dwelling - waiting for movement..." else "Recording your location..."
        val stopIntent = Intent(this, TrackingService::class.java).apply { action = ACTION_STOP }
        val pendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Tracking",
                pendingIntent
            )
            .build()
    }

    private fun startTracking() {
        val notification = createNotification(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isPaused = false
        lastLat = null
        lastLng = null
        pausedLat = null
        pausedLng = null
        lastMovementTime = System.currentTimeMillis()
        pointCount = 0

        val movementThreshold = getMovementThreshold(this)
        val dwellTimeoutMs = getDwellTimeoutMs(this)

        prefs.edit().putBoolean(KEY_IS_PAUSED, false).commit()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    handleLocation(location, movementThreshold, dwellTimeoutMs)
                }
            }
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(4000L)
            .build()

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@TrackingService)
            val session = Session(
                startTime = System.currentTimeMillis(),
                endTime = null,
                totalPoints = 0
            )
            currentSessionId = db.sessionDao().insertSession(session)
            
            lastLat = null
            lastLng = null
            broadcastStateChanged(this@TrackingService, true, false, 0.0, 0.0)

            updateJob = launch {
                while (isActive) {
                    delay(2000)
                    updateNotification()
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }

    private fun handleLocation(location: android.location.Location, movementThreshold: Double, dwellTimeoutMs: Long) {
        val lat = location.latitude
        val lng = location.longitude
        val alt = location.altitude

        if (isPaused) {
            handlePausedLocation(lat, lng, alt, movementThreshold)
        } else {
            handleActiveLocation(lat, lng, alt, movementThreshold, dwellTimeoutMs)
        }
    }

    private fun handleActiveLocation(lat: Double, lng: Double, altitude: Double, threshold: Double, dwellTimeout: Long) {
        var shouldRecord = false

        if (lastLat == null || lastLng == null) {
            shouldRecord = true
            lastLat = lat
            lastLng = lng
            lastMovementTime = System.currentTimeMillis()
        } else {
            val dist = DistanceCalculator.haversineDistance(lastLat!!, lastLng!!, lat, lng)
            if (dist > threshold) {
                shouldRecord = true
                lastLat = lat
                lastLng = lng
                lastMovementTime = System.currentTimeMillis()
            } else {
                val elapsed = System.currentTimeMillis() - lastMovementTime
                if (elapsed > dwellTimeout) {
                    isPaused = true
                    pausedLat = lastLat
                    pausedLng = lastLng
                    prefs.edit().putBoolean(KEY_IS_PAUSED, true).commit()
                    lifecycleScope.launch(Dispatchers.Main) {
                        updateNotification()
                    }
                    lastMovementTime = System.currentTimeMillis()
                    broadcastStateChanged(this@TrackingService, true, true, lastLat ?: 0.0, lastLng ?: 0.0)
                }
            }
        }

        if (shouldRecord) {
            recordPoint(lat, lng, altitude)
        }
    }

    private fun handlePausedLocation(lat: Double, lng: Double, altitude: Double, threshold: Double) {
        val dist = if (pausedLat != null && pausedLng != null) {
            DistanceCalculator.haversineDistance(pausedLat!!, pausedLng!!, lat, lng)
        } else {
            0.0
        }

        if (dist > threshold) {
            isPaused = false
            pausedLat = null
            pausedLng = null
            lastLat = lat
            lastLng = lng
            lastMovementTime = System.currentTimeMillis()

            prefs.edit().putBoolean(KEY_IS_PAUSED, false).commit()
            
            recordPoint(lat, lng, altitude)
            
            broadcastStateChanged(this, true, false, lat, lng)
        }
    }

    private fun recordPoint(lat: Double, lng: Double, altitude: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@TrackingService)
            val point = LocationPoint(
                sessionId = currentSessionId,
                latitude = lat,
                longitude = lng,
                altitude = altitude,
                timestamp = System.currentTimeMillis()
            )
            db.locationPointDao().insertPoint(point)
            pointCount++
            updateNotification()
        }
    }

    private fun updateNotification() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@TrackingService)
            val points = db.locationPointDao().getPointsBySessionSync(currentSessionId)
            val distance = DistanceCalculator.calculateTotalDistance(points)
            val distanceStr = DistanceCalculator.formatDistance(distance)
            val session = db.sessionDao().getSessionById(currentSessionId)

            val startTime = session?.startTime ?: System.currentTimeMillis()
            val durationStr = DistanceCalculator.formatDuration(System.currentTimeMillis() - startTime)

            val title = if (isPaused) "GPS Tracking (Paused)" else "GPS Tracking Active"
            val content = if (isPaused) {
                "Paused - waiting for movement"
            } else {
                "Points: $pointCount | $distanceStr | $durationStr"
            }

            val stopIntent = Intent(this@TrackingService, TrackingService::class.java).apply { action = ACTION_STOP }
            val pendingIntent = PendingIntent.getService(this@TrackingService, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            val notification = NotificationCompat.Builder(this@TrackingService, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop Tracking",
                    pendingIntent
                )
                .build()

            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun stopTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        updateJob?.cancel()

        runBlocking(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@TrackingService)
            if (currentSessionId != -1L) {
                val session = db.sessionDao().getSessionById(currentSessionId)
                session?.let {
                    val pc = db.locationPointDao().getPointCount(currentSessionId)
                    db.sessionDao().updateSession(
                        it.copy(
                            endTime = System.currentTimeMillis(),
                            totalPoints = pc
                        )
                    )
                }
            }
            currentSessionId = -1
            pointCount = 0
            isPaused = false
            lastLat = null
            lastLng = null
            pausedLat = null
            pausedLng = null

            prefs.edit().putBoolean(KEY_IS_PAUSED, false).commit()
            
            broadcastStateChanged(this@TrackingService, false, false, 0.0, 0.0)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
