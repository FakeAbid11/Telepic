# Telepix

A modern, standalone Android photo & video library with Telegram-powered cloud backup — built with **Kotlin**, **Jetpack Compose**, and **Material 3 / Material You**.

Telepix organizes your local media into a fast, beautiful timeline and (in later phases) backs it up to a private Telegram channel, letting you browse cloud media by preview without automatically downloading originals.

> **Local-first photo management with Telegram-powered cloud backup.**

---

## Technology stack

| Area | Choice |
|------|--------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 (Material You) |
| Architecture | Layered (UI → ViewModel → Domain → Repository → Data), unidirectional state flow |
| Async | Kotlin Coroutines + Flow / StateFlow |
| Navigation | Navigation for Compose |
| Persistence | Jetpack DataStore (Preferences) |
| Local media | Android MediaStore + Paging 3 |
| Persistence (app data) | Room (cloud destination + media manifest) |
| Image loading | Coil (thumbnail-first, video-frame decoding) |
| Cloud backend | Telegram via **Java TDLib** (`org.drinkless.tdlib`), ARM-only |
| Database | Room *(later phases)* |
| Background work | WorkManager *(later phases)* |
| Map | OpenStreetMap *(later phases)* |
| Build system | **GitHub Actions only** |

---

## Building Telepix (no local build required)

**You do not need Android Studio, Gradle, or the Android SDK on your machine.** Telepix is built entirely by **GitHub Actions**.

Every push to `main` and every pull request runs the [`Android CI`](.github/workflows/android-ci.yml) workflow, which:

1. Checks out the repository
2. Sets up JDK 17
3. Sets up Gradle with dependency caching
4. Compiles the app and runs unit tests
5. Runs Android lint (static verification)
6. Assembles a **debug APK**
7. Uploads the APK (and test/lint reports) as workflow artifacts

No Telegram credentials are required for Phases 1–3. From Phase 4 the Telegram (TDLib) backend
needs **API credentials supplied as GitHub Actions Secrets** (see below); without them the debug
build still compiles and runs (login is simply unavailable), so CI stays green.

### Downloading the debug APK

1. Open the repository's **Actions** tab.
2. Select the latest run on `main` (or your pull request).
3. Scroll to **Artifacts**.
4. Download **`telepix-debug-apk`** and install the APK on your device.

You can also trigger a build manually via **Run workflow** (`workflow_dispatch`).

### Telegram credentials (Phase 4+)

To build a login-capable APK, configure **GitHub repository Secrets** (Settings → Secrets and
variables → Actions):

