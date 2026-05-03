package com.example.gpstracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gpstracker.data.AppDatabase
import com.example.gpstracker.data.Session
import com.example.gpstracker.databinding.ActivityMainBinding
import com.example.gpstracker.utils.DistanceCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: AppDatabase
    private lateinit var sessionAdapter: SessionAdapter
    private var isTracking = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions[Manifest.permission.ACCESS_BACKGROUND_LOCATION] == true
        } else {
            true
        }
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] == true
        } else {
            true
        }

        if (fineLocationGranted && backgroundLocationGranted && notificationsGranted) {
            toggleTracking()
        } else {
            Toast.makeText(this, "Location permissions are required for tracking", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)

        setupRecyclerView()
        setupTrackingButton()
        checkIfTracking()

        observeSessions()
    }

    private fun setupRecyclerView() {
        sessionAdapter = SessionAdapter { session ->
            val intent = Intent(this, SessionDetailActivity::class.java)
            intent.putExtra("SESSION_ID", session.id)
            startActivity(intent)
        }
        binding.sessionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = sessionAdapter
        }
    }

    private fun setupTrackingButton() {
        binding.trackButton.setOnClickListener {
            if (!hasRequiredPermissions()) {
                requestPermissions()
            } else {
                toggleTracking()
            }
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

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun toggleTracking() {
        if (isTracking) {
            stopTracking()
        } else {
            startTracking()
        }
    }

    private fun startTracking() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isTracking = true
        updateButtonState()
        Toast.makeText(this, "Tracking started", Toast.LENGTH_SHORT).show()
    }

    private fun stopTracking() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        }
        startService(intent)
        isTracking = false
        updateButtonState()
        Toast.makeText(this, "Tracking stopped", Toast.LENGTH_SHORT).show()
    }

    private fun checkIfTracking() {
        val intent = Intent(this, TrackingService::class.java)
        isTracking = false
        updateButtonState()
    }

    private fun updateButtonState() {
        if (isTracking) {
            binding.trackButton.text = "Stop Tracking"
            binding.trackButton.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            binding.statusText.text = "Tracking active..."
        } else {
            binding.trackButton.text = "Start Tracking"
            binding.trackButton.setBackgroundColor(getColor(android.R.color.holo_green_dark))
            binding.statusText.text = "Ready to track"
        }
    }

    private fun observeSessions() {
        lifecycleScope.launch {
            database.sessionDao().getAllSessions().collectLatest { sessions ->
                withContext(Dispatchers.Main) {
                    sessionAdapter.submitList(sessions)
                    if (sessions.isEmpty()) {
                        binding.noSessionsText.visibility = android.view.View.VISIBLE
                    } else {
                        binding.noSessionsText.visibility = android.view.View.GONE
                    }
                }
            }
        }
    }
}

class SessionAdapter(
    private val onSessionClick: (Session) -> Unit
) : RecyclerView.Adapter<SessionAdapter.SessionViewHolder>() {

    private var sessions: List<Session> = emptyList()

    fun submitList(newSessions: List<Session>) {
        sessions = newSessions
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SessionViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(sessions[position])
    }

    override fun getItemCount() = sessions.size

    inner class SessionViewHolder(itemView: android.view.View) :
        RecyclerView.ViewHolder(itemView) {

        private val db = AppDatabase.getDatabase(itemView.context)

        fun bind(session: Session) {
            val dateText = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(session.startTime))

            val durationText = if (session.endTime != null) {
                DistanceCalculator.formatDuration(session.endTime - session.startTime)
            } else {
                "Active..."
            }

            itemView.findViewById<android.widget.TextView>(R.id.sessionDate).text = dateText
            itemView.findViewById<android.widget.TextView>(R.id.sessionDuration).text = "Duration: $durationText"
            itemView.findViewById<android.widget.TextView>(R.id.sessionPoints).text = "Points: ${session.totalPoints}"

            val distanceView = itemView.findViewById<android.widget.TextView>(R.id.sessionDistance)

            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val points = db.locationPointDao().getPointsBySessionSync(session.id)
                val distance = DistanceCalculator.calculateTotalDistance(points)
                val distanceStr = DistanceCalculator.formatDistance(distance)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    distanceView.text = "Distance: $distanceStr"
                }
            }

            itemView.setOnClickListener { onSessionClick(session) }
        }
    }
}
