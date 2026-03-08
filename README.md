# Dual Camera Evidence DVR — Android App

Professional Android vehicle evidence recorder.  
Blueprint v2.0 · Kotlin · Camera2 API · CameraX · Coroutines

---

## Module Build Status

| # | Module | Status |
|---|---|---|
| 1 | Project Foundation (Gradle, Manifest, CI, Service skeleton) | ✅ Complete |
| 2 | Camera Pipeline (Camera2 dual-stream, physical ID binding) | 🔄 In progress |
| 3 | Telemetry Engine (GPS + IMU + NTP + Barometer) | ⏳ Queued |
| 4 | Session Manager (folder structure, signing) | ⏳ Queued |
| 5 | Collision Detector (3g spike, jerk, direction) | ⏳ Queued |
| 6 | Evidence Packager (SHA-256, Keystore, RFC 3161) | ⏳ Queued |
| 7 | Loop Recorder (3-min segments, event cap) | ⏳ Queued |
| 8 | UI (Main screen, Setup Wizard, Event Review) | ⏳ Queued |

---

## Requirements

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34
- Target device: Android 8.0+ (API 26+)

---

## Build Instructions

### Android Studio
```
File → Open → select this folder
Build → Make Project
Build → Build APK(s)
```

### Command line
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions (CI)
Push to `main` → workflow auto-builds signed Release APK.  
Configure secrets in repo Settings → Secrets:
- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

---

## Project Structure

```
app/src/main/
├── kotlin/com/dashcam/dvr/
│   ├── DVRApplication.kt         Application class, notification channels
│   ├── service/
│   │   ├── RecordingService.kt   Foreground Service (process survival)
│   │   └── BootReceiver.kt       Auto-start on boot
│   ├── camera/                   Module 2 — Camera Pipeline
│   ├── telemetry/                Module 3 — Telemetry Engine
│   ├── session/                  Module 4 — Session Manager
│   ├── collision/                Module 5 — Collision Detector
│   ├── evidence/                 Module 6 — Evidence Packager
│   ├── loop/                     Module 7 — Loop Recorder
│   ├── ui/                       Module 8 — UI Activities
│   └── util/
│       └── AppConstants.kt       All configuration constants
├── res/
└── AndroidManifest.xml
```

---

## Blueprint Compliance Notes

- **§14 Process Survival**: `PARTIAL_WAKE_LOCK` + `Foreground Service` with `foregroundServiceType=camera|microphone|location`
- **§14 Boot**: `BootReceiver` + `RECEIVE_BOOT_COMPLETED`
- **§3 Camera**: Camera2 physical ID binding in `CameraManager` (Module 2)
- **§6 Telemetry**: GPS cold-start flagging + NTP sync in `TelemetryEngine` (Module 3)
- **§12 Evidence**: SHA-256 + Android Keystore + RFC 3161 in `EvidencePackager` (Module 6)
