# SmartPoi UDP Extras

An Android companion app for **SmartPoi** LED glow-stick devices. It streams
live, frame-by-frame patterns over UDP to up to two POIs running in AP mode,
and can also switch their on-board patterns over HTTP.

Built with Kotlin + Jetpack Compose (minSdk 26, target/compile SDK 36).

## Features

| Screen | Description |
|--------|-------------|
| **Computer Generated** | Animated patterns (Perlin noise, arc noise, plasma) masked by geometric shapes — triangle, twin triangle, inverted triangle, circle, star, spiral, diamond, rings, plus wave / zigzag / tiled patterns. Live preview, background render loop. |
| **Zap Game** | Two tap-targets (magenta = POI 1, cyan = POI 2); tapping fires a bright pixel sweep down that POI's LED strip. |
| **Audio Reactive** | Microphone RMS volume drives a centred gradient line, with an adjustable gain slider. Ported from `udp_send_SmartPoi_8_sound_activation.pde`. |
| **Light Saber** | Coloured right-angle triangle pattern; ON/OFF play `lightup.mp3` / `lightoff.mp3`, swinging the phone plays `wave.mp3` (accelerometer). Each row is sent 7× for persistence. Ported from `udp_send_SmartPoi_8.pde`. |
| **Settings** | POI 1 / POI 2 IP addresses (AP mode), default LED strip size, stream FPS cap (0.5–10.0), packet repeat, and on-board POI modes 1–6. |
| **Magic Poi** (`udp_upgrade`) | Log in / sign up (JWT) to a MagicPoiAlphaServer, list parties you own or are invited to, join the stream. The party OWNER gets START / STOP buttons. |
| **Server Bridge** (`udp_upgrade`) | Phone as UDP relay: two modes — **Magic Poi party** (MAGICPOI_* protocol to magicpoi-streamd, coordinates from the join API, shows "waiting for start" cylon state) and **Legacy server** (old SMARTPOI_* protocol, no auth, still works against the old server on :2391). |

## Requirements

- Android 8.0+ (API 26)
- Two SmartPoi devices in **AP mode** with known IPs (defaults: `192.168.1.1` and `192.168.1.78`)
- The phone must join the POIs' WiFi network for UDP streaming

## Build

```bash
# from the project root (Android Studio: open the folder and run)
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. `local.properties` must
point at your Android SDK (`sdk.dir=...`) — it is git-ignored.

## Usage

1. Open **Settings** and confirm the POI IPs and LED strip size.
2. On any streaming screen press **Prime** — the app tells both POIs to turn
   their LEDs off and switch to **UDP mode** over HTTP
   (`/pattern?patternChooserChange=...`).
3. Press **Start** to begin streaming; **Stop** turns the LEDs off again.

## How the UDP protocol works

- Each pixel is encoded as a single byte (`encodePixel`), so a `W × H` frame is
  streamed row by row as `W`-byte UDP datagrams.
- For `H == 1` (line modes) one `W`-byte packet is sent per frame; otherwise
  rows are spaced evenly across the frame period.
- A dedicated background worker handles sending (`sendFrameLatest`), so the
  render loop never blocks on slow WiFi — stale frames are dropped instead of
  building lag.
- `packetRepeat` sends each datagram multiple times for reliability.

## Project structure

```
app/src/main/java/za/tomjuggler/smartpoiudpextras/
├── MainActivity.kt          # Entry point, navigation, home menu
├── UtilitiesDrawer.kt       # Slide-in navigation drawer
├── PoiState.kt              # Shared state + UDP/HTTP networking
├── ComputerGeneratedScreen.kt
├── ZapGameScreen.kt
├── AudioReactiveScreen.kt
├── LightSaberScreen.kt
└── SettingsScreen.kt
app/src/main/assets/         # lightup.mp3, lightoff.mp3, wave.mp3
```

## Notes

- Several features are ports of the original Processing sketches
  (`udp_send_SmartPoi_8.pde`, `ZapGame.java`, …) that shipped with the POIs.
- All streaming loops run on background dispatchers; the UI only blits the
  latest rendered frame, so the interface stays responsive.
- The screen stays awake while the app is open (`FLAG_KEEP_SCREEN_ON`).

## License

Private / personal project.