# Acoustic Keylogger Android Application

This directory contains a Kotlin/Android implementation of the acoustic keylogger's microphone-based signal collection and keystroke extraction pipeline. It is modeled after the Python processing logic inside `acoustic_keylogger/audio_processing.py`.

---

## Architectural Comparison

| Feature | Python Implementation (`acoustic_keylogger/`) | Android Kotlin Implementation (`apk/`) |
| :--- | :--- | :--- |
| **Audio Capture** | Loaded from offline WAV files (`wav_read`) | Real-time 44.1kHz 16-bit PCM streaming (`AudioRecord`) |
| **Calibration** | Calculated on the first $N$ seconds of a static clip | Computed on startup during a 2.5-second ambient capture |
| **Spike Detection** | Absolute sample values compared to silence threshold | Continuous streaming checks matching calculated threshold |
| **Window Slicing** | 0.3s window from start index, padded to 13,230 samples | Buffered extraction of 13,230 samples (300ms window) |
| **Waveform Backtracking** | Backtracks tail if amplitude exceeds threshold | Tail backtracking on sliding buffer to prevent overlap clipping |
| **Data Export** | Saved to PostgreSQL database array | Saved as individual little-endian `.pcm` files for training |

---

## Codebase Map

- **[KeystrokeDetector.kt](file:///home/aaron/Documents/acoustic-keylogger/apk/app/src/main/kotlin/com/acoustic/keylogger/KeystrokeDetector.kt)**: Translates the stream processing, threshold calibration, and backtracking extraction algorithms into Kotlin.
- **[AcousticKeyloggerService.kt](file:///home/aaron/Documents/acoustic-keylogger/apk/app/src/main/kotlin/com/acoustic/keylogger/AcousticKeyloggerService.kt)**: Implements the Android Foreground Service that captures microphone audio on a background thread and manages notifications.
- **[MainActivity.kt](file:///home/aaron/Documents/acoustic-keylogger/apk/app/src/main/kotlin/com/acoustic/keylogger/MainActivity.kt)**: Manages UI, permissions (microphone and background notification), and displays real-time key logger activity logs.
- **[activity_main.xml](file:///home/aaron/Documents/acoustic-keylogger/apk/app/src/main/res/layout/activity_main.xml)**: XML layouts styling the application state control panel.

---

## Building and Installing

### Prerequisites
1. Android SDK installed.
2. Gradle or Android Studio installed.

### Compiling via CLI
From this `apk/` directory:

1. Build the debug application:
   ```bash
   gradle assembleDebug
   ```
2. Install the APK on a connected device/emulator:
   ```bash
   gradle installDebug
   ```

### Application Output
Detected keystroke files are stored in the application's external files directory:
`/Android/data/com.acoustic.keylogger/files/keystroke_*.pcm`

These raw 16-bit PCM files can be transferred to your workstation and loaded directly using Python's NumPy/SciPy libraries or tools like Audacity for training model pipelines.
