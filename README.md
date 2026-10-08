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

No Telegram credentials are required for Phase 1 — Telegram functionality is not implemented yet, so the build succeeds without any secrets.

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

### 🔜 Later phases

Onboarding & permissions · Local media library (MediaStore) · Telegram/TDLib · Telepix Cloud · Backup engine · Recognition & deduplication · Polished Photos UI · Cloud/Albums/Viewer · Map/Restore · Settings & security hardening · Full integration.

See [`PRD.md`](PRD.md) for the complete product specification and the 12-phase plan.

---

## Project structure

```
app/src/main/java/com/telepix/
├── MainActivity.kt              # Single-activity Compose host
├── TelepixApplication.kt        # Owns the DI container
├── di/                          # Lightweight manual dependency container
├── navigation/                  # Destinations + NavHost
├── settings/                    # ThemeMode, repository, ViewModel (DataStore)
└── ui/
    ├── TelepixApp.kt            # Scaffold + bottom navigation
    ├── theme/                   # Design system: Color, Type, Shape, Spacing, Theme
    ├── components/              # Reusable Empty/Loading/Error/Header components
    └── screens/                 # photos, cloud, albums, map, settings
```

---

## Package namespace

`com.telepix` — used consistently across the application ID, namespace, and source packages.

---

## License

To be determined.
