package com.acoustic.keylogger

import kotlin.math.abs

/**
 * KeystrokeDetector translates the Python-based keystroke detection logic
 * to work in real-time on continuous PCM 16-bit audio streams.
 */
class KeystrokeDetector(
    private val sampleRate: Int = 44100,
    private val windowDuration: Double = 0.3,
    private var threshold: Int = 500,
    private val onKeystrokeDetected: (ShortArray, Int) -> Unit
) {
    private val lenSample = (sampleRate * windowDuration).toInt() // 13230 samples (300ms)
    private val streamBuffer = ArrayList<Short>()

    fun setThreshold(newThreshold: Int) {
        threshold = newThreshold
    }

    fun getThreshold(): Int = threshold

    /**
     * Feed raw audio data from AudioRecord.
     */
    fun feed(data: ShortArray, size: Int) {
        for (i in 0 until size) {
            streamBuffer.add(data[i])
        }
        processBuffer()
    }

    /**
     * Process accumulated buffers to detect keystrokes.
     * Matches the Python logic of identifying exceeding threshold, windowing,
     * backtracking from the tail to avoid clipping next keystrokes, and zero-padding.
     */
    private fun processBuffer() {
        var i = 0
        while (i < streamBuffer.size) {
            val value = abs(streamBuffer[i].toInt())
            if (value > threshold) {
                // Keystroke start found at index i
                // Check if we have enough samples to process a full window (13230 samples)
                if (i + lenSample > streamBuffer.size) {
                    // We don't have enough data yet. Trim the buffer up to this start index
                    // so we don't re-scan preceding silent/noise samples, and wait.
                    trimBuffer(i)
                    return
                }

                val a = i
                var b = i + lenSample
                if (b > streamBuffer.size) {
                    b = streamBuffer.size
                }

                // Backtrack 'b' while the end of the slice exceeds the threshold.
                // This matches the Python logic to avoid bleeding into the next keystroke's waveform.
                while (b > a && abs(streamBuffer[b - 1].toInt()) > threshold) {
                    b--
                }

                // Slice keystroke sound segment and pad the tail with zeros to maintain 300ms duration.
                val keystroke = ShortArray(lenSample)
                val sliceLength = b - a
                for (k in 0 until sliceLength) {
                    keystroke[k] = streamBuffer[a + k]
                }

                // Find peak amplitude for metadata/logging
                var maxPeak = 0
                for (k in 0 until sliceLength) {
                    val absVal = abs(keystroke[k].toInt())
                    if (absVal > maxPeak) {
                        maxPeak = absVal
                    }
                }

                // Notify callback of detected keystroke
                onKeystrokeDetected(keystroke, maxPeak)

                // Discard processed audio up to index b and restart loop search from new index 0
                trimBuffer(b)
                i = 0
                continue
            }
            i++
        }

        // If we processed the entire buffer and no sample exceeded the threshold, clear the buffer.
        streamBuffer.clear()
    }

    private fun trimBuffer(startIndex: Int) {
        if (startIndex <= 0) return
        if (startIndex >= streamBuffer.size) {
            streamBuffer.clear()
            return
        }
        streamBuffer.subList(0, startIndex).clear()
    }
}

/**
 * Calibrator captures standard ambient noise profile for a specific period
 * to compute a dynamic threshold adaptively, mimicking silence_threshold in Python.
 */
class Calibrator(
    private val sampleRate: Int = 44100,
    private val calibrationDurationSec: Double = 2.0,
    private val factor: Double = 5.0,
    private val onCalibrated: (Int) -> Unit
) {
    private val targetSamples = (sampleRate * calibrationDurationSec).toInt()
    private var collectedSamples = 0
    private var maxVal = 0

    /**
     * Feed data during calibration phase. Returns true if calibration is completed.
     */
    fun feed(data: ShortArray, size: Int): Boolean {
        if (collectedSamples >= targetSamples) return true

        for (i in 0 until size) {
            val absVal = abs(data[i].toInt())
            if (absVal > maxVal) {
                maxVal = absVal
            }
            collectedSamples++
            if (collectedSamples >= targetSamples) {
                // Dynamic threshold = max absolute amplitude * factor.
                // Enforce reasonable bounds to prevent over/under sensitivity.
                val computedThreshold = (maxVal * factor).toInt().coerceIn(300, 25000)
                onCalibrated(computedThreshold)
                return true
            }
        }
        return false
    }

    fun isCalibrated(): Boolean = collectedSamples >= targetSamples
}
