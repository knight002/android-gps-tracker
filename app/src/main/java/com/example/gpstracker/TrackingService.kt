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
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date

class TrackingService : LifecycleService() {

    private lateinit var logFile: File

    private fun log(msg: String) {
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS").format(Date())
            val logMsg = "[$timestamp] $msg\n"
            FileWriter(logFile, true).use { it.write(logMsg) }
            println("TrackingService: $msg")
        } catch (_: Exception) {}
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var currentSessionId: Long = -1
    private var sessionStartTime: Long = 0
    private var pointCount = 0
    private var totalDistance: Double = 0.0
    private var updateJob: Job? = null
    private var dwellJob: Job? = null
    private var trackingStatus = TrackingStatus.READY
    private lateinit var prefs: SharedPreferences

    companion object {
        const val CHANNEL_ID = "gps_tracking_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_BROADCAST_STATE_CHANGED = "com.example.gpstracker.TRACKING_STATE_CHANGED"
        const val EXTRA_STATUS = "status"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_POINTS = "points"
        const val EXTRA_DISTANCE_STR = "distance_str"
        const val EXTRA_DURATION_STR = "duration_str"
        const val PREFS_NAME = "gps_tracker_prefs"
        const val KEY_MOVEMENT_THRESHOLD = "movement_threshold"
        const val DEFAULT_MOVEMENT_THRESHOLD_M = 20.0
        const val KEY_DWELL_TIME = "dwell_time_seconds"
        const val DEFAULT_DWELL_TIME_S = 15

        @Volatile
        var lastKnownStatus: TrackingStatus = TrackingStatus.READY

        fun getMovementThreshold(context: Context): Double {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getFloat(KEY_MOVEMENT_THRESHOLD, DEFAULT_MOVEMENT_THRESHOLD_M.toFloat()).toDouble()
        }

        fun getDwellTimeMs(context: Context): Long {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_DWELL_TIME, DEFAULT_DWELL_TIME_S) * 1000L
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

        fun broadcastStateChanged(
            context: Context,
            status: TrackingStatus = TrackingStatus.READY,
            lat: Double = 0.0,
            lng: Double = 0.0,
            points: Int = 0,
            distanceStr: String = "",
            durationStr: String = ""
        ) {
            lastKnownStatus = status
            val intent = Intent(ACTION_BROADCAST_STATE_CHANGED).apply {
                putExtra(EXTRA_STATUS, status.name)
                putExtra(EXTRA_LATITUDE, lat)
                putExtra(EXTRA_LONGITUDE, lng)
                putExtra(EXTRA_POINTS, points)
                putExtra(EXTRA_DISTANCE_STR, distanceStr)
                putExtra(EXTRA_DURATION_STR, durationStr)
                setPackage(context.packageName)
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }

    private var lastLat: Double? = null  // For UI display
    private var lastLng: Double? = null  // For UI display
    private var lastRecordedLat: Double? = null  // Last point saved to DB
    private var lastRecordedLng: Double? = null  // Last point saved to DB

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        logFile = File(getExternalFilesDir(null), "tracking_log.txt")
        log("Service created")
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

    private fun startTracking() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        lastLat = null
        lastLng = null
        lastRecordedLat = null
        lastRecordedLng = null
        pointCount = 0
        totalDistance = 0.0
        trackingStatus = TrackingStatus.TRACKING
        dwellJob?.cancel()
        dwellJob = null

        val movementThreshold = getMovementThreshold(this)
        log("startTracking with threshold=$movementThreshold")

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                log("onLocationResult called")
                result.lastLocation?.let { location ->
                    handleLocation(location, movementThreshold)
                }
            }
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@TrackingService)
            sessionStartTime = System.currentTimeMillis()
            val session = Session(
                startTime = sessionStartTime,
                endTime = null,
                totalPoints = 0
            )
            currentSessionId = db.sessionDao().insertSession(session)

            broadcastStateChanged(this@TrackingService, trackingStatus, 0.0, 0.0, 0, "0 m", "00:00:00")

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

