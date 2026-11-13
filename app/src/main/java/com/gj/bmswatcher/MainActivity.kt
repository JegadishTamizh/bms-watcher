package com.gj.bmswatcher

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var etInterval: EditText
    private lateinit var tvStatus: TextView

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(BookingCheckWorker.PREFS, Context.MODE_PRIVATE)
    }

    // Must be kept as a field: SharedPreferences holds listeners weakly.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { renderStatus() }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "Notification permission denied: you will NOT get alerts. Enable it in app settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        etInterval = findViewById(R.id.etInterval)
        tvStatus = findViewById(R.id.tvStatus)

        etUrl.setText(prefs.getString(BookingCheckWorker.KEY_URL, ""))
        etInterval.setText(prefs.getInt(BookingCheckWorker.KEY_INTERVAL, 15).toString())

        findViewById<Button>(R.id.btnStart).setOnClickListener { onStart() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { onStop() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { onTest() }

        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        renderStatus()
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    private fun readInputs(): Pair<String, Int>? {
        val url = etUrl.text.toString().trim()
        val interval = etInterval.text.toString().trim().toIntOrNull() ?: 15
        if (!url.startsWith("http") || BookingCheckWorker.extractDate(url) == null) {
            Toast.makeText(this, "URL must start with http and end with a YYYYMMDD date", Toast.LENGTH_LONG).show()
            return null
        }
        if (interval < 1) {
            Toast.makeText(this, "Interval must be at least 1 minute", Toast.LENGTH_LONG).show()
            return null
        }
        return Pair(url, interval)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun onStart() {
        val (url, interval) = readInputs() ?: return
        prefs.edit()
            .putString(BookingCheckWorker.KEY_URL, url)
            .putInt(BookingCheckWorker.KEY_INTERVAL, interval)
            .putBoolean(BookingCheckWorker.KEY_ACTIVE, true)
            .putString(BookingCheckWorker.KEY_LOG_RESULT, "Waiting for first check...")
            .apply()
        ensureNotificationPermission()
        BookingCheckWorker.startMonitoring(this, interval)
        Toast.makeText(this, "Monitoring started", Toast.LENGTH_SHORT).show()
    }

    private fun onStop() {
        prefs.edit().putBoolean(BookingCheckWorker.KEY_ACTIVE, false).apply()
        BookingCheckWorker.stopMonitoring(this)
        Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show()
    }

    private fun onTest() {
        val (url, interval) = readInputs() ?: return
        prefs.edit()
            .putString(BookingCheckWorker.KEY_URL, url)
            .putInt(BookingCheckWorker.KEY_INTERVAL, interval)
            .putString(BookingCheckWorker.KEY_LOG_RESULT, "Test running... (takes about 10-70 seconds)")
            .apply()
        ensureNotificationPermission()
        BookingCheckWorker.runTestNow(this)
    }

    private fun renderStatus() {
        val active = prefs.getBoolean(BookingCheckWorker.KEY_ACTIVE, false)
        val interval = prefs.getInt(BookingCheckWorker.KEY_INTERVAL, 15)
        val url = prefs.getString(BookingCheckWorker.KEY_URL, "").orEmpty()
        val date = BookingCheckWorker.extractDate(url) ?: "-"

        val text = buildString {
            append("Monitoring: ")
            append(if (active) "ACTIVE (every $interval min)" else "STOPPED")
            append("\nTarget date: ").append(date)
            append("\nLast checked: ").append(prefs.getString(BookingCheckWorker.KEY_LOG_TIME, "-"))
            append("\nResolved URL: ").append(prefs.getString(BookingCheckWorker.KEY_LOG_URL, "-").orEmpty().ifBlank { "-" })
            append("\nPage title: ").append(prefs.getString(BookingCheckWorker.KEY_LOG_TITLE, "-").orEmpty().ifBlank { "-" })
            append("\nResult: ").append(prefs.getString(BookingCheckWorker.KEY_LOG_RESULT, "-"))
        }
        tvStatus.text = text
    }
}
