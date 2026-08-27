# COMREL SCANNER

Lightweight offline Android document scanner.

## Version 1 features

- CameraX document capture
- Single-page scanning
- Multi-page batch scanning
- Review thumbnails
- Delete individual pages
- Add more pages
- Combine pages into one PDF
- Save automatically to:
  `Downloads/COMREL SCANNER`
- Offline operation
- Small creator credit:
  `Created by Community Relations Assistant: Jon Rose`

## Build requirements

This project uses:

- Android Gradle Plugin 9.3.0
- Gradle 9.5
- Kotlin 2.3.21
- Java 17
- compileSdk 36
- minSdk 29
- CameraX 1.6.1

Open the project folder in a current Android Studio release and allow Gradle Sync.

## Build APK

Android Studio:

Build > Generate App Bundle(s) / APK(s) > Generate APK(s)

The debug APK will normally be under:

app/build/outputs/apk/debug/app-debug.apk

## Important

This first version deliberately keeps the app lightweight.

It does NOT yet include automatic four-corner document detection/cropping like CamScanner.

That can be added as a later version without changing the basic workflow.
