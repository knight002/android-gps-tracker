package com.example.gpstracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
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
    }

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

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, TrackingService::class.java).apply { action = ACTION_STOP }
        val pendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracking Active")
            .setContentText("Recording your location...")
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
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(this@TrackingService)
                        val point = LocationPoint(
                            sessionId = currentSessionId,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            altitude = location.altitude,
                            timestamp = System.currentTimeMillis()
                        )
                        db.locationPointDao().insertPoint(point)
                        pointCount++
                        updateNotification()
                    }
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

    private fun updateNotification() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@TrackingService)
            val points = db.locationPointDao().getPointsBySessionSync(currentSessionId)
            val distance = DistanceCalculator.calculateTotalDistance(points)
            val distanceStr = DistanceCalculator.formatDistance(distance)
            val session = db.sessionDao().getSessionById(currentSessionId)

            val startTime = session?.startTime ?: System.currentTimeMillis()
            val durationStr = DistanceCalculator.formatDuration(System.currentTimeMillis() - startTime)

            val stopIntent = Intent(this@TrackingService, TrackingService::class.java).apply { action = ACTION_STOP }
            val pendingIntent = PendingIntent.getService(this@TrackingService, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            val notification = NotificationCompat.Builder(this@TrackingService, CHANNEL_ID)
                .setContentTitle("GPS Tracking Active")
                .setContentText("Points: $pointCount | $distanceStr | $durationStr")
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

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@TrackingService)
            if (currentSessionId != -1L) {
                val session = db.sessionDao().getSessionById(currentSessionId)
                session?.let {
                    val pointCount = db.locationPointDao().getPointCount(currentSessionId)
                    db.sessionDao().updateSession(
                        it.copy(
                            endTime = System.currentTimeMillis(),
                            totalPoints = pointCount
                        )
                    )
                }
            }
            currentSessionId = -1
            pointCount = 0
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
