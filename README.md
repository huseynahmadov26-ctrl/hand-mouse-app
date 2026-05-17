# Hand Mouse Controller

Android Kotlin app that controls the phone with hand gestures.

- Index finger tip moves a cursor overlay.
- Thumb + index pinch performs a tap.
- CameraX captures frames.
- MediaPipe Hand Landmarker detects hand landmarks.
- Android Accessibility Service performs taps with `dispatchGesture`.
- WindowManager shows the cursor above other apps.

## Build APK From Terminal

Java 17 is required.

```bash
./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Build APK With GitHub Actions

Push to the `main` branch. The workflow in `.github/workflows/build.yml` runs:

```bash
./gradlew assembleDebug
```

Download the APK from the GitHub Actions artifact:

```text
hand-mouse-debug-apk
```

## Install APK With ADB

Connect your phone with USB debugging enabled, then run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Phone Setup

1. Open the app.
2. Tap `Open Overlay Permission Settings`.
3. Allow `Display over other apps` for Hand Mouse.
4. Return to the app.
5. Tap `Open Accessibility Settings`.
6. Enable `Hand Mouse Accessibility Service`.
7. Return to the app.
8. Tap `Start Tracking`.

Android will show camera/foreground-service indicators while tracking is running.

## Code Map

- `MainActivity.kt`: Jetpack Compose UI and camera preview surface.
- `HandMouseService.kt`: CameraX frame analysis, MediaPipe tracking, cursor movement, pinch detection.
- `HandTracker.kt`: MediaPipe Hand Landmarker setup.
- `CursorOverlay.kt`: global WindowManager cursor.
- `MyAccessibilityService.kt`: tap simulation with `dispatchGesture`.
