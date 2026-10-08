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
| Cloud backend | Telegram via TDLib *(later phases)* |
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

No Telegram credentials are required for Phases 1–2 — the actual Telegram backend is not
implemented yet, so the build succeeds without any secrets.

### Downloading the debug APK

1. Open the repository's **Actions** tab.
2. Select the latest run on `main` (or your pull request).
3. Scroll to **Artifacts**.
4. Download **`telepix-debug-apk`** and install the APK on your device.

You can also trigger a build manually via **Run workflow** (`workflow_dispatch`).

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

### 🔜 Later phases

Local media library (MediaStore) · Telegram/TDLib · Telepix Cloud · Backup engine · Recognition & deduplication · Polished Photos UI · Cloud/Albums/Viewer · Map/Restore · Settings & security hardening · Full integration.

See [`PRD.md`](PRD.md) for the complete product specification and the 12-phase plan.

---

## Project structure

```
app/src/main/java/com/telepix/
├── MainActivity.kt              # Single-activity Compose host
├── TelepixApplication.kt        # Owns the DI container
├── di/                          # Lightweight manual dependency container
├── navigation/                  # Destinations + NavHost
├── onboarding/                  # BackupPreference, repository, ViewModel, startup routing
├── permissions/                 # MediaPermissionState + SDK-aware policy + controller
├── settings/                    # ThemeMode, repository, ViewModel (DataStore)
├── telegram/                    # TelegramAuthState contract + controller (Phase 2 stub)
└── ui/
    ├── TelepixRoot.kt            # Startup routing: onboarding vs main app
    ├── TelepixApp.kt            # Scaffold + bottom navigation
    ├── theme/                   # Design system: Color, Type, Shape, Spacing, Theme
    ├── components/              # Reusable Empty/Loading/Error/Header components
    ├── onboarding/              # OnboardingScreen, steps, and reusable onboarding components
    └── screens/                 # photos, cloud, albums, map, settings
```

---

## Package namespace

`com.telepix` — used consistently across the application ID, namespace, and source packages.

---

## License

To be determined.
