package com.example.gpstracker

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.gpstracker.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        loadSettings()

        binding.saveButton.setOnClickListener {
            saveSettings()
        }

        binding.cancelButton.setOnClickListener {
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(TrackingService.PREFS_NAME, MODE_PRIVATE)
        val threshold = prefs.getFloat(TrackingService.KEY_MOVEMENT_THRESHOLD, Config.DEFAULT_MOVEMENT_THRESHOLD_M.toFloat())
        val timeout = prefs.getFloat(TrackingService.KEY_DWELL_TIMEOUT, Config.DEFAULT_DWELL_TIMEOUT_MINUTES.toFloat())

        binding.movementThresholdEdit.setText(threshold.toString())
        binding.dwellTimeoutEdit.setText(timeout.toString())
    }

    private fun saveSettings() {
        val thresholdStr = binding.movementThresholdEdit.text.toString()
        val timeoutStr = binding.dwellTimeoutEdit.text.toString()

        val threshold = thresholdStr.toFloatOrNull()
        val timeout = timeoutStr.toFloatOrNull()

        if (threshold == null || threshold <= 0) {
            binding.movementThresholdEdit.error = "Enter a valid number > 0"
            return
        }
        if (timeout == null || timeout <= 0) {
            binding.dwellTimeoutEdit.error = "Enter a valid number > 0"
            return
        }

        val prefs = getSharedPreferences(TrackingService.PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putFloat(TrackingService.KEY_MOVEMENT_THRESHOLD, threshold)
            .putFloat(TrackingService.KEY_DWELL_TIMEOUT, timeout)
            .apply()

        finish()
    }
}
