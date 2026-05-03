package com.example.gpstracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gpstracker.data.AppDatabase
import com.example.gpstracker.databinding.ActivitySessionDetailBinding
import com.example.gpstracker.utils.DistanceCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

class SessionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionDetailBinding
    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val basePath = File(getExternalFilesDir(null) ?: filesDir, "osmdroid")
        if (!basePath.exists()) {
            basePath.mkdirs()
        }
        val cacheDir = File(basePath, "cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = basePath
        Configuration.getInstance().osmdroidTileCache = cacheDir

        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Session Details"

        mapView = binding.mapView
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)

        val sessionId = intent.getLongExtra("SESSION_ID", -1L)
        if (sessionId != -1L) {
            loadSession(sessionId)
        } else {
            Toast.makeText(this, "Invalid session", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadSession(sessionId: Long) {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@SessionDetailActivity)
                val session = db.sessionDao().getSessionById(sessionId)
                val points = db.locationPointDao().getPointsBySessionSync(sessionId)

                withContext(Dispatchers.Main) {
                    if (session == null || points.isEmpty()) {
                        Toast.makeText(this@SessionDetailActivity, "No data found", Toast.LENGTH_SHORT).show()
                        finish()
                        return@withContext
                    }

                    val dateText = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(session.startTime))

                    val durationText = if (session.endTime != null) {
                        DistanceCalculator.formatDuration(session.endTime - session.startTime)
                    } else {
                        "Active..."
                    }

                    val distance = DistanceCalculator.calculateTotalDistance(points)
                    val distanceStr = DistanceCalculator.formatDistance(distance)

                    binding.sessionInfo.text = "$dateText\nDuration: $durationText\nPoints: ${session.totalPoints}\nDistance: $distanceStr"

                    displayRouteOnMap(points)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SessionDetailActivity, "Error loading session: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayRouteOnMap(points: List<com.example.gpstracker.data.LocationPoint>) {
        try {
            val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }

            val polyline = Polyline()
            polyline.setPoints(geoPoints)
            polyline.color = getColor(android.R.color.holo_blue_dark)
            polyline.width = 8f
            mapView.overlays.add(polyline)

            if (points.isNotEmpty()) {
                val startPoint = GeoPoint(points.first().latitude, points.first().longitude)
                val startMarker = Marker(mapView)
                startMarker.position = startPoint
                startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                startMarker.title = "Start"
                startMarker.snippet = "Tracking started here"
                mapView.overlays.add(startMarker)
            }

            if (points.size > 1) {
                val endPoint = GeoPoint(points.last().latitude, points.last().longitude)
                val endMarker = Marker(mapView)
                endMarker.position = endPoint
                endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                endMarker.title = "End"
                endMarker.snippet = "Tracking ended here"
                mapView.overlays.add(endMarker)
            }

            if (geoPoints.isNotEmpty()) {
                val bounds = BoundingBox.fromGeoPointsSafe(geoPoints)
                mapView.zoomToBoundingBox(bounds, true, 100)
            }

            mapView.invalidate()
        } catch (e: Exception) {
            Toast.makeText(this, "Error displaying map: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
