# CleanYourTV & Launcher Customizations — Complete Handover Guide

> **Target Repository**: `cleanyourtv` (`main` branch)  
> **Upstream**: `https://github.com/Gathin23/cleanyourtv.git`  
> **Fork**: `https://github.com/shivam0198-code/cleanyourtv.git`  
> **Target Device**: Xiaomi Mi TV 5X (Android 10 API 29, 1080p, IP: `192.168.1.8:5555`)  
> **DVR System**: CP Plus 8-Channel DVR (IP: `192.168.1.2`, RTSP 554, HTTP 80, User: `admin`, Pass: `admin@123`)  
> **Date**: November 2026

---

## 1. Repository Architecture & Core Structure

CleanYourTV is an ultra-minimalist, high-performance Android TV launcher built specifically for low-overhead operation on smart TVs and TV sticks (such as Xiaomi Mi TV, Fire TV, Sony Bravia, Chromecast with Google TV).

### Key Modules:
- **`launcher/` (`com.gathin.tvhome`)**:
  - The core TV launcher application.
  - Written in native Android Java with lightweight view hierarchies.
  - Zero heavy external dependencies (no Jetpack Compose, no React Native, minimal memory footprint ~35-50 MB RAM).
  - Main files:
    - `MainActivity.java`: Primary TV launcher activity handling the app grid, top navigation bar, D-pad remote navigation, animations, and lifecycle.
    - `Config.java`: Central configuration engine that loads base properties from `res/values/config.xml` and dynamically overrides them at runtime via `/sdcard/Android/data/com.gathin.tvhome/files/config.json` or local theme configs.
    - `AppAdapter.java`: RecyclerView adapter rendering app tiles, dock items, banners, and module cards.
    - `MemInfo.java`: Direct `/proc/meminfo` parser for accurate hardware memory tracking.
    - `ShareServer.java` & `ShareWebTemplate.java`: Lightweight embedded HTTP server and web portal for wireless TV Drop file transfer.
    - `CctvManager.java` & `CctvDigest.java`: CCTV surveillance camera coordinator and RFC 2617 Digest HTTP engine.
- **`addons/home-cctv/` (`com.gathin.homecctv`)**:
  - Standalone TV app for multi-channel CCTV surveillance with ONVIF discovery and Command Center layout.
- **`tools/`**:
  - Python TV management utilities (`manage.py`, `theme.sh`, `theme.ps1`) for remote theme injection and wallpaper switching via ADB.

---

## 2. GitHub Actions CI Build Pipeline

### Why GitHub CI?
The local environment is a lightweight Linux container without a full Android SDK / NDK toolchain. Compiling Android APKs locally would exhaust memory and disk space. Instead, compilation is delegated to GitHub Actions runners (`ubuntu-latest` with OpenJDK 17 and pre-cached Android SDK).

### CI Rules & Workflow:
- **Strict Constraint**: Never edit `.github/workflows/ci.yml`.
- **Automatic Build Hook**:
  - In `launcher/app/build.gradle`, the task `outputLauncherApk` is hooked into `assembleRelease`:
    ```groovy
    afterEvaluate {
        tasks.named('assembleRelease').configure {
            finalizedBy('outputLauncherApk')
        }
    }
    ```
  - When `assembleRelease` finishes, `outputLauncherApk`:
    1. Base64-encodes the compiled `app-release.apk` and streams it into the CI job log between `<<<START_B64_launcher>>>` and `<<<END_B64_launcher>>>`.
    2. Uploads the file to `temp.sh` as a secondary fallback.

### Building & Sideloading Procedure:
1. **Commit & Push**:
   ```bash
   git add -u
   git commit -m "fix/feat: description"
   git push myfork main
   ```
2. **Monitor Build**:
   ```bash
   gh run list --repo shivam0198-code/cleanyourtv -L 1
   gh run view <RUN_ID> --repo shivam0198-code/cleanyourtv
   ```
3. **Extract APK**:
   Extract the base64 output from `gh run view --job <JOB_ID> --log` and decode into `/tmp/cleanyourtv-release.apk`.
