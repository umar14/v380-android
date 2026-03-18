# V380-android

A native Android app to stream live video from **V380 / Macrovideo** IP cameras directly on your phone — no cloud, no third-party app, no internet required.

Built with plain Android Java + `MediaCodec` hardware decoding. No Gradle, no Android Studio required.

---

## Credits

The camera binary protocol (authentication, stream login, frame parsing) was reverse-engineered from the C++ project:

> **[v380](https://github.com/mzyy94/v380)** by [@mzyy94](https://github.com/mzyy94)  
> Licensed under the terms of that repository.

This Android app is a Java port of that work, adapted for mobile with the following additions:
- Double AES-128 ECB password encryption (`CameraAuth.java`)
- Binary protocol fixes (correct struct field offsets, LE frame header parsing, stream login response handling)
- H.264 Annex-B NAL parsing with both 3-byte and 4-byte start code support
- Android `MediaCodec` hardware decoder pipeline

---

## Features

- Live H.264 video stream from V380/Macrovideo cameras over LAN
- Input fields for IP, port, device ID, username, password
- Auto-connects after 3 seconds (touch any field to cancel)
- Hardware-accelerated H.264 decoding via `MediaCodec`
- Tap the stream to toggle connection status overlay
- No Gradle, no Android Studio — builds with a single script

---

## Requirements

### Runtime
- Android 8.0+ (API 26+)
- Same Wi-Fi network as the camera

### Build
- **JDK 17**
- **Android SDK command-line tools** with:
  - `build-tools/35.0.0`
  - `platforms/android-35`
  - `platform-tools` (for ADB install)

---

## Build Instructions

### macOS / Linux

1. Install JDK 17:
   ```bash
   # macOS
   brew install openjdk@17

   # Ubuntu/Debian
   sudo apt install openjdk-17-jdk
   ```

2. Install Android SDK command-line tools:
   ```bash
   # Download from: https://developer.android.com/studio#command-tools
   # Extract to /usr/local/share/android-commandlinetools (or edit SDK_ROOT in build.sh)

   sdkmanager "build-tools;35.0.0" "platforms;android-35" "platform-tools"
   ```

3. Edit `SDK_ROOT` at the top of `build.sh` if your SDK is installed elsewhere:
   ```bash
   export SDK_ROOT=/usr/local/share/android-commandlinetools
   ```

4. Build and install:
   ```bash
   chmod +x build.sh
   ./build.sh
   ```

   The signed APK will be at `build/apk/V380CamPhone-signed.apk`.  
   If a device is connected via ADB, it will be installed and launched automatically.

---

### Windows

1. Install [JDK 17](https://adoptium.net/)

2. Install Android SDK command-line tools:
   - Download from: https://developer.android.com/studio#command-tools
   - Extract to `C:\Android\cmdline-tools` (or edit `SDK_ROOT` in `build.bat`)
   - Run:
     ```
     sdkmanager "build-tools;35.0.0" "platforms;android-35" "platform-tools"
     ```

3. Edit `SDK_ROOT` at the top of `build.bat`:
   ```bat
   set SDK_ROOT=C:\Android\cmdline-tools
   ```

4. Build and install:
   ```
   build.bat
   ```

   The signed APK will be at `build\apk\V380CamPhone-signed.apk`.  
   If a device is connected via ADB, it will be installed and launched automatically.

---

## Manual ADB Install (without running the build script)

```bash
adb install build/apk/V380CamPhone-signed.apk
```

---

## Usage

1. Open the app
2. Enter your camera's **IP address**, **Device ID**, **username**, and **password**
3. Tap **Connect** (or wait 3 seconds for auto-connect)
4. The stream opens in fullscreen landscape
5. Tap the screen to show/hide the connection status
6. Press Back to return to the connection screen

---

## Finding Your Camera's Device ID

Run the v380 C++ tool with `--discover` on the same network:

```bash
./v380 --discover
```

Or check the V380 app on your phone — the device ID is shown in camera settings.

---

## Project Structure

```
V380CamPhone/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/example/v380cam/
│   │   ├── MainActivity.java       # Connection form + auto-connect
│   │   ├── StreamActivity.java     # Fullscreen stream view
│   │   ├── CameraProtocol.java     # V380 binary protocol (TCP)
│   │   ├── CameraAuth.java         # Double AES-128 ECB password encryption
│   │   ├── H264Decoder.java        # MediaCodec H.264 decoder
│   │   ├── CameraDevice.java       # Device model
│   │   └── CameraDiscovery.java    # UDP broadcast discovery
│   └── res/
│       ├── layout/
│       │   ├── activity_main.xml   # Connection form layout
│       │   └── activity_stream.xml # Stream + status overlay
│       └── values/styles.xml
├── build.sh                        # Build script (macOS/Linux)
├── build.bat                       # Build script (Windows)
└── README.md
```

---

## License

Protocol implementation derived from [mzyy94/v380](https://github.com/mzyy94/v380).  
Android app code is provided as-is for personal/educational use.