- `TELEGRAM_API_ID` — numeric, from your [Telegram developer account](https://my.telegram.org)
- `TELEGRAM_API_HASH` — string

The CI passes them to Gradle (masked), which injects them into `BuildConfig`. They are **never**
committed, hardcoded, or logged. Without them, debug builds use a safe placeholder and Telegram
login is simply unavailable — the rest of the app (and CI) still work.

### Verifying Telegram login on a real device

Real-device Telegram login must be done manually by the developer (an ARM Android device is
required): install the debug APK, complete onboarding, then on the **Telegram Login** step enter your
phone number, the Telegram code, and (if enabled) your 2FA password. Confirm the authorized state,
restart the app (the session should persist), and test logout/re-login.

---

## Development status

### ✅ Phase 1 — Foundation & Material You Design System *(complete)*

- Valid Android Kotlin project using Jetpack Compose + Material 3
- Telepix branding (app name, blue/black/white identity, adaptive launcher icon)
- Centralized **Telepix design system**: colors, typography, shapes, spacing tokens
- **Dark (default)**, **Light**, and **System default** themes with persisted preference
- Five primary navigation destinations with a Material 3 bottom navigation bar:
  **Photos · Cloud · Albums · Map · Settings**
- Reusable UI components: `EmptyState`, `LoadingState`, `ErrorState`, `ScreenHeader`
- Foundation settings screen (Account, Backup, Appearance, Storage, About)
- Layered architecture foundation with a lightweight manual DI container
- Unit test foundation (navigation, theme, settings persistence)
- GitHub Actions CI producing a debug APK artifact

### ✅ Phase 2 — Onboarding & Permissions *(complete)*

- **Six-screen first-run onboarding**: Welcome · How Telepix Works · Permissions ·
  Telegram Login · Backup Preferences · Ready
- Persistent onboarding state (completion + backup preference) via the existing DataStore
  architecture; completed users launch straight into the main app, fresh installs start onboarding
- Polished Material 3 onboarding with a subtle step-progress indicator, back navigation,
  vector-only illustrations (no stock imagery), and short screen transitions
- **Version-aware media permissions**: Android 13+ granular photo/video, Android 14 partial
  "selected photos" access, and the legacy storage model for older versions — with honest
  granted / partial / denied / permanently-denied states and an open-Settings recovery path.
  No unrelated permissions are requested.
- Backup preference selection (`BACKUP_ALL` / `SELECT_FOLDER` / `NOT_NOW`), persisted for later phases
- **Telegram Login UI foundation**: a real state contract (`TelegramAuthState`) and controller
  abstraction that Phase 4 (TDLib) will implement — no fake authentication, accounts, or channels
- Reusable onboarding components (scaffold, progress, illustration, choice card) and Compose UI
  tests (Robolectric) plus JVM unit tests for persistence, routing, and permission logic

### ✅ Phase 3 — Local Media Library *(complete)*

- **Real local library from MediaStore** — photos, videos, and GIFs are read from
  `MediaStore.Files` as a single, newest-first, paged stream (no full-library scans in memory,
  no filesystem-path identity — content URIs are used)
- **Domain model** `LocalMedia` + type-safe `MediaType` (PHOTO / VIDEO / GIF), normalized from
  MediaStore MIME metadata, with metadata ready for the future Media Details / Albums / folder
  selection features (bucket id/name, relative path, dimensions, size, dates, duration)
- **Paging 3** (`PagingSource` → `Repository` → `ViewModel` → `LazyVerticalGrid`) for fast,
  incremental, memory-efficient loading with stable keys and cancellation
- **Thumbnail-first grid**: adaptive columns, rounded tiles, video play + duration indicators,
  GIF badges, **Coil** image loading (with video-frame decoding) — full originals are never decoded
  for the grid
- **Chronological date grouping** with full-width day headers and a subtle **right-side date rail**
  foundation for quick timeline navigation
- **Permission-aware** (reuses the Phase 2 model): queries only when access is available; honest
  granted / partial / denied / blocked states, each with appropriate UI and a recovery path
- Distinct **Loading / Empty / Permission / Error** states with retry; MediaStore **change detection**
  via a lifecycle-safe `ContentObserver` that invalidates the paged data
- Viewer **navigation contract** (passes only a stable media id); the full viewer is a later phase
- No backup, hashing, Telegram, or cloud is triggered by viewing media

### ✅ Phase 4 — Telegram / TDLib Integration *(complete: code + CI)*

- **Java TDLib** bindings (`org.drinkless.tdlib.Client` / `TdApi`) via the `io.github.tdlib-android:core`
  AAR — **no JSON client, no hand-written JNI shim**
- **ARM-only** native libraries (`arm64-v8a`, `armeabi-v7a`) selected via `ndk.abiFilters`; the APK
  excludes x86/x86_64. `minSdk` raised to **26** (required by the TDLib artifact)
- A clean layer boundary: **UI → `TelegramAuthController` → `TelegramSessionManager` → `TdLibClientGateway` → TDLib**.
  The UI never touches `Client` directly
- Full authentication state machine mapping **every** TDLib authorization state to `TelegramAuthState`
  (initializing → phone number → code → optional 2FA password → authorized; closing/closed; errors)
- **Persistent, app-private TDLib database** (`noBackupFilesDir`) with a **Keystore-wrapped
  encryption key** — login survives app restart and process death; never stored on shared storage
- Real onboarding Telegram UI: phone / code / 2FA inputs, account identity on success, and
  recoverable errors (invalid credentials, **flood wait**, network) — **never fakes a login**
- **Non-blocking startup**: TDLib initializes without ever delaying or crashing the local Photos flow
- **Secure logging**: API hash, phone number, authentication code, password, and the encryption key
  are never logged

### ✅ Phase 5 — Telegram Cloud *(architecture + UI + persistence complete; live cloud calls pending device bring-up)*

Implemented and CI-verified:
- **Room** introduced (KSP) with an explicit schema and **no destructive fallback**:
  `cloud_destination` (single validated row per provider) and `cloud_media_manifest`
  (stable identity `chatId + messageId`, optional reserved hash field for Phase 7)
- Clean layering: **Cloud screen → `CloudViewModel` → `CloudRepository` → `CloudDataSource`
  → TDLib**, reusing the **single** Phase 4 client/session/gateway (never a second Telegram client)
- Domain models: `TelepixCloudDestination`, `CloudMedia` (IMAGE / VIDEO / GIF, GIF kept distinct),
  `CloudPreview`, `LocalDownloadedMedia`; a rich `CloudStatus` (initializing / connecting /
  refreshing / ready / empty / offline / not-authenticated / destination-missing / invalid / error)
- `ChatValidator` enforces a **safe destination** (accessible channel the account can post to, with
  the expected title) — never the title alone, so a same-name channel owned by someone else is rejected
- Repository orchestration (discover → create-if-needed → validate → persist → refresh → manifest
  upsert with de-dup → state), race-guarded, cancellable, on background dispatchers; **no uploads**
- A functional **Cloud screen** (adaptive grid, video/GIF indicators, preview-on-demand) that
  **never downloads originals to browse** and distinguishes empty from offline/error

Pending (requires an authenticated session on a real ARM device):
- The concrete TDLib cloud request shapes (`searchChatsOnServer`/`getChats`, `createNewSupergroupChat`,
  `getChatHistory`, `download`/`getFile`) are written defensively against **TDLib 1.8.64**, whose
  generated `TdApi` surface must be finalized on-device; until then the cloud data source returns
  honest "not available" results and the UI shows a truthful setup/unavailable state (no faked cloud).

### 🔜 Later phases

Backup engine (queue, WorkManager, hashing, dedup) · Recognition & reinstall recovery · Polished Photos interactions · Albums · Viewer · Map/Restore · Settings & security hardening · Full integration.

See [`PRD.md`](PRD.md) for the complete product specification and the 12-phase plan.

---

## Project structure

```
app/src/main/java/com/telepix/
├── MainActivity.kt              # Single-activity Compose host
├── TelepixApplication.kt        # Owns the DI container + Coil image loader
├── di/                          # Lightweight manual dependency container
├── navigation/                  # Destinations + NavHost + viewer route
├── domain/media/                # LocalMedia, MediaType, PhotosItem, day grouping
├── data/media/                  # MediaStore loader, mapper, PagingSource, repository, observer
├── onboarding/                  # BackupPreference, repository, ViewModel, startup routing
├── permissions/                 # MediaPermissionState + SDK-aware policy + controller
├── settings/                    # ThemeMode, repository, ViewModel (DataStore)
├── telegram/                    # TDLib: controller, session manager, client gateway, key, states
└── ui/
    ├── TelepixRoot.kt            # Startup routing: onboarding vs main app
    ├── TelepixApp.kt            # Scaffold + bottom navigation
    ├── theme/                   # Design system: Color, Type, Shape, Spacing, Theme
    ├── components/              # Reusable Empty/Loading/Error/Header components
    ├── onboarding/              # OnboardingScreen, steps, and reusable onboarding components
    └── screens/                 # photos (real library), cloud, albums, map, settings, viewer
```

---

## Package namespace

`com.telepix` — used consistently across the application ID, namespace, and source packages.

---

## License

To be determined.