4. **Sideload to TV**:
   ```bash
   adb push /tmp/cleanyourtv-release.apk /data/local/tmp/app.apk
   adb shell pm install -r -g /data/local/tmp/app.apk
   ```
   > **CRITICAL**: Always use the `-g` flag with `pm install` to automatically grant all required runtime permissions (`READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`). Without `-g`, Android 10/11 revokes storage permissions, causing TV Drop to appear empty.

---

## 3. Miracast & Inputs Menu Integration

### Purpose & Behavior:
- Smart TVs have both physical HDMI inputs and wireless display protocols (Miracast / Screen Mirroring).
- The Inputs dropdown menu was enhanced to feature physical TV inputs (HDMI 1, HDMI 2 / PlayStation, etc.) along with a dedicated **Miracast** tile.

### Implementation:
- **Vector Icon**: `launcher/app/src/main/res/drawable/ic_cast.xml` provides a crisp wireless screen mirroring vector.
- **Config & Resolution**:
  - `castApp` and `castAppLabel` configuration keys in `Config.java` and `config.xml`.
  - Automatically launches the vendor Miracast activity (e.g. `com.xiaomi.mitv.screencast` on Xiaomi Mi TV) when selected.
  - Positioned inside the Inputs popup dropdown without clashing with physical TV tuner inputs.

---

## 4. QR Code Share Menu (TV Drop)

### Purpose & Architecture:
- TV Drop allows users to wirelessly transfer files and APKs between their phone/PC and the TV without any third-party apps or cables.
- Opened from the top navigation bar via `btnShare` (featuring a clean QR code icon).

### Key Features:
1. **On-Screen QR Code Dialog**:
   - Generates a real-time QR code using `QrView.java` pointing to `http://<tv-ip>:8080`.
   - Displays available TV storage in GB.
2. **Embedded Web Portal (`ShareWebTemplate.java`)**:
   - Clean dark-mode glassmorphic interface (custom-designed for mobile phones, zero horizontal scroll).
   - **Hero 1-Click APK Installer**: Select an APK on the phone; it uploads directly and automatically triggers Android's package installer on the TV screen.
   - **Streaming Multipart Upload**: Chunked 32KB streaming uploads with synchronized progress bars on both phone and TV.
   - **File Management**: Mobile file browser supporting folder creation, downloading files to the phone, and safe deletion.
3. **Storage Permission Safeguard**:
   - `showShareOverlay()` in `MainActivity.java` checks `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE`.
   - Automatically requests permissions if revoked, ensuring the file list never appears blank or empty.

---

## 5. Sleek & Configurable Dock Position Dots

### Background & Improvements:
- In upstream CleanYourTV, dock position dots were exclusively tied to the `cinematic_home` top shelf. On standard home screens, no position dots were rendered.
- Furthermore, the default dots were large (6dp diameter unfocused, 20dp wide focused).

### Modifications:
1. **Unified Activation**:
   - In `fillHero(position, label)`, `updateHeroDots(position)` is called on both standard home and cinematic home.
   - Controlled by the `dock_dots` boolean config.
2. **Sleek Dimensions & Dynamic Layout**:
   - Default dot size reduced to `4dp` (sleeker profile).
   - Focused dot pill width set to `14dp`.
   - Corner radius set dynamically: `(dockDotsSize / 2f) * density`.
3. **Configuration Keys (`config.xml` & `config.json`)**:
   - `"dock_dots"`: `true` / `false` (Show/hide position dots).
   - `"dock_dots_size"`: Integer dp (Default `4`).
   - `"dock_dots_focused_width"`: Integer dp (Default `14`).

---

## 6. Minimalist RAM Meter & Clock Synchronization

### Purpose:
- Users requested a sleek, minimal RAM meter directly underneath the clock/date header showing real-time memory usage out of total TV RAM (`RAM X.X / Y.Y GB`).

### Implementation:
- **Layout**: `activity_main.xml` contains `ramMeterLayout` directly beneath `clockBlock`.
- **Data Source**: `MemInfo.java` reads `/proc/meminfo` every 5 seconds.
- **Config**: `"show_ram_meter"` in `config.xml` & `config.json`.

