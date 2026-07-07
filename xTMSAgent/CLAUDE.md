# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

xTMSAgent is an Android launcher application for NEXGO SmartPOS devices. It serves as a custom home screen with integrated TMS (Terminal Management System) functionality for managing application installations, configuration updates, and device settings.

**Key Components:**
- **Android Launcher**: Custom home screen with draggable app grid
- **MQTT Client**: HiveMQ-based client for real-time TMS communication
- **Device Integration**: Uses NEXGO SmartPOS SDK for device-specific operations

## Build Commands

### Standard Build Tasks
```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (signed)
./gradlew assembleRelease

# Clean build
./gradlew clean

# Install debug on connected device
./gradlew installDebug
```

### Running Tests
```bash
# Run unit tests
./gradlew test

# Run instrumented tests on connected device
./gradlew connectedAndroidTest

# Run specific test
./gradlew test --tests "one.globalconnect.xtmsagent.SpecificTest"
```

### Build Configuration
- **compileSdk**: 36
- **minSdk**: 29
- **targetSdk**: 36
- **Kotlin Version**: 1.9.21
- **Java Target**: 19

## Architecture

### Application Structure

**MainActivity.kt** (app/src/main/java/com/uic/home/MainActivity.kt)
- Entry point and primary activity
- Launcher functionality with app grid management
- Permission handling (storage, install packages)
- TMS operations coordination (version checks, updates)
- Password protection system for settings
- Uses companion object for shared state and logging

**TMS_FUNC.kt** (app/src/main/java/com/uic/home/TMS_FUNC.kt)
- Configuration management (Launcher_Config.JSON)
- Theme configuration (background, foreground, font colors/sizes)
- Server connection parameters (MQTT/HTTPS settings)

**GridAdapter & ItemTouchHelperCallback** (app/src/main/java/com/uic/home/btn_move/)
- RecyclerView adapter for draggable app grid
- Implements drag-and-drop for app reorganization
- Persists app layout to XML configuration

### Configuration Files

**Launcher_Config.JSON** (app/src/main/assets/cfg/)
```json
{
  "launcher_theme": {
    "background_color": "FF1A1A1A",
    "foreground_color": "FF000000",
    "font_color": "FF808080",
    "font_size": 20,
    "system_pwd_protection": false
  },
  "tms_cfg": {
    "server_addr": "tms.uiclatam.com",
    "tcp_port": 5050,
    "tcp_ssl": false,
    "ftp_port": 990,
    "ftp_ssl": true,
    "ftp_user": "tmsftp",
    "ftp_password": "...",
    "nii": 678,
    "conn_timeout": 10,
    "resp_timeout": 60,
    "attempt_counter": 2
  }
}
```

**Application Persistence** (launch.xml)
- Stored at: `/storage/emulated/0/Android/data/one.globalconnect.xtmsagent/files/launch.xml`
- Contains app list order, names, package names, and background colors
- Loaded/saved via `LoadAppList()` and `SaveAppList()`

### Data Flow

1. **Initialization**:
   - MainActivity.onCreate() → checkForPermission() → Init()
   - Assets copied to internal storage
   - Configuration loaded from JSON

2. **TMS Update Check**:
   - Updates delivered via MQTT (`easy` / `notify` topics)
   - EasyTaskManager / AppUpdateManager handle download and install
   - Configuration reload triggered on file modification time change

3. **App Launch**:
   - User taps app button in grid
   - LaunchApp(packageName) called
   - Android intent launched for target package

4. **Configuration Updates**:
   - TMSFunc.ChkParamChange() monitors JSON file modification
   - Reloads theme and server settings dynamically
   - Updates UI colors/fonts without restart

### Key Technical Details

**Signing Configuration**:
- Release builds use keystore: `app/homekey.jks`
- Key alias: "key0"
- Store/key passwords stored in build.gradle.kts (production should use secure storage)

**Version Generation**:
- Custom `generateGitInfo()` function in build.gradle.kts
- Currently hardcoded to "202503241659"
- Output APK name format: `{versionName}-{gitInfo}-{buildType}.apk`

**NEXGO SDK Integration**:
- AAR file: `app/libs/nexgo-smartpos-sdk-v3.08.002_20240410.aar`
- Used for device-specific operations (S/N retrieval, beeper, etc.)
- APIProxy.getDeviceEngine() provides device info access

**Logging**:
- Debug logs via `Logd()` companion function (debug builds only)
- Persistent log file: `/storage/emulated/0/Android/data/one.globalconnect.xtmsagent/files/Log.txt`
- Format: `[YYYY/MM/DD HH:mm:ss] message`

**Permissions Required**:
- QUERY_ALL_PACKAGES: List installed apps
- MANAGE_USB: USB device management
- INTERNET: TMS server communication
- READ/WRITE_EXTERNAL_STORAGE: File operations
- MANAGE_EXTERNAL_STORAGE: Full storage access
- REQUEST_INSTALL_PACKAGES: APK installation
- INSTALL_PACKAGES: System-level installation

### Important Development Notes

**Build Type Detection**:
```kotlin
if ("release" == BuildConfig.BUILD_TYPE) {
    // Release-specific behavior
}
```

**Back Button Handling**:
- Custom OnBackPressedCallback prevents leaving launcher
- Typical behavior for home screen replacement apps

**Password System**:
- Each password is entered as two separate fields (p0 + p1); both must match to authenticate
- **Admin password (index 0)** default: `"22687075"` / `"27071287"` — stored SHA-256 hashed in `Password.xml` (`MainActivity.kt:674`)
- **Secondary password (index 1)** default: `"1234567"` / `"8901234"` (`MainActivity.kt:680`)
- **Super user passwords** are date-derived (change daily): `SHA256(date + seed).take(8 hex) → toLong(16) % 100_000_000` formatted as 8 digits (`MainActivity.kt:957`); seeds stored in `SuperPwdStore`
- Rate-limiting: lockout for 30 s after 3 consecutive failures, escalating up to 3 min after 18+ failures
- Used for accessing settings, triggering updates, app management

## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- After modifying code files in this session, run `graphify update .` to keep the graph current (AST-only, no API cost)
