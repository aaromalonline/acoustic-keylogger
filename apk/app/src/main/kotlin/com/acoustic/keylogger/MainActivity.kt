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
    private val logLines = ArrayList<String>()
    private val typedTextAccumulator = StringBuilder()

    // Training Mode views & state
    private lateinit var cbTrainingMode: com.google.android.material.checkbox.MaterialCheckBox
    private lateinit var trainingControls: android.widget.LinearLayout
    private lateinit var tvTrainingPrompt: TextView
    private lateinit var tvTrainingProgress: TextView
    private lateinit var trainingProgressBar: com.google.android.material.progressindicator.LinearProgressIndicator

    private var isTrainingActive = false
    private var currentTrainingKeyIndex = 0
    private var currentTrainingKeySamplesCount = 0
    private val trainingKeys = listOf(
        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", 
        "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "space"
    )

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
                    val char = intent.getStringExtra(AcousticKeyloggerService.EXTRA_CHAR) ?: ""
                    val isTraining = intent.getBooleanExtra(AcousticKeyloggerService.EXTRA_IS_TRAINING, false)
                    
                    logKeystroke(count, peak, timestamp, char)

                    if (isTraining) {
                        handleTrainingKeystroke(char)
                    }
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

        // Initialize Training UI Elements
        cbTrainingMode = findViewById(R.id.cbTrainingMode)
        trainingControls = findViewById(R.id.trainingControls)
        tvTrainingPrompt = findViewById(R.id.tvTrainingPrompt)
        tvTrainingProgress = findViewById(R.id.tvTrainingProgress)
        trainingProgressBar = findViewById(R.id.trainingProgressBar)

        cbTrainingMode.setOnCheckedChangeListener { _, isChecked ->
            isTrainingActive = isChecked
            trainingControls.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            if (isChecked) {
                resetTrainingSession()
            }
        }

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
        val serviceIntent = Intent(this, AcousticKeyloggerService::class.java).apply {
            putExtra(AcousticKeyloggerService.EXTRA_IS_TRAINING, isTrainingActive)
            if (isTrainingActive && currentTrainingKeyIndex < trainingKeys.size) {
                putExtra(AcousticKeyloggerService.EXTRA_TRAINING_KEY, trainingKeys[currentTrainingKeyIndex])
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun resetTrainingSession() {
        currentTrainingKeyIndex = 0
        currentTrainingKeySamplesCount = 0
        updateTrainingUI()
    }

    private fun updateTrainingUI() {
        if (currentTrainingKeyIndex < trainingKeys.size) {
            val targetKey = trainingKeys[currentTrainingKeyIndex]
            tvTrainingPrompt.text = "Target: Press '$targetKey' 10 times"
            tvTrainingProgress.text = "Progress for '$targetKey': $currentTrainingKeySamplesCount / 10"
            trainingProgressBar.progress = currentTrainingKeySamplesCount
        } else {
            tvTrainingPrompt.text = "Training Complete! 🎉"
            tvTrainingProgress.text = "All 270 samples collected and stored."
            trainingProgressBar.progress = 10
            cbTrainingMode.isChecked = false
            isTrainingActive = false
            stopKeyloggerService()
            Toast.makeText(this, "Acoustic dataset collection complete!", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleTrainingKeystroke(char: String) {
        if (currentTrainingKeyIndex >= trainingKeys.size) return
        val target = trainingKeys[currentTrainingKeyIndex]
        if (char == target) {
            currentTrainingKeySamplesCount++
            if (currentTrainingKeySamplesCount >= 10) {
                currentTrainingKeySamplesCount = 0
                currentTrainingKeyIndex++
                updateTrainingServiceKey()
            }
            updateTrainingUI()
        }
    }

    private fun updateTrainingServiceKey() {
        if (currentTrainingKeyIndex < trainingKeys.size) {
            val serviceIntent = Intent(this, AcousticKeyloggerService::class.java).apply {
                putExtra(AcousticKeyloggerService.EXTRA_IS_TRAINING, true)
                putExtra(AcousticKeyloggerService.EXTRA_TRAINING_KEY, trainingKeys[currentTrainingKeyIndex])
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
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

    private fun logKeystroke(count: Int, peak: Int, timestamp: Long, char: String) {
        keystrokeCountText.text = "Keystrokes Detected: $count"

        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val formattedTime = sdf.format(Date(timestamp))

        val displayChar = if (char == "[SPACE]") " " else char
        typedTextAccumulator.append(displayChar)

        val newLine = "[%s] Keystroke #%d | Peak: %d | Predicted: \'%s\'"
            .format(formattedTime, count, peak, char)
        logLines.add(newLine)

        // Limit log size to prevent layout performance lag
        if (logLines.size > 100) {
            logLines.removeAt(0)
        }

        val sb = StringBuilder().apply {
            append("READABLE TYPED INPUT:\n")
            append("👉 \"").append(typedTextAccumulator.toString()).append("\"\n")
            append("==================================================\n\n")
            for (line in logLines.reversed()) {
                append(line).append("\n")
            }
        }

        logTextView.text = sb.toString()
        
        // Auto scroll to top to keep the readable typed input in view
        val scroll = logTextView.parent as? android.widget.ScrollView
        scroll?.post {
            scroll.fullScroll(android.view.View.FOCUS_UP)
        }
    }
}
