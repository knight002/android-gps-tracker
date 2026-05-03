package com.example.gpstracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gpstracker.data.AppDatabase
import com.example.gpstracker.data.Session
import com.example.gpstracker.databinding.ActivityMainBinding
import com.example.gpstracker.utils.DistanceCalculator
import com.example.gpstracker.utils.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: AppDatabase
    private lateinit var sessionAdapter: SessionAdapter

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getBooleanExtra(TrackingService.EXTRA_IS_TRACKING, false)?.let { isTracking ->
                updateButtonState(isTracking)
            }
        }
    }

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

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkForUpdates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)

        setupRecyclerView()
        setupTrackingButton()
        setupUpdateButton()
        observeSessions()
    }

    override fun onResume() {
        super.onResume()
        syncTrackingState()
        registerStateReceiver()
    }

    override fun onPause() {
        super.onPause()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
    }

    private fun syncTrackingState() {
        val isRunning = TrackingService.isServiceRunning(this)
        updateButtonState(isRunning)
    }

    private fun registerStateReceiver() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        val filter = IntentFilter(TrackingService.ACTION_BROADCAST_STATE_CHANGED)
        LocalBroadcastManager.getInstance(this).registerReceiver(stateReceiver, filter)
    }

    private fun setupRecyclerView() {
        sessionAdapter = SessionAdapter(
            onSessionClick = { session ->
                val intent = Intent(this, SessionDetailActivity::class.java)
                intent.putExtra("SESSION_ID", session.id)
                startActivity(intent)
            },
            onSessionDelete = { session ->
                showDeleteConfirmation(session)
            }
        )
        binding.sessionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = sessionAdapter
        }
    }

    private fun showDeleteConfirmation(session: Session) {
        AlertDialog.Builder(this)
            .setTitle("Delete Session")
            .setMessage("Are you sure you want to delete this tracking session?")
            .setPositiveButton("Delete") { _, _ ->
                deleteSession(session)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSession(session: Session) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@MainActivity)
            db.sessionDao().deleteSession(session)
            db.locationPointDao().deletePointsBySession(session.id)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Session deleted", Toast.LENGTH_SHORT).show()
            }
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

    private fun setupUpdateButton() {
        binding.updateButton.setOnClickListener {
            checkForUpdates()
        }
    }

    private fun checkForUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("This app needs permission to install updates. Please enable 'Install unknown apps' for GPS Tracker.")
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    intent.data = Uri.parse("package:$packageName")
                    installPermissionLauncher.launch(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        binding.updateButton.isEnabled = false
        binding.updateButton.text = "Checking..."

        lifecycleScope.launch {
            val updateInfo = Updater.checkForUpdates()

            withContext(Dispatchers.Main) {
                binding.updateButton.isEnabled = true
                binding.updateButton.text = "Check for Updates"

                if (updateInfo == null) {
                    Toast.makeText(this@MainActivity, "No updates found or failed to check", Toast.LENGTH_LONG).show()
                    return@withContext
                }

                val info = updateInfo
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update Available: ${info.versionName}")
                    .setMessage(info.releaseNotes)
                    .setPositiveButton("Download & Install") { _, _ ->
                        binding.updateButton.isEnabled = false
                        binding.updateButton.text = "Downloading..."
                        lifecycleScope.launch {
                            Updater.downloadAndInstall(this@MainActivity, info)
                            withContext(Dispatchers.Main) {
                                binding.updateButton.isEnabled = true
                                binding.updateButton.text = "Check for Updates"
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
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
        if (TrackingService.isServiceRunning(this)) {
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
        TrackingService.setTrackingState(this, true)
        updateButtonState(true)
        Toast.makeText(this, "Tracking started", Toast.LENGTH_SHORT).show()
    }

    private fun stopTracking() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        }
        startService(intent)
        updateButtonState(false)
        Toast.makeText(this, "Tracking stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateButtonState(isTracking: Boolean) {
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
    private val onSessionClick: (Session) -> Unit,
    private val onSessionDelete: (Session) -> Unit
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

            itemView.findViewById<android.widget.ImageButton>(R.id.deleteButton).setOnClickListener {
                onSessionDelete(session)
            }
        }
    }
}
