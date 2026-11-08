# SailLog

Simple Android GPS logger for sailing. Kotlin, no third-party libraries, no Play Services, no map tiles (works fully offline).

Build: open this folder in Android Studio (Koala or newer), let Gradle sync, Run on a device (minSdk 29 / Android 10+).

- Start/Stop manually, or "Schedule start…" for a future date/time (AlarmManager exact alarm -> foreground service).
- Logs time, lat/lon, speed (knots) every 2 s from the GPS provider.
- Auto-stops when speed > 15 kn for 3 consecutive fixes (AUTO_STOP_KN in TrackingService.kt).
- On every stop a GPX 1.1 file is written to Documents/SailLog/ (speed in Garmin TrackPointExtension, m/s). "Share last GPX" button.
- Foreground service + partial wake lock + battery-optimisation exemption request keep it alive with the screen off.
- A CSV backup of the current track is appended continuously, so a killed process resumes where it left off.

## Getting an APK without installing Android Studio
Push this folder to a GitHub repository. The workflow in .github/workflows/build.yml builds a debug APK
(Actions tab -> latest run -> Artifacts -> SailLog-debug-apk). Unzip it, copy app-debug.apk to the phone and install it
(allow "install unknown apps" when prompted).
