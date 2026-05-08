package com.example.gpstracker

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gpstracker.data.AppDatabase
import com.example.gpstracker.databinding.ActivitySessionDetailBinding
import com.example.gpstracker.utils.DistanceCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class SessionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.visibility = View.GONE

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Session Details"

        binding.loadingBar.visibility = View.VISIBLE

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
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadSession(sessionId: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@SessionDetailActivity)
                val session = db.sessionDao().getSessionById(sessionId)
                val points = db.locationPointDao().getPointsBySessionSync(sessionId)

                if (session == null || points.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SessionDetailActivity, "No data found", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@launch
                }

                val distanceStr = DistanceCalculator.formatDistance(
                    DistanceCalculator.calculateTotalDistance(points)
                )

                val dateText = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(session.startTime))

                val durationText = if (session.endTime != null) {
                    DistanceCalculator.formatDuration(session.endTime - session.startTime)
                } else {
                    "Active..."
                }

                val infoText = "$dateText\nDuration: $durationText\nPoints: ${session.totalPoints}\nDistance: $distanceStr"

                val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }

                withContext(Dispatchers.Main) {
                    binding.sessionInfo.text = infoText
                    binding.mapView.post {
                        displayRouteOnMap(geoPoints, points)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingBar.visibility = View.GONE
                    Toast.makeText(this@SessionDetailActivity, "Error loading session: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayRouteOnMap(
        geoPoints: List<GeoPoint>,
        rawPoints: List<com.example.gpstracker.data.LocationPoint>
    ) {
        val mapView = binding.mapView
        try {
            val polyline = Polyline()
            polyline.setPoints(geoPoints)
            polyline.outlinePaint.color = getColor(android.R.color.holo_blue_dark)
            polyline.outlinePaint.strokeWidth = 8f
            mapView.overlays.add(polyline)

            if (rawPoints.isNotEmpty()) {
                val startPoint = GeoPoint(rawPoints.first().latitude, rawPoints.first().longitude)
                val startMarker = Marker(mapView)
                startMarker.position = startPoint
                startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                startMarker.title = "Start"
                startMarker.snippet = "Tracking started here"
                mapView.overlays.add(startMarker)
            }

            if (rawPoints.size > 1) {
                val endPoint = GeoPoint(rawPoints.last().latitude, rawPoints.last().longitude)
                val endMarker = Marker(mapView)
                endMarker.position = endPoint
                endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                endMarker.title = "End"
                endMarker.snippet = "Tracking ended here"
                mapView.overlays.add(endMarker)
            }

            if (geoPoints.isNotEmpty()) {
                val bounds = BoundingBox.fromGeoPointsSafe(geoPoints)
                mapView.zoomToBoundingBox(bounds, false, 100)
            }

            mapView.invalidate()
            mapView.visibility = View.VISIBLE
        } catch (e: Exception) {
            Toast.makeText(this, "Error displaying map: ${e.message}", Toast.LENGTH_LONG).show()
            mapView.visibility = View.VISIBLE
        } finally {
            binding.loadingBar.visibility = View.GONE
        }
    }
}
