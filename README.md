# Echo Show 8 - Music Assistant Now Playing

A dedicated, full-featured **Now Playing** client and **App Launcher Overlay** designed specifically for the **Amazon Echo Show 8** (and other Android-based smart displays / tablets), integrated seamlessly with [Music Assistant](https://music-assistant.io/).

---

## ✨ Features

* **🎨 Music Assistant Web Layout Match:**
  * Clean, edge-to-edge dark theme designed for landscape smart displays (1280x800).
  * Rounded album artwork card with ambient drop shadow.
  * Centered bold track title, album name, and artist name.
  * Active player device pill (e.g. `[ Echo Show 8 ]`).

* **🎤 Synchronized Lyrics (Karaoke Mode):**
  * Borderless floating synchronized lyrics stream.
  * Real-time auto-scrolling with current active line centered and highlighted in bold pure white.
  * Inactive lines smoothly dimmed.
  * **Tap-to-Seek:** Tap any lyric line to instantly jump playback to that timestamp.
  * Intelligent lyrics provider using [LRCLIB](https://lrclib.net/) with title cleanup and parenthetical fallback.
  * One-tap toggle via `[ 🎤 Lyrics ]` pill or album artwork.

* **🎛️ Full Transport & Playback Controls:**
  * Scrubbable progress bar with live elapsed and duration indicators.
  * Previous, circular accent Play/Pause, and Next buttons.
  * Hardware/Software volume slider with mute indicator and live volume percentage.

* **⚡ Floating System Navigation Overlay & App Drawer:**
  * Draggable floating minimal dot overlay that stays accessible on top of any app.
  * Tap to expand into a quick navigation menu:
    * **Home:** Return to device launcher / Echo home.
    * **App Drawer:** Built-in grid of all installed apps on the device (e.g., VLC, Music Assistant, Settings) with tap-to-launch.
    * **Now Playing:** Jump directly back to the player.
  * **Auto-Start on Boot:** Automatically launches the overlay service on boot (`BOOT_COMPLETED`).

* **🔌 Real-Time WebSocket Synchronization:**
  * Direct bidirectional WebSocket connection to Music Assistant server (`ws://<host>:8095/ws`).
  * Subscribes to real-time `player_updated` events.
  * Exponential backoff and auto-reconnection if network drops.

---

## 📱 Hardware & Target Platform

* **Target Device:** Amazon Echo Show 8 (1st Gen 2019, codename `crown`)
* **OS:** FireOS 6 / Android 7.1.2 Nougat (API 25)
* **Screen Resolution:** 1280x800 (landscape, tvdpi ~213dpi)
* **Minimum SDK:** Android 7.1 (API 25)
* **Target SDK:** Android 14 (API 34)

### 🔓 Device Prerequisites: Unlocking ADB / Root
Amazon Echo Show devices do not expose Developer Options or ADB out-of-the-box. To install third-party apps and use ADB over Wi-Fi, the Echo Show 8 (1st Gen, 2019) must first be bootloader-unlocked and rooted:
* **XDA Guide:** Follow the step-by-step tutorial: [[UNLOCK][ROOT][TWRP][UNBRICK] Amazon Echo Show 8 1st Gen (2019) "crown"](https://xdaforums.com/t/unlock-root-twrp-unbrick-amazon-echo-show-8-1st-gen-2019-crown.4766687/)
* Once rooted with Magisk/TWRP, enable network ADB over Wi-Fi:
  ```bash
  setprop service.adb.tcp.port 5555
  stop adbd
  start adbd
  ```
* Connect from your computer via `adb connect <echo-ip>:5555`.

---

## 🚀 Getting Started

### 1. Build the APK

Prerequisites: JDK 17+ and Android SDK.

```bash
# Clone the repository
git clone https://github.com/solvedro/echo-now-playing.git
cd echo-now-playing

# Build release APK
./gradlew assembleRelease
```

The compiled APK will be at:
`app/build/outputs/apk/release/app-release.apk`

---

### 2. Install on Echo Show 8 via ADB

Ensure ADB is enabled on your Echo Show:

```bash
# Connect to Echo Show 8 over Wi-Fi
adb connect 192.168.100.10:5555

# Install the APK
adb -s 192.168.100.10:5555 install -r app-release.apk

# Grant Overlay (System Alert Window) permission
adb -s 192.168.100.10:5555 shell pm grant io.music_assistant.nowplaying android.permission.SYSTEM_ALERT_WINDOW

# Launch Now Playing
adb -s 192.168.100.10:5555 shell monkey -p io.music_assistant.nowplaying -c android.intent.category.LAUNCHER 1
```

---

### 3. Server Configuration

On first launch (or via the **Settings (⚙️)** icon in the top right):
1. Enter your **Music Assistant Server IP / Host** (e.g. `192.168.100.22` or Home Assistant IP).
2. Enter the **WebSocket Port** (default: `8095`).
3. Select your target player from the automatically discovered players list (e.g., `Echo Show 8`).

---

## 🛠️ Architecture

* **Language:** Kotlin 2.0
* **Networking & WebSocket:** OkHttp 4.12
* **Image Loading:** Coil 2.6
* **Lyrics Engine:** LRCLIB API with custom LRC parser & auto-centering `LinearSmoothScroller`
* **Architecture Components:** AndroidX ViewBinding, SharedPreferences, Foreground Service for Overlay

---

## 📄 License

MIT License - feel free to use and adapt for your smart home setup!
