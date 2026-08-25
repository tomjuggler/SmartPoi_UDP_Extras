---
name: android-app-builds
description: Use when building Android apps with Gradle on this box.
tags: [android, gradle, build, agp]
---

# Building Android apps with Gradle (no Android Studio)

Context: SDK at /mnt/Storage/Documents/PROGRAMMING/Android/android-sdk-linux, JDK 17, no Android Studio. The `android` CLI (see android-cli skill) handles SDK/devices/docs; this skill covers the Gradle project side.

## Workflow
1. Check what exists before downloading: platforms in `$SDK/platforms`, Gradle dists in `~/.gradle/wrapper/dists/`, cached deps in `~/.gradle/caches/modules-2/`. Network to dl.google.com / maven-central works — Maven dependency resolution is fine, but avoid installing new SDK packages when a suitable platform is present.
2. Pick versions by querying maven-metadata.xml directly (the `android studio version-lookup` command FAILS without a running Studio instance):
   `curl -s https://dl.google.com/android/maven2/<group/path>/<artifact>/maven-metadata.xml | tr '<' '\n' | grep '^version>'`
3. Scaffold Kotlin + Compose project: settings.gradle.kts, build.gradle.kts (root + app), gradle.properties (`android.useAndroidX=true`), local.properties (`sdk.dir=...`), manifest, res. Copy `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` from a known-good existing project (e.g. ProcessingAndroidDemo) — the system `gradle` binary may be broken (`Cannot find module 'gradle-public-api-legacy'`), so never rely on it to run `gradle wrapper`.
4. Build in background: `./gradlew assembleDebug` (foreground cap is 600s; first build takes ~5 min).
5. Verify: `aapt dump badging` the APK, `adb install -r`, `adb shell am start`, screenshot via `adb exec-out screencap -p` and view with vision. Wake the device first (`KEYCODE_WAKEUP` + keyevent 82) or the screenshot is black.

## Version compatibility pitfall (critical)
The newest Compose BOM requires newer AGP + compileSdk than what's installed. Rule of thumb observed 2026-08:
- AGP 8.13.x supports compileSdk up to 36 (Android 16). Compose BOM 2026.x ships ui 1.12 which demands AGP 9.1+ and compileSdk 37 → build fails with a long "requires libraries and applications to compile against version 37" list.
- Known-good combo: AGP 8.13.2 + Kotlin 2.1.20 + `org.jetbrains.kotlin.plugin.compose` 2.1.20 + Gradle 8.14.3 + Compose BOM 2025.06.01 + compileSdk/targetSdk 36.
- Check `~/.gradle/caches/modules-2/files-2.1/` to see which artifact groups are already cached.

## Pitfalls
- Kotlin Compose state delegates (`var x by mutableStateOf(...)`) need BOTH `androidx.compose.runtime.getValue` and `setValue` imports in non-composable files.
- Compose `Brush.linearGradient(start=Offset(...))` needs `androidx.compose.ui.geometry.Offset` import; `kotlin.math.abs` etc. must be imported explicitly.
- Don't replace `import androidx.compose.material3.*` with individual imports unless there's a conflict — the wildcard is fine.
- See references/version-pins.md for the exact working build files from a real project.

## References
- references/version-pins.md — known-good Gradle/AGP/Kotlin/Compose versions and where to query latest.