### Clock Wake Lag Fix:
- **Root Cause**: Upstream `TIME_VALID_AFTER_MS` was set to `1_735_689_600_000L` (Jan 1, 2025). On TV wake from standby, the clock would temporarily read invalid before NTP sync, hiding `clockBlock` for 15–20 seconds while the RAM meter remained visible.
- **Fix**:
  - Lowered `TIME_VALID_AFTER_MS` to Year 2000 (`946684800000L`), ensuring the clock is visible immediately upon wake.
  - Linked `ramMeterLayout` visibility directly to `clockBlock.getVisibility() == View.VISIBLE`, guaranteeing that the clock and RAM meter always stay perfectly synchronized.

---

## 7. CCTV Surveillance Camera Integration

### Architecture & DVR Setup:
- **DVR**: CP Plus 8-Channel DVR at `192.168.1.2:80` (HTTP) and `192.168.1.2:554` (RTSP).
- **Authentication**: Dahua/CP Plus HTTP Digest Auth (RFC 2617).
  - Fixed MD5 realm parsing and hexadecimal byte conversion (`b & 0xFF` masking) in `CctvDigest.java`.
  - Escaped RTSP credentials (`admin:admin%40123@...`) in `CctvManager.java`.

### UI & Remote Navigation:
1. **Swipe-Away Navigation**:
   - On the Home screen, a subtle glass gutter tab on the right edge indicates off-screen content.
   - Swiping right from the last dock app or pressing Right on the top bar slides the app grid out and slides the CCTV container in.
   - Pressing Left from the leftmost camera or pressing Back slides the CCTV view out and restores the Home screen.
2. **2x2 Grid + Peeking Camera 5**:
   - Cameras 1–4 are laid out in a balanced 2x2 grid.
   - Camera 5 peeks in from the right edge.
   - **Camera 5 Sizing**: Wrapped in a vertical column matching the exact height and width of Cameras 1–4 with bottom spacing, preventing vertical stretching.
3. **Rounded Corners & Glass Scrim**:
   - `cctvContainer` background is `#14FFFFFF` (translucent glass), allowing the launcher's active wallpaper and ambient glow to shine through.
   - Every camera card has `clipToOutline="true"` and a programmatic `ViewOutlineProvider` with `16dp` corner radius, perfectly matching the launcher's app tiles without square bleeding.
4. **1-Click Live HD Expansion**:
   - **The Problem with RTSP H.265**: Media3 ExoPlayer 1.4.1 hangs on Dahua/CP Plus H.265 DHAV RTSP streams waiting for SPS/PPS packets, and calling `player.release()` synchronously on the UI thread triggers a 20-second ANR freeze.
   - **The High-Speed Snapshot Pipeline**:
     - Clicking or pressing DPAD_CENTER on any camera card immediately opens `cctvExpandedOverlay`.
     - The overlay immediately seeds `cctvHdImg` with the camera's current snapshot (zero black screen).
     - `cctvManager.startHighSpeedStream()` launches a dedicated worker thread fetching snapshots for that channel at ~150ms intervals (~6-7 FPS live feed matching native DVR sub-stream rate).
     - `cctvManager.releasePlayer()` runs asynchronously in a background executor thread, completely eliminating UI thread blocking.
     - Pressing Back smoothly collapses the HD overlay and resumes the 5-camera grid loop.

---

## 8. Summary of Active Configurations & Keys

```xml
<!-- Minimalist RAM meter below clock -->
<bool name="show_ram_meter">true</bool>

<!-- CCTV Screen Integration -->
<bool name="cctv_enabled">true</bool>
<string name="cctv_ip">192.168.1.2</string>
<string name="cctv_user">admin</string>
<string name="cctv_password">admin@123</string>
<integer name="cctv_channels">5</integer>

<!-- Dock Position Dots -->
<bool name="dock_dots">true</bool>
<integer name="dock_dots_size">4</integer>
<integer name="dock_dots_focused_width">14</integer>
```

---

## 9. Next Steps & Recommended Workflow

1. **TV Power Off**: Per user instructions, power off the TV when testing is concluded via `adb shell input keyevent 26`.
2. **Adding More Cameras**: Increase `cctv_channels` in `config.xml` or `config.json` (DVR supports up to 8 channels).
3. **Testing TV Drop**: Visit `http://192.168.1.8:8080/` from any phone or PC on the LAN to transfer APKs and files.
