package com.silentguard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.silentguard.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }
    private val phoneStatePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // Needed so the service can tell a real phone call apart from
        // "nothing happening" and back off instead of fighting it blind.
        phoneStatePermissionLauncher.launch(android.Manifest.permission.READ_PHONE_STATE)

        binding.switchEnable.isChecked = isServiceEnabled()
        refreshStatus()

        binding.switchEnable.setOnCheckedChangeListener { _, checked ->
            setServiceEnabled(checked)
            if (checked) {
                ContextCompat.startForegroundService(this, Intent(this, SilentModeService::class.java))
            } else {
                startService(Intent(this, SilentModeService::class.java).apply {
                    action = SilentModeService.ACTION_STOP
                })
            }
            refreshStatus()
        }

        binding.buttonDndAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }

        binding.buttonBatteryExemption.setOnClickListener {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val dndGranted = AudioMuteManager.hasNotificationPolicyAccess(this)
        binding.textDndStatus.text = getString(
            if (dndGranted) R.string.status_granted else R.string.status_not_granted
        )

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryExempt = pm.isIgnoringBatteryOptimizations(packageName)
        binding.textBatteryStatus.text = getString(
            if (batteryExempt) R.string.status_granted else R.string.status_not_granted
        )
    }

    private fun isServiceEnabled(): Boolean =
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getBoolean(Prefs.KEY_ENABLED, false)

    private fun setServiceEnabled(enabled: Boolean) {
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Prefs.KEY_ENABLED, enabled)
            .apply()
    }
}
