package com.acoustic.keylogger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var calibrationStatus: TextView
    private lateinit var keystrokeCountText: TextView
    private lateinit var logTextView: TextView
    private lateinit var statusIndicator: android.view.View
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton

    private var currentStatus = AcousticKeyloggerService.STATE_STOPPED

    // Permission launcher for Android Runtime Permissions
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val postNotificationsGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
        
        if (recordAudioGranted && postNotificationsGranted) {
            startKeyloggerService()
        } else {
            Toast.makeText(
                this,
                "Microphone and Notification permissions are required to run the service.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AcousticKeyloggerService.ACTION_STATUS_UPDATE -> {
                    val status = intent.getStringExtra(AcousticKeyloggerService.EXTRA_STATUS) ?: AcousticKeyloggerService.STATE_STOPPED
                    val threshold = intent.getIntExtra(AcousticKeyloggerService.EXTRA_THRESHOLD, 0)
                    updateStatusUI(status, threshold)
                }
                AcousticKeyloggerService.ACTION_KEYSTROKE_DETECTED -> {
                    val peak = intent.getIntExtra(AcousticKeyloggerService.EXTRA_PEAK, 0)
                    val count = intent.getIntExtra(AcousticKeyloggerService.EXTRA_COUNT, 0)
                    val timestamp = intent.getLongExtra(AcousticKeyloggerService.EXTRA_TIMESTAMP, 0)
                    val filePath = intent.getStringExtra(AcousticKeyloggerService.EXTRA_FILE_PATH) ?: ""
                    
                    logKeystroke(count, peak, timestamp, filePath)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI Elements
        statusText = findViewById(R.id.statusText)
        calibrationStatus = findViewById(R.id.calibrationStatus)
        keystrokeCountText = findViewById(R.id.keystrokeCountText)
        logTextView = findViewById(R.id.logTextView)
        statusIndicator = findViewById(R.id.statusIndicator)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        // Configure Buttons
        btnStart.setOnClickListener {
            checkPermissionsAndStart()
        }

        btnStop.setOnClickListener {
            stopKeyloggerService()
        }

        // Set initial UI state
        updateStatusUI(AcousticKeyloggerService.STATE_STOPPED, 0)
    }

    override fun onStart() {
        super.onStart()
        
        // Register Service Broadcast Receiver
        val filter = IntentFilter().apply {
            addAction(AcousticKeyloggerService.ACTION_STATUS_UPDATE)
            addAction(AcousticKeyloggerService.ACTION_KEYSTROKE_DETECTED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
    }

    override fun onStop() {
        unregisterReceiver(serviceReceiver)
        super.onStop()
    }

    private fun checkPermissionsAndStart() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isEmpty()) {
            startKeyloggerService()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startKeyloggerService() {
        val serviceIntent = Intent(this, AcousticKeyloggerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopKeyloggerService() {
        val serviceIntent = Intent(this, AcousticKeyloggerService::class.java)
        stopService(serviceIntent)
    }

    private fun updateStatusUI(status: String, threshold: Int) {
        currentStatus = status
        val drawable = statusIndicator.background as? GradientDrawable

        when (status) {
            AcousticKeyloggerService.STATE_CALIBRATING -> {
                statusText.text = "Status: Calibrating"
                calibrationStatus.text = "Measuring background noise floor..."
                drawable?.setColor(Color.parseColor("#FFD600")) // Yellow
                btnStart.isEnabled = false
                btnStop.isEnabled = true
            }
            AcousticKeyloggerService.STATE_LISTENING -> {
                statusText.text = "Status: Listening"
                calibrationStatus.text = "Threshold: $threshold (Calibrated)"
                drawable?.setColor(Color.parseColor("#00E676")) // Green
                btnStart.isEnabled = false
                btnStop.isEnabled = true
            }
            AcousticKeyloggerService.STATE_STOPPED -> {
                statusText.text = "Status: Stopped"
                calibrationStatus.text = "Threshold: Not Calibrated"
                drawable?.setColor(Color.parseColor("#FF1744")) // Red
                btnStart.isEnabled = true
                btnStop.isEnabled = false
            }
        }
    }

    private fun logKeystroke(count: Int, peak: Int, timestamp: Long, filePath: String) {
        keystrokeCountText.text = "Keystrokes Detected: $count"

        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val formattedTime = sdf.format(Date(timestamp))
        val file = File(filePath)

        val logEntry = "[%s] Keystroke #%d detected | Peak: %d | Saved: %s\n"
            .format(formattedTime, count, peak, file.name)

        logTextView.append(logEntry)
        
        // Auto scroll to bottom
        val scroll = logTextView.parent as? android.widget.ScrollView
        scroll?.post {
            scroll.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
