import os
import glob
import numpy as np
import tensorflow as tf
from sklearn.model_selection import train_test_split

# Constants matching Android project configurations
SAMPLE_RATE = 44100
WINDOW_DURATION = 0.3
NUM_SAMPLES = int(SAMPLE_RATE * WINDOW_DURATION)  # 13230 samples

# List of target labels
LABELS = [
    "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", 
    "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "space"
]
LABEL_MAP = {label: i for i, label in enumerate(LABELS)}

def load_pcm_file(filepath):
    """Load a raw little-endian 16-bit PCM file and normalize it to [-1.0, 1.0] float32."""
    with open(filepath, 'rb') as f:
        audio_data = np.frombuffer(f.read(), dtype=np.int16)
    
    # Standardize length to exactly 13,230 samples
    if len(audio_data) < NUM_SAMPLES:
        audio_data = np.pad(audio_data, (0, NUM_SAMPLES - len(audio_data)), 'constant')
    else:
        audio_data = audio_data[:NUM_SAMPLES]
        
    # Convert to float32 normalized
    return audio_data.astype(np.float32) / 32768.0

def main(data_dir="datasets/training", output_model="lab/keylogger_model.tflite"):
    print("--------------------------------------------------")
    print("  Acoustic Keylogger - End-to-End 1D CNN Trainer  ")
    print("--------------------------------------------------")
    
    if not os.path.exists(data_dir):
        print(f"[!] Error: Training directory '{data_dir}' not found.")
        print(f"    Please copy the collected files from your device to '{data_dir}'.")
        print("    File pattern should be: train_<key>_<timestamp>.pcm")
        return

    # Find all training PCM files
    search_path = os.path.join(data_dir, "*.pcm")
    files = glob.glob(search_path)
    
    if not files:
        print(f"[!] No training files found in '{data_dir}'.")
        return
        
    print(f"[*] Found {len(files)} training audio files.")
    
    X = []
    y = []
    
    print("[*] Processing raw audio signals...")
    for filepath in files:
        filename = os.path.basename(filepath)
        # Parse label from file pattern: train_<key>_<timestamp>.pcm
        parts = filename.split('_')
        if len(parts) < 2:
            continue
        key_label = parts[1]
        
        if key_label not in LABEL_MAP:
            print(f"[!] Warning: Unknown key label '{key_label}' in filename '{filename}'. Skipping.")
            continue
            
        try:
            audio = load_pcm_file(filepath)
            # Expand dimensions to (13230, 1) for Conv1D layer input channel
            X.append(np.expand_dims(audio, axis=-1))
            y.append(LABEL_MAP[key_label])
        except Exception as e:
            print(f"[!] Failed to process '{filename}': {str(e)}")
            
    X = np.array(X)
    y = np.array(y)
    
    if len(X) == 0:
        print("[!] Error: No features extracted. Aborting.")
        return
        
    print(f"[*] Dataset shapes: X={X.shape}, y={y.shape}")
    
    # Split into train and test sets
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.15, random_state=42, stratify=y)
    
    # Build 1D Convolutional Neural Network (CNN) for raw waveform processing
    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(NUM_SAMPLES, 1)),
        # Conv1D layer to act as learnable filterbank on raw waveform
        tf.keras.layers.Conv1D(16, kernel_size=64, strides=4, activation='relu'),
        tf.keras.layers.MaxPooling1D(pool_size=4),
        tf.keras.layers.Conv1D(32, kernel_size=32, strides=2, activation='relu'),
        tf.keras.layers.MaxPooling1D(pool_size=4),
        tf.keras.layers.Flatten(),
        tf.keras.layers.Dense(64, activation='relu'),
        tf.keras.layers.Dropout(0.3),
        tf.keras.layers.Dense(len(LABELS), activation='softmax')
    ])
    
    model.compile(
        optimizer='adam',
        loss='sparse_categorical_crossentropy',
        metrics=['accuracy']
    )
    
    print("\n[*] Training 1D Convolutional Neural Network...")
    model.fit(
        X_train, y_train,
        validation_data=(X_test, y_test),
        epochs=50,
        batch_size=16
    )
    
    # Evaluate final accuracy
    loss, accuracy = model.evaluate(X_test, y_test, verbose=0)
    print(f"\n[+] Training completed. Validation Accuracy: {accuracy * 100:.2f}%")
    
    # Convert model to TensorFlow Lite format
    print("[*] Converting model to TensorFlow Lite...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()
    
    # Ensure lab/ directory exists
    os.makedirs(os.path.dirname(output_model), exist_ok=True)
    
    # Save the TFLite model
    with open(output_model, 'wb') as f:
        f.write(tflite_model)
        
    print(f"[+] Successfully exported TFLite model to: {output_model}")
    print("--------------------------------------------------")
    print("Next steps to run on Android:")
    print("1. Copy 'keylogger_model.tflite' to your Android app assets directory:")
    print("   apk/app/src/main/assets/keylogger_model.tflite")
    print("2. Update the Android app to run TFLite interpreter.")
    print("--------------------------------------------------")

if __name__ == "__main__":
    main()
