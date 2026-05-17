# Hand Mouse Camera Preview

Beginner-friendly Android Kotlin project that runs from VS Code, terminal builds, and GitHub Actions.

This version includes:

- Jetpack Compose UI
- Start Tracking button
- Stop Tracking button
- Camera permission status
- Overlay permission status
- Accessibility service status
- Tracking Enabled / Disabled text
- CameraX preview shown on screen when tracking is enabled

Real hand tracking is not implemented yet. The code marks where to add CameraX `ImageAnalysis`, MediaPipe, overlay cursor handling, and accessibility tap handling later.

## Build In GitHub Actions

Push to the `main` branch. The workflow at `.github/workflows/build.yml` builds:

```bash
gradle :app:assembleDebug
```

The generated APK is uploaded as a GitHub Actions artifact named:

```text
hand-mouse-debug-apk
```

## Local Terminal Build

If Gradle is installed locally:

```bash
gradle :app:assembleDebug
```

The APK will be created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```
