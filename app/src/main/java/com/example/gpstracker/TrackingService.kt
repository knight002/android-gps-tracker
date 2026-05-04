package com.example.gpstracker

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    companion object {
        const val CHANNEL_ID = "gps_tracking_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_BROADCAST_STATE_CHANGED = "com.example.gpstracker.TRACKING_STATE_CHANGED"
        const val EXTRA_IS_TRACKING = "is_tracking"
        const val EXTRA_IS_PAUSED = "is_paused"
        const val PREFS_NAME = "gps_tracker_prefs"
        const val KEY_IS_TRACKING = "is_tracking"

        private const val DWELL_TIMEOUT_MS = 5 * 60 * 1000L
        private const val MOVEMENT_THRESHOLD_M = 20.0

        fun isServiceRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (TrackingService::class.java.name == service.service.className) {
                    return true
                }
            }
            return false
        }

        fun broadcastStateChanged(context: Context, isTracking: Boolean, isPaused: Boolean) {
            val intent = Intent(ACTION_BROADCAST_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_TRACKING, isTracking)
                putExtra(EXTRA_IS_PAUSED, isPaused)
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

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    handleLocation(location)
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

            broadcastStateChanged(this@TrackingService, true, false)

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

    private fun handleLocation(location: android.location.Location) {
        val lat = location.latitude
        val lng = location.longitude
        val alt = location.altitude

        if (isPaused) {
            handlePausedLocation(lat, lng, alt)
        } else {
            handleActiveLocation(lat, lng, alt)
        }
    }

    private fun handleActiveLocation(lat: Double, lng: Double, altitude: Double) {
        var shouldRecord = false

        if (lastLat == null || lastLng == null) {
            shouldRecord = true
            lastLat = lat
            lastLng = lng
            lastMovementTime = System.currentTimeMillis()
        } else {
            val dist = DistanceCalculator.haversineDistance(lastLat!!, lastLng!!, lat, lng)
            if (dist > MOVEMENT_THRESHOLD_M) {
                shouldRecord = true
                lastLat = lat
                lastLng = lng
                lastMovementTime = System.currentTimeMillis()
            } else {
                val elapsed = System.currentTimeMillis() - lastMovementTime
                if (elapsed > DWELL_TIMEOUT_MS) {
                    isPaused = true
                    pausedLat = lastLat
                    pausedLng = lastLng
                    lifecycleScope.launch(Dispatchers.Main) {
                        updateNotification()
                    }
                    lastMovementTime = System.currentTimeMillis()
                }
            }
        }

        if (shouldRecord) {
            recordPoint(lat, lng, altitude)
        }
    }

    private fun handlePausedLocation(lat: Double, lng: Double, altitude: Double) {
        val dist = if (pausedLat != null && pausedLng != null) {
            DistanceCalculator.haversineDistance(pausedLat!!, pausedLng!!, lat, lng)
        } else {
            0.0
        }

        if (dist > MOVEMENT_THRESHOLD_M) {
            isPaused = false
            pausedLat = null
            pausedLng = null
            lastLat = lat
            lastLng = lng
            lastMovementTime = System.currentTimeMillis()

            recordPoint(lat, lng, altitude)

            broadcastStateChanged(this, true, false)
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

            broadcastStateChanged(this@TrackingService, false, false)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
