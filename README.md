# Hand Mouse

Android Kotlin app that uses the front camera as a hand-controlled mouse:

- CameraX captures frames in a foreground service.
- MediaPipe Hand Landmarker detects hand landmarks.
- Landmark 8, the index finger tip, drives a global cursor overlay.
- Landmark 4 to landmark 8 distance detects a pinch.
- Pinch dispatches a system tap through an Android Accessibility Service.

## Setup

1. Open this folder in Android Studio.
2. Download the MediaPipe model:

   ```bash
   ./gradlew :app:downloadHandModel
   ```

   On Windows without a Gradle wrapper, run the same task from Android Studio's Gradle tool window, or use an installed `gradle` command:

   ```powershell
   gradle :app:downloadHandModel
   ```

3. Build and install the app.
4. Open Hand Mouse and grant:
   - Camera permission
   - Display over other apps permission
   - Accessibility permission for "Hand Mouse Tap Service"
5. Tap "Start hand mouse", then switch to another app.

## Notes

The app uses a foreground service with `foregroundServiceType="camera"` so camera analysis can continue after leaving the setup screen. Android may still show a camera/privacy indicator while tracking is active.
