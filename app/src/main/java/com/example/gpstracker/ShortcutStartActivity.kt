package com.example.gpstracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat

class ShortcutStartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (TrackingService.isServiceRunning(this)) {
            Toast.makeText(this, "Tracking is already active", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!hasRequiredPermissions()) {
            Toast.makeText(this, "Grant permissions first via the Start Tracking button", Toast.LENGTH_LONG).show()
            val mainIntent = Intent(this, MainActivity::class.java)
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(mainIntent)
            finish()
            return
        }

        startTrackingService()
        Toast.makeText(this, "Tracking started", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun startTrackingService() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val fineLocation = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return fineLocation && backgroundLocation && notifications
    }
}
