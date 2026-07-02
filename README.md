# 🌌 StreamCast

[![Android Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white&style=for-the-badge)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-7F52FF?logo=kotlin&logoColor=white&style=for-the-badge)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white&style=for-the-badge)](https://developer.android.com/jetpack/compose)
[![Material Design 3](https://img.shields.io/badge/Design-Material%203-FF4081?style=for-the-badge)](https://m3.material.io/)

StreamCast is a high-performance, fully localized media casting and stream sniffing application for Android. Built with a **Cosmic Slate** visual aesthetic, StreamCast combines modern Jetpack Compose layouts with low-level network server components to stream local content and sniff web browser media directly to Smart TVs, Roku, and DLNA/UPnP receivers.

---

## ✨ Features & Capabilities

```
    StreamSniff Browser ──► Web Video Interceptor (.m3u8, .mp4) ──┐
                                                                   ├──► Cast Controller
    Local Media Files   ──► Range-Request HTTP Server (Port 8182) ─┘          │
                                                                              ▼
    Local Subnet Scan   ◄── SSDP, mDNS, Roku, and Manual IP Scan ◄─── Smart TV Targets
```

### 🔍 1. Subnet Discovery Engine
*   **Multicast Discovery (mDNS & SSDP):** Automatically scans active local networks for Smart TVs, Roku, Chromecast, and DLNA renderers.
*   **Target IP Injection:** Avoid discovery limitations caused by strict router configurations (AP isolation, multicasting drop) with direct IP entry.

### 🌐 2. Web Stream Sniffer & Browser
*   **Embedded Sniffing WebView:** Intercepts modern stream formats, including HLS (`.m3u8`), MP4, DASH (`.mpd`), and WebM.
*   **Local History & Bookmarks:** Offline Room Database caches browsing history and bookmark directories safely.

### ⚡ 3. Local Range-Request Server
*   **Localized Background HTTP Server:** Spins up a socket-based background server listening locally on port `8182`.
*   **Range Content Seeking:** Fully supports HTTP standard `206 Partial Content` (Range-Requests) so smart TV receivers can effortlessly scrub/seek through heavy local files.

### 🪵 4. Centralized Structured Logging (`LoggingModule`)
*   **Structured Format:** Centralized system to intercept, categorize, and format logs across **System**, **DiscoveryEngine**, **MediaController**, **Server**, and **Browser**.
*   **Unified Debug Panel:** Offers live diagnostics, a dynamic log viewer inside a beautiful diagnostic HUD overlay, real-time levels, and a clean `getLogs()` API.

### 🛠️ 5. Offline Diagnostics & Troubleshooter
*   **Intelligent Local Auditing:** Evaluates Wi-Fi state, gateway status, and IP setups.
*   **Self-Help Resolution:** Step-by-step resolution logs for router steering, dual-band isolation, and multicast drops.

---

## 🛠 Tech Stack & Modern Architecture

StreamCast adheres strictly to Android MVVM, unidirectional data flows (UDF), and clean modular architecture:

| Component | Technology | Description |
|---|---|---|
| **UI Framework** | **Jetpack Compose** | Modern declarative Material 3 interface |
| **Concurrency** | **Kotlin Coroutines & Flow** | Real-time reactive updates (`StateFlow`, `collectAsStateWithLifecycle`) |
| **Local DB** | **Room Database** | Type-safe SQL caching for streams and history |
| **Http & Network** | **OkHttp & Custom Sockets** | Multicast socket management & local HTTP server |
| **Diagnostic HUD** | **Custom DiagnosticCards** | Implements the dynamic debug panel and UI HUD overlay |

### 📂 Directory Structure

```text
app/src/main/java/com/example/
├── AppLogger.kt            # Legacy log bridge maintaining full UI backwards-compatibility
├── LoggingModule.kt       # NEW: Core logging architecture with structured tags & levels
├── CastViewModel.kt        # State manager & controller for servers/discovery
├── StreamCastApp.kt        # Application initialization
├── MainActivity.kt         # Jetpack Compose Entry point, permission triggers
├── browser/
│   └── WebSniffer.kt       # WebView stream sniffer controller
├── casting/
│   ├── DiscoveryEngine.kt  # mDNS, SSDP, and Roku scan engine
│   └── RokuCastHandler.kt  # Roku device protocol implementation
├── database/
│   └── AppDatabase.kt      # Local Room configuration
├── server/
│   └── LocalHttpServer.kt  # Range-Request (206) socket HTTP server
├── service/
│   ├── UpdateCheckService.kt
│   └── UpdateCheckWorker.kt
└── ui/
    ├── DiagnosticHudOverlay.kt # HUD diagnostic display
    ├── theme/                  # Theme configurations (Cosmic Slate Color Schemes)
    └── components/
        ├── DeviceCards.kt      # Interactive target card components
        ├── DiagnosticCards.kt  # System diagnostic rendering
        └── MediaCastingCards.kt # Web video list and current casting layout
```

---

## 🚀 Getting Started & Build

### Prerequisites
*   Android SDK 34+
*   JDK 17

### 1. Configure Secrets
Ensure you have copied the example environment config if needed:
```bash
cp .env.example .env
```

### 2. Gradle Command-Line Commands
Compile and package the debug APK using standard Gradle:
```bash
# Assemble the debug APK
gradle assembleDebug

# Run unit and local integration tests
gradle :app:testDebugUnitTest
```

Once built, find your output at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📡 Continuous Integration (GitHub Actions)

This project is continuous-integration ready with a preconfigured pipeline at `.github/workflows/android.yml`:
*   **Triggers:** Triggers on any commit or pull request to core branches.
*   **Steps:** Decodes the secure Keystore, sets up JDK 17, resolves version catalogs, builds unit tests, compiles the production-ready APK, and returns the compiled artifact to the workflow actions page automatically.
