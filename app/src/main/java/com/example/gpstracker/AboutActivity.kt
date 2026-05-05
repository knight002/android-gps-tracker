package com.example.gpstracker;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.example.gpstracker.databinding.ActivityAboutBinding;

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);
        val binding = ActivityAboutBinding.inflate(layoutInflater);
        setContentView(binding.root);

        supportActionBar?.setDisplayHomeAsUpEnabled(true);
        supportActionBar?.title = "About";

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName;
        } catch (_: Exception) {
            "Unknown";
        }

        binding.versionText.text = "Version: $versionName";
    }

    override fun onSupportNavigateUp(): Boolean {
        finish();
        return true;
    }
}