    private fun handleLocation(location: android.location.Location, movementThreshold: Double) {
        val lat = location.latitude
        val lng = location.longitude
        val alt = location.altitude

        // Always update UI display location
        lastLat = lat
        lastLng = lng

        log("handleLocation lat=$lat, lng=$lng, lastRecordedLat=$lastRecordedLat, lastRecordedLng=$lastRecordedLng")

        // If no last recorded point, record this one
        if (lastRecordedLat == null || lastRecordedLng == null) {
            log("First point, recording...")
            lastRecordedLat = lat
            lastRecordedLng = lng
            trackingStatus = TrackingStatus.TRACKING
            dwellJob?.cancel()
            dwellJob = null
            recordPoint(lat, lng, alt)
            val durStr = DistanceCalculator.formatDuration(System.currentTimeMillis() - sessionStartTime)
            broadcastStateChanged(this, trackingStatus, lat, lng, pointCount, "0 m", durStr)
            return
        }

        // Calculate distance from last recorded point
        val dist = DistanceCalculator.haversineDistance(lastRecordedLat!!, lastRecordedLng!!, lat, lng)
        log("dist=$dist, threshold=$movementThreshold")

        // Only save to DB if movement > threshold
        if (dist > movementThreshold) {
            log("Movement detected, recording point...")
            lastRecordedLat = lat
            lastRecordedLng = lng
            totalDistance += dist
            trackingStatus = TrackingStatus.TRACKING
            dwellJob?.cancel()
            dwellJob = null
            recordPoint(lat, lng, alt)
        } else if (trackingStatus == TrackingStatus.TRACKING && dwellJob == null) {
            log("No movement, starting dwell timer...")
            dwellJob = lifecycleScope.launch {
                val dwellMs = getDwellTimeMs(this@TrackingService)
                delay(dwellMs)
                trackingStatus = TrackingStatus.DWELLING
                log("Status changed to DWELLING")
                val durStr = DistanceCalculator.formatDuration(System.currentTimeMillis() - sessionStartTime)
                val distStr = DistanceCalculator.formatDistance(totalDistance)
                broadcastStateChanged(this@TrackingService, trackingStatus, lastLat ?: 0.0, lastLng ?: 0.0, pointCount, distStr, durStr)
            }
        }

        val durStr = DistanceCalculator.formatDuration(System.currentTimeMillis() - sessionStartTime)
        val distStr = DistanceCalculator.formatDistance(totalDistance)
        broadcastStateChanged(this, trackingStatus, lat, lng, pointCount, distStr, durStr)
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
            log("Point recorded: lat=$lat, lng=$lng, total=$pointCount")
            updateNotification()
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, TrackingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentIntent(mainPendingIntent)
            .setContentTitle("Tracking")
            .setContentText("Recording your location...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Tracking",
                stopPendingIntent
            )
            .build()
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

            val title = if (trackingStatus == TrackingStatus.DWELLING) "Stationary" else "Tracking"
            val content = "Points: $pointCount | $distanceStr | $durationStr"

            val stopIntent = Intent(this@TrackingService, TrackingService::class.java).apply { action = ACTION_STOP }
            val stopPendingIntent = PendingIntent.getService(this@TrackingService, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            val mainIntent = Intent(this@TrackingService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val mainPendingIntent = PendingIntent.getActivity(this@TrackingService, 1, mainIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            val notification = NotificationCompat.Builder(this@TrackingService, CHANNEL_ID)
                .setContentIntent(mainPendingIntent)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop Tracking",
                    stopPendingIntent
                )
                .build()

            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)

            broadcastStateChanged(
                this@TrackingService,
                trackingStatus,
                lastLat ?: 0.0, lastLng ?: 0.0,
                pointCount, distanceStr, durationStr
            )
        }
    }

    private fun stopTracking() {
        log("stopTracking called")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        updateJob?.cancel()
        dwellJob?.cancel()
        dwellJob = null

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
            sessionStartTime = 0
            pointCount = 0
            totalDistance = 0.0
            lastLat = null
            lastLng = null
            lastRecordedLat = null
            lastRecordedLng = null
            trackingStatus = TrackingStatus.READY
        }
        lastKnownStatus = TrackingStatus.READY

        log("Tracking stopped. Log saved to ${logFile.absolutePath}")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
