package com.example.gpstracker

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
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

// MapView initialization approach (critical for performance):
// MapView is NOT inflated in XML (activity_session_detail.xml uses a
// FrameLayout container instead). It is created programmatically in
// buildMapAndDisplayRoute() AFTER session data loads from the DB.
//
// Reason: osmdroid's MapView constructor triggers heavy initialization
// (tile cache SQLite DB, tile provider, renderer setup) that blocks the
// main thread. If inflated via setContentView(), the UI freezes before
// rendering, causing the black-screen hang on session tap.
//
// Instead: the spinner (ProgressBar) renders immediately from XML,
// then a coroutine loads data on IO, and MapView is built on the main
// thread afterward — the user sees the spinner during the brief freeze.
//
// OSMdroid Configuration is also duplicated here (initOsMdroidConfig())
// as a fallback for when the activity is restored from recents, in case
// MainActivity's config init was skipped.
class SessionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionDetailBinding
    private var mapView: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initOsMdroidConfig()

        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

    private fun initOsMdroidConfig() {
        if (Configuration.getInstance().userAgentValue == null) {
            val basePath = File(getExternalFilesDir(null) ?: filesDir, "osmdroid")
            basePath.mkdirs()
            val cacheDir = File(basePath, "cache")
            cacheDir.mkdirs()
            Configuration.getInstance().userAgentValue = packageName
            Configuration.getInstance().osmdroidBasePath = basePath
            Configuration.getInstance().osmdroidTileCache = cacheDir
        }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView?.onDetach()
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
                    buildMapAndDisplayRoute(geoPoints, points)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingBar.visibility = View.GONE
                    Toast.makeText(this@SessionDetailActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildMapAndDisplayRoute(
        geoPoints: List<GeoPoint>,
        rawPoints: List<com.example.gpstracker.data.LocationPoint>
    ) {
        try {
            val mv = MapView(this).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            mapView = mv
            binding.mapContainer.addView(mv, 0)

            mv.post {
                try {
                    val polyline = Polyline()
                    polyline.setPoints(geoPoints)
                    polyline.outlinePaint.color = getColor(android.R.color.holo_blue_dark)
                    polyline.outlinePaint.strokeWidth = 8f
                    mv.overlays.add(polyline)

                    if (rawPoints.isNotEmpty()) {
                        val startPoint = GeoPoint(rawPoints.first().latitude, rawPoints.first().longitude)
                        val startMarker = Marker(mv)
                        startMarker.position = startPoint
                        startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        startMarker.title = "Start"
                        startMarker.snippet = "Tracking started here"
                        mv.overlays.add(startMarker)
                    }

                    if (rawPoints.size > 1) {
                        val endPoint = GeoPoint(rawPoints.last().latitude, rawPoints.last().longitude)
                        val endMarker = Marker(mv)
                        endMarker.position = endPoint
                        endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        endMarker.title = "End"
                        endMarker.snippet = "Tracking ended here"
                        mv.overlays.add(endMarker)
                    }

                    if (geoPoints.isNotEmpty()) {
                        val bounds = BoundingBox.fromGeoPointsSafe(geoPoints)
                        mv.zoomToBoundingBox(bounds, false, 100)
                    }

                    mv.invalidate()
                } catch (e: Exception) {
                    Toast.makeText(this@SessionDetailActivity, "Error displaying map: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    binding.loadingBar.visibility = View.GONE
                }
            }
        } catch (e: Exception) {
            binding.loadingBar.visibility = View.GONE
            Toast.makeText(this, "Error creating map: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
