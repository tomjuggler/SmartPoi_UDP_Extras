---
name: android-compose-app-dev
description: Use when building/porting Android Compose apps on this box.
tags: [android, compose, kotlin, gradle]
---

# Android Compose App Development (local toolchain)

Use when creating or modifying a native Android app project (Kotlin + Jetpack Compose Material 3) in /mnt/Storage/Documents/PROGRAMMING/Android/, including porting Processing-for-Android sketches into a Gradle project.

## Environment (this box)
- SDK: /mnt/Storage/Documents/PROGRAMMING/Android/android-sdk-linux (platforms up to android-36, build-tools 36). Never download SDK components.
- `android` CLI at ~/.local/bin/android; `android studio version-lookup` fails without Studio — instead query maven-metadata.xml directly:
  `curl -s https://dl.google.com/android/maven2/<group/path>/maven-metadata.xml | tr '<' '\n' | grep '^version>'`
- System `gradle` (9.7) is BROKEN for wrapper generation ("Cannot find module 'gradle-public-api-legacy'"). Workaround: copy `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` from an existing project (e.g. ProcessingAndroidDemo) and write your own gradle-wrapper.properties pointing at the cached dist (~/.gradle/wrapper/dists/gradle-8.14.3-*). Build with ./gradlew.
- Java 17 system default; AGP 8.x needs jvmTarget 17.

## Known-good version combo (Aug 2026)
AGP 8.13.2 + Kotlin 2.1.20 (+ org.jetbrains.kotlin.plugin.compose 2.1.20) + Gradle 8.14.3 + compileSdk/targetSdk 36 + Compose BOM **2025.06.01**.
PITFALL: the newest Compose BOMs (2026.x, ui 1.12+) require AGP 9.1+ and compileSdk 37 — check `checkDebugAarMetadata` errors and pin BOM DOWN, not AGP up.

## Workflow
1. Confirm versions via maven-metadata before writing build files (google() + mavenCentral reachable).
2. Scaffold: settings.gradle.kts, build.gradle.kts (root + app), gradle.properties (android.useAndroidX=true), local.properties (sdk.dir), manifest, themes.xml (dark NoActionBar base), adaptive icon (mipmap-anydpi-v26 + drawable vector + colors.xml).
3. Write all Kotlin before first build; then `./gradlew assembleDebug` in background (first build ~5 min) and fix `e:` lines iteratively.
4. Deploy/verify: `adb install -r`, `am start`, `adb exec-out screencap -p > /tmp/x.png` and inspect with vision. Wake device first (KEYCODE_WAKEUP + keyevent 82) or screenshots come back black.

## Compose pitfalls learned the hard way
- **ModalNavigationDrawer**: never mirror drawer open/close in your own boolean — gesture-close desyncs and the hamburger stops working. Drive `rememberDrawerState` directly: `scope.launch { drawerState.open() }`, and on item tap `scope.launch { drawerState.close(); onSelect(it) }`.
- Top-level `by mutableStateOf` delegates in an `object` need BOTH `androidx.compose.runtime.getValue` and `setValue` imports.
- Private top-level vals in one Kotlin file are invisible to sibling files in the same package — share theme colours/helpers as internal/public, or duplicate.
- Explicit material3 imports (Text, Button, MaterialTheme…) per file; wildcard `material3.*` + wildcard `runtime.*` collides.
- `Canvas`/Brush gradient offsets need `androidx.compose.ui.geometry.Offset` import; kotlin.math helpers (abs, sqrt) need explicit imports.

## Android 16 / targetSdk 36 gotchas
- **Cleartext HTTP is blocked by default** — any app talking to plain-HTTP LAN devices (ESP8266/SmartPoi etc.) needs `android:usesCleartextTraffic="true"` in <application>. Symptom: fetch/openConnection fails silently, device never responds.
- Runtime permissions (RECORD_AUDIO) via `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`.

## User preferences (Tom)
- Strip sizes for POI work: 36, 60, 72, 120, 200 px everywhere — never just 36–120.
- Every feature = separate menu item; app must have a real utilities drawer menu, not bare screens.
- "Flashy modern": Compose M3 dark theme, animated gradient headers, FilterChips, live Canvas preview + FPS readout.

## SmartPoi protocol reference
See references/smartpoi-protocol.md for the definitive UDP/HTTP protocol harvested from the Cordova SmartPoi_Controls app and the Processing sketches.
