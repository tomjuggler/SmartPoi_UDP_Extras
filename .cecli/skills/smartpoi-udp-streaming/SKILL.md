---
name: smartpoi-udp-streaming
description: Use for SmartPoi LED UDP streaming app work.
tags: [smartpoi, poi, udp, led, android]
---

# SmartPoi UDP Streaming

Tom's SmartPoi LED poi devices (ESP8266, AP mode) receive 3:3:2-encoded LED
frames over UDP and are controlled via a small HTTP pattern-chooser API.
Definitive sources: Cordova app at
`/mnt/Storage/Documents/PROGRAMMING/Cordova/SmartPoi_Controls` (HTTP commands),
Processing sketches in `~/sketchbook/udp_send_SmartPoi_*` (UDP pixel protocol),
Python reference `/mnt/Storage/Documents/PROGRAMMING/Python/PixelBlaze/app/poi_http.py`.
The current Android app is `/mnt/Storage/Documents/PROGRAMMING/Android/SmartPoi_UDP_Extras`
(Kotlin + Compose M3, minSdk 26, target/compileSdk 36).

## Hard-won protocol facts (do not rediscover)

1. **Priming before any stream**: `GET http://<ip>/pattern?patternChooserChange=7`
   (LEDs OFF) then `=0` (UDP Mode), port 80, both POIs. On stream stop send `7` again.
2. **Cleartext HTTP**: modern targetSdk blocks `http://` by default. Android apps
   MUST set `android:usesCleartextTraffic="true"` or the prime commands silently
   never leave the device (no crash, just nothing happens).
3. **UDP pixel format**: port 2390, one byte per LED = RRRGGGBB (3:3:2) + 127
   offset: `(r&0xE0)|((g&0xE0)>>3)|(b>>6)` then `+127`.
4. **Rotation**: frames are rotated 90° clockwise before sending — a COLUMN of
   the image is one LED "line". Each rotated row is ONE datagram of exactly
   `height` bytes. Wrong packet length or no rotation = garbled shapes on the POI
   (colours right, placement wrong).
5. **1-row line modes** (audio volume line, zap pixel): skip rotation, one
   packet of `width` bytes.
6. **Reliability**: rows are sent 3× (pattern modes up to 7×) per POI.
7. **On-board modes** via same HTTP API: 1=Generated, 2=IMG1-5, 3=IMG6-10,
   4=IMG10-20, 5=IMG1-52, 6=On/Off, 7=LEDs OFF, 0=UDP Mode.
8. Only 2 POIs, AP mode, defaults 192.168.1.1 and 192.168.1.78.

## Performance pitfalls (Compose streaming UI)

- NEVER generate per-pixel frames inside a composition/`LaunchedEffect` main
  thread — the UI freezes instantly. Run the render loop in
  `withContext(Dispatchers.Default)`, UDP on `Dispatchers.IO`, and let the UI
  preview blit the latest published frame (reference swap).
- Cap the frame rate (Tom wants low options: 1/2/5/10 fps — no "Max"; it floods
  UDP and drains battery).
- Cancel streaming coroutines when leaving a screen: `DisposableEffect` +
  `onDispose { signalStop }`, and cancel jobs in OFF/STOP handlers.

## Build environment

- SDK at `/mnt/Storage/Documents/PROGRAMMING/Android/android-sdk-linux`
  (platform 36, build-tools 36 present). Never download SDK components.
- System `gradle` binary is broken (missing module); use the wrapper copied
  from ProcessingAndroidDemo + cached Gradle 8.14.3 distribution.
- Version pins that work together (2026-08): AGP 8.13.2, Kotlin 2.1.20 +
  `org.jetbrains.kotlin.plugin.compose`, Compose BOM **2025.06.01**. The newest
  Compose BOM requires AGP 9.x + compileSdk 37 — check before bumping.
- Deploy/test: `adb install -r` then screenshot-verify via
  `adb exec-out screencap -p` (wake screen first with KEYCODE_WAKEUP + key 82).

See `references/smartpoi-protocol.md` for code-level details.
