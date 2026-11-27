# YVIBE (Android WebView app, light glass UI)

## Build with GitHub Actions
1. Push this whole folder to a GitHub repo (branch `main`).
2. Open the repo > **Actions** > **Build APK** (it runs on every push, or press *Run workflow*).
3. When it turns green, open the run > **Artifacts** > download **YVIBE-debug-apk** > unzip > install the `.apk`.

## Before building
- `app/src/main/java/com/yvibe/app/MainActivity.kt`: paste your ad script into `USER_SCRIPT`.
- Package name is `com.yvibe.app` (change in `app/build.gradle.kts` and `AndroidManifest`/`MainActivity` if needed).
