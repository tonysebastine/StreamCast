# StreamCast

StreamCast is a high-performance, responsive Android media casting application built completely with modern **Jetpack Compose**, **Kotlin Coroutines/Flow**, and **Room Database**. It enables seamless local media sharing and web stream sniffing directly to smart TVs and casting receivers.

The application utilizes a dark Cosmic Slate visual design theme characterized by rich contrast, generous negative space, and intuitive typography pairings to create a highly readable experience across phone, tablet, and foldable devices.

---

## Key Capabilities

### 1. Unified Device Discovery Engine
- **Multicast Discovery (mDNS & SSDP)**: Dynamically scans the local subnet for smart devices, TV screens, Roku targets, and DLNA renderers.
- **Manual IP Injection**: Allows users to input target casting IPs directly when multicast discovery packets are filtered or blocked by strict network hardware.

### 2. Live Web Video Sniffer
- **Seamless Browser Integration**: Features an embedded high-performance web browser.
- **Dynamic Stream Capture**: Automatically intercepts and sniffs media formats—such as HLS (`.m3u8`), MP4, DASH (`.mpd`), and WebM—ready to transfer directly to external screens.
- **Bookmark & History Repositories**: Integrated with a local Room database to allow users to bookmark their favorite stream directories and review cast history instantly.

### 3. Integrated Local Range-Request Server
- **Background HTTP Server**: Spawns a localized thread-safe server listening on port `8182`.
- **Range Queries**: Supports HTTP partial-content header seeking (Range Request) to enable smooth timeline scrubbing and buffer management on remote TV players casting local media files.

### 4. Offline Diagnostic Troubleshooter
- **Local Network Analyzer**: Runs instant local audits on the active Wi-Fi state, subnet configurations, and hardware errors.
- **Intelligent Offline Guides**: Resolves complex casting failures (such as dual-band 2.4GHz/5GHz router steering, Access Point Isolation settings, and multicast mDNS packet drop conditions) instantly without requiring external internet or API connections.

---

## Architecture & Design Patterns

The project is structured according to **clean MVVM architecture** guidelines to isolate state, platform services, and UI rendering:

- **UI Layer (`MainActivity.kt`)**: Implements Material Design 3 (M3) components, fully adaptive layouts supporting mobile and tablet dimensions, and edge-to-edge rendering.
- **State Layer (`CastViewModel.kt`)**: Manages UI state with reactive Kotlin state flows (`StateFlow`), handling asynchronous operations cleanly within the ViewModel lifecycle scope.
- **Network Engine (`LocalHttpServer.kt`, `DiscoveryEngine.kt`)**: Employs low-level socket, NSD (Network Service Discovery), and HTTP server interfaces to guarantee real-time discovery and media streaming.
- **Persistence Layer (`AppDatabase.kt`)**: Provides robust local SQLite access through Room for keeping history and bookmark records fully synchronized.

---

## Getting Started

### Local Compilation & Build
To build and run the application locally on your machine or inside your build environment:

1. Clone or import the codebase.
2. Initialize environment variables (if any) by copying `.env.example` to `.env`:
   ```bash
   cp .env.example .env
   ```
3. Run the Gradle assemble task to generate the debug APK:
   ```bash
   gradle assembleDebug
   ```
4. The compiled APK will be available in:
   `app/build/outputs/apk/debug/app-debug.apk`

---

## Continuous Integration & Actions

This repository includes a preconfigured GitHub Actions workflow located in `.github/workflows/android.yml`. On every code commit or pull request to the main branches, the pipeline will:
- Set up a secure JDK 17 environment.
- Automatically construct and resolve Gradle dependencies.
- Compile and assemble the production-ready debug APK.
- Publish the artifact directly back to your GitHub workflow actions page for easy distribution.
