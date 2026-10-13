# Telepic

[![Android CI](https://github.com/FakeAbid11/Telepic/actions/workflows/android-ci.yml/badge.svg)](https://github.com/FakeAbid11/Telepic/actions/workflows/android-ci.yml)

A modern, standalone Android photo & video library with **Telegram-powered cloud backup**, built with Kotlin, Jetpack Compose, and Material 3 (Material You).

Telepic reads your device library through `MediaStore` and presents it as a fast, beautiful timeline. It backs your media up to a **private Telegram channel** and lets you browse that cloud library by *preview* — originals are downloaded only when you explicitly ask for them.

> **Local-first photo management with Telegram-powered cloud backup.**

---

## Table of contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Usage](#usage)
- [Configuration](#configuration)
- [Architecture](#architecture)
- [Project status](#project-status)
- [Limitations](#limitations)
- [Testing](#testing)
- [Contributing](#contributing)
- [License](#license)
- [Acknowledgements](#acknowledgements)

---

## Features

**Library & timeline**
- Reads photos, videos, and GIFs from `MediaStore` as one newest-first, paged stream (no full-library scans in memory).
- Adaptive, thumbnail-first grid with stable keys; full-resolution originals are never decoded for the grid.
- Localized relative day headers (Today / Yesterday / weekday / date) resolved in the device time zone.
- Right-side date rail built from the loaded days only — it grows with paging and never force-loads the library.
- Backup status badges (queued / uploading / backed up / failed) from one batched repository snapshot; no per-tile queries.

**Viewer**
- Full-screen viewer for photos, videos, and GIFs with pinch-to-zoom, pan, and double-tap reset.
- Previous/next navigation stays within the originating collection (local or cloud).
- Videos play via Media3 ExoPlayer; animated GIFs decode via Coil.
- Honest "no longer available" state — a missing item is never silently replaced by another.

**Albums & organization**
- **Your own albums**, created from the Albums tab and filled from the Photos timeline: name them, add photos, rename or delete them. The album is a list of the media you chose, so it survives a folder being renamed and is never affected by the filesystem.
- Folder albums built from real MediaStore buckets (newest item as cover, real counts, provider bucket names), shown in the same grid under **Device folders**.
- Album contents reuse the exact Photos pipeline, so an album behaves identically to the timeline.
- Favorites, Archive, and Trash per item — independent of backup state, with undo.
- Permanent delete goes through the Android-sanctioned system consent dialog (`MediaStore.createDeleteRequest`, API 29+).

**Map**
- Interactive OpenStreetMap view (osmdroid, no API key) with marker clustering.
- Photo positions come only from embedded EXIF GPS. **No device-location permission is requested, and no coordinates or EXIF are ever uploaded.**

**Backup**
- Persistent backup queue in Room that survives process death, restart, and reboot.
- Explicit state machine: nothing reaches `BACKED_UP` without a confirmed remote identity from Telegram.
- Crash recovery, bounded retry, cooperative cancellation, and safe app-private staging (originals are never modified or deleted).
- WorkManager-driven with a foreground notification and a network constraint; uploads resume on reconnect.
- **Content-based deduplication** — streaming SHA-256 identity, so the same bytes are never uploaded twice, even after a reinstall.

**Cloud (Telegram)**
- Browse your backed-up media by preview without downloading originals.
- Destination discovery/creation, with posting rights taken from real chat membership — never the channel title.

**Restore**
- Explicit "Save to device" that downloads an original, verifies it, and publishes it into the public gallery.

**Onboarding, permissions & theming**
- Six-step first-run onboarding with persistent state.
- Version-aware media permissions (Android 13+ granular, Android 14 partial "selected photos", legacy storage below) with honest granted / partial / denied / permanently-denied states.
- Dark (default), Light, and System themes, all driven by centralized design tokens.
- Accessibility-minded: merged content descriptions, labeled actions, and Material-standard touch targets.

**Settings, details & security**
- Backup transport controls — Wi-Fi only, charge only, battery-not-low — enforced as WorkManager constraints, so a run simply does not start until the condition holds rather than failing.
- Media preferences — show/hide videos and GIFs in the library views (backups unaffected), and whether videos start muted.
- Media Details for both local and cloud items: kind, format, dimensions, size, duration, folder/path, capture time, camera, the EXIF exposure set (ISO / aperture / shutter / focal length), GPS, backup status and remote identity — with every unavailable field omitted rather than guessed.
- App lock behind your device PIN/pattern/password, with an honest "unavailable" state on a device that has no credential.
- Local and cloud library counts, measured cache sizes with safe cache clearing, and an open-source licence list.

---

## Requirements

**To install and use the app**
- An **ARM** Android device (`arm64-v8a` or `armeabi-v7a`) running **Android 8.0 (API 26) or newer**.
- A Telegram account, if you want cloud backup.
- No Google account, and no device-location permission, is required.

**To build or contribute**
- **No local toolchain is required.** Telepic is a standard Gradle Android project, but the canonical build runs in **GitHub Actions** — you do not need Android Studio, Gradle, or the Android SDK installed to produce an APK.
- A GitHub account, to run the workflow.
- Telegram API credentials, only if you want a login-capable build (see [Configuration](#configuration)).

---

## Installation

### Option 1 — Install the prebuilt debug APK (recommended)

Every push to `main` produces a debug APK as a CI artifact.

1. Open the repository's **Actions** tab.
2. Select the latest successful run on `main`.
3. Scroll to **Artifacts** and download **`telepic-debug-apk`**.
4. Unzip it and install the APK on your device. You may need to allow *Install unknown apps* for your file manager or browser.

> The APK is a **debug** build and is not signed for distribution. Telegram login only works if the
> build was produced with API credentials configured (see below).

### Option 2 — Build it yourself through CI

1. **Fork** the repository (or clone it and push to your own remote).
2. *(Optional)* Add Telegram credentials as repository secrets so login is available — see
   [Configuration](#configuration).
3. Trigger a build by pushing a commit, opening a pull request, or using
   **Actions → Android CI → Run workflow** (`workflow_dispatch`).
4. Download the **`telepic-debug-apk`** artifact from the finished run.

### Verifying Telegram login on a device

Real-device login is a manual, one-time check:

1. Install the APK and complete onboarding.
2. On the **Telegram Login** step, enter your phone number, the login code, and (if enabled) your 2FA password.
3. Confirm the authorized state, restart the app (the session should persist), and test logout / re-login.

---

## Usage

Telepic is an app, not a library, so "usage" is a walkthrough of the main flows.

**1. First launch — onboarding**
Complete the six steps: Welcome → How Telepic Works → Permissions → Telegram Login → Backup Preferences → Ready. Granting media access is what populates the timeline; Telegram login is optional and only needed for cloud features.

**2. Browse your library**
Open **Photos** for the full timeline, or **Albums** to browse by folder. Scroll to page through media, tap the date rail to jump between days, and pull to refresh after adding media outside the app.

**3. Turn on backup**
Choose a backup mode during onboarding or later in **Settings → Backup**:
- **Back up all photos** — discovers and uploads eligible media automatically.
- **Select a folder** — opens a picker listing the device's actual media folders; only the folders you tick are scanned and uploaded. Confirm requires at least one folder, and nothing else is uploaded automatically.
- **Not now** — never uploads automatically.

Use **Back up now** to scan for new media immediately, and **Backup Center** to watch progress, retry, or cancel individual items.

**4. Browse the cloud**
Open **Cloud** to see everything already backed up in your Telegram channel. Tiles load **previews** on demand — browsing never downloads originals.

**5. View and restore**
Tap any photo or video to open the full-screen viewer. For a cloud item, use **Download** to fetch the original for viewing, or **Save to device** to download it, verify it, and publish it into your gallery.

**6. Organize**
In the viewer, mark items as **favorite**, **archive**, or move them to **trash**. Favorites / Archive / Trash are reachable from Albums. Archive hides an item from the main timeline without deleting it; permanent deletion asks for system consent first.

**7. Map your photos**
Open **Map** to see photos that carry EXIF GPS coordinates. Media without location data simply never gets a pin — nothing is guessed.

**8. Settings**
Tap the **gear in the top-right corner** — available on every tab — for account and connection state, backup preferences, transport and media options, the app lock, theme, storage usage, and sign-out.

---

## Configuration

### GitHub Actions secrets

Configure these under **Settings → Secrets and variables → Actions**. All are optional for a debug
build — without them the app still compiles and runs, but Telegram login is unavailable.

| Secret | Required | Purpose |
|---|---|---|
| `TELEGRAM_API_ID` | For login | Numeric API id from your [Telegram developer account](https://my.telegram.org). Injected into `BuildConfig`. |
| `TELEGRAM_API_HASH` | For login | API hash string. Injected into `BuildConfig`. |
| `RELEASE_KEYSTORE_BASE64` | Release only | Base64-encoded keystore for signing a release build. |
| `RELEASE_KEYSTORE_PASSWORD` | Release only | Keystore password. |
| `RELEASE_KEY_ALIAS` | Release only | Signing key alias. |
| `RELEASE_KEY_PASSWORD` | Release only | Signing key password. |

**Secrets are never committed, hardcoded, or logged.** Debug builds without credentials use a safe
placeholder (`api_id = 0`). A **release** build refuses to run at all unless real Telegram credentials
are present, so a secret-less release cannot ship with a permanently dead login. If a non-numeric
`TELEGRAM_API_ID` is supplied, the build fails fast rather than silently falling back to `0`.

### Build configuration

Defined in [`app/build.gradle.kts`](app/build.gradle.kts):

| Setting | Value |
|---|---|
| `applicationId` / `namespace` | `com.telepic` |
| `minSdk` | 26 (Android 8.0) — required by the TDLib artifact |
| `targetSdk` / `compileSdk` | 35 |
| ABI filters | `arm64-v8a`, `armeabi-v7a` (x86/x86_64 excluded) |
| Java / Kotlin target | 17 |
| Release build | R8 minification + resource shrinking, with project ProGuard rules |
| Lint | `abortOnError = true` |

Dependency versions are centralized in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

### In-app settings

| Setting | Location | Options |
|---|---|---|
| Theme | Settings → Appearance | Dark (default), Light, System default |
| Backup mode | Settings → Backup | Back up all photos / Select a folder / Not now |
| Manual backup | Settings → Backup | **Back up now** (enabled once a backup mode is configured) |
| Cache | Settings → Storage | Measured Telegram session and staging cache sizes; **Clear backup cache** |
| Account | Settings → Account | Telegram account, connection state, backup channel, sign-out |

---

## Architecture

Telepic is a **single-module, single-activity** app with a **layered, unidirectional** data flow:

```
UI (Jetpack Compose)
  ↓
ViewModel (StateFlow / Paging 3)
  ↓
Domain (pure models + state machines)
  ↓
Repository (interface + Default implementation)
  ↓
Data sources: MediaStore · Room · TDLib · File system · DataStore
```

- **UI** is 100% Jetpack Compose + Material 3 — there are no XML layouts.
- **Dependency injection** is a small hand-written container ([`di/AppContainer.kt`](app/src/main/java/com/telepic/di/AppContainer.kt)) using `by lazy` singletons — no Hilt or Koin. It is deliberately shaped so a future DI framework can be introduced without touching callers.
- **Every repository is an interface with a `Default*` implementation**, which is the seam the test suite substitutes.

### Tech stack

| Area | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 (Material You) |
| Async | Kotlin Coroutines + Flow / StateFlow |
| Navigation | Navigation for Compose, with a typed `MediaSource` across the viewer boundary |
| Local media | Android `MediaStore` + Paging 3 |
| App-data storage | Room (KSP) |
| Settings storage | Jetpack DataStore (Preferences) |
| Image loading | Coil — thumbnail-first, video-frame decoding, animated GIFs |
| Video playback | Media3 ExoPlayer |
| Map | osmdroid + OpenStreetMap tiles (Apache-2.0, no API key) |
| EXIF / GPS | `androidx.exifinterface` |
| Recognition / dedup | Streaming SHA-256 via `MessageDigest` (no third-party hashing library) |
| Cloud backend | Telegram via Java TDLib (`org.drinkless.tdlib`), ARM-only |
| Background work | WorkManager, with a foreground backup worker |

### Project structure

```
app/src/main/java/com/telepic/
├── MainActivity.kt                 # Single-activity Compose host
├── TelepicApplication.kt           # Owns the DI container + Coil image loader
├── di/                             # Lightweight manual dependency container
├── navigation/                     # Destinations, NavHost, typed viewer routes
├── domain/
│   ├── media/                      # LocalMedia, MediaType, PhotosItem, day grouping
│   ├── cloud/                      # CloudMedia, destination, status, upload contract
│   └── backup/                     # BackupState + state machine, BackupItem, recognition
├── data/
│   ├── media/                      # MediaStore loader, mapper, PagingSource, repository, observer
│   ├── cloud/                      # Cloud repository, TDLib data source, Room (destination/manifest)
│   ├── backup/                     # Queue, coordinator, repository, staging, WorkManager
│   │   ├── hash/                   # Streaming SHA-256 ContentHasher
│   │   ├── db/                     # backup_queue entity + DAO (content-hash indexed)
│   │   └── work/                   # BackupWorker, scheduler, worker factory
│   ├── organization/               # Favorites / Archive / Trash state + local deleter
│   └── restore/                    # Restore pipeline + MediaStore publisher
├── onboarding/                     # BackupPreference, repository, ViewModel, startup routing
├── permissions/                    # SDK-aware media-permission policy + controller
├── settings/                       # ThemeMode, repository, ViewModel (DataStore)
├── telegram/                       # TDLib controller, session manager, gateway, key, states
└── ui/
    ├── TelepicRoot.kt              # Startup routing: onboarding vs main app
    ├── TelepicApp.kt               # Scaffold + bottom navigation
    ├── theme/                      # Design system: Color, Type, Shape, Spacing, Theme
    ├── components/                 # Reusable Empty / Loading / Error / Header components
    ├── onboarding/                 # OnboardingScreen, steps, reusable components
    └── screens/                    # photos, cloud, albums, map, settings, viewer, backup, organization
```

### Database

Room is at **version 7**, with explicit migrations and **no destructive fallback**:

| Table | Purpose | Introduced |
|---|---|---|
| `cloud_destination` | The single validated Telepic Backup channel | v1 |
| `cloud_media_manifest` | Cloud media index, keyed by `chatId + messageId` | v1 |
| `backup_queue` | Source of truth for backup state | 1 → 2 |
| `media_organization` | Favorites / Archive / Trash state | 3 → 4 |
| `media_location` | EXIF-GPS location cache | 4 → 5 |
| `custom_album` | User-created albums: name + cover hint | 6 → 7 |
| `custom_album_media` | Album membership, keyed `(albumId, localMediaId)`, cascading on delete | 6 → 7 |
| `media_location_scan` | Which items the map has already examined for GPS — including those that carry none | 7 → 8 |

Migrations are `1 → 2`, `2 → 3`, `3 → 4`, `4 → 5`, `5 → 6`, `6 → 7`, and `7 → 8`. Each has a migration test that builds the previous version and proves existing rows survive. Note that `TelepicDatabase` is always the latest version, so each of those tests chains *every* migration from its starting version — a version bump therefore touches all of them.

---

## Project status

Telepic is a **debug-milestone** project. Every feature is implemented and verified by CI (compile +
unit tests + lint), but the Telegram and device-bound surfaces have **not** been exercised on a real
device yet.

| Feature | Status |
|---|---|
| Onboarding (6 steps) | Complete |
| Version-aware media permissions | Complete |
| Photos timeline (adaptive grid, day headers, date rail, paging) | Complete |
| Backup status indicators | Complete *(real-upload path device-gated)* |
| Albums + album contents | Complete |
| User-created albums (create / add / rename / delete) | Complete |
| Full-screen viewer (photo / GIF / video) | Complete *(playback device-validated only)* |
| Map (EXIF GPS, marker clustering) | Complete *(rendering device-validated only)* |
| Favorites / Archive / Trash | Complete *(system delete consent device-validated only)* |
| Telegram authentication (TDLib) | Code complete *(live login device-gated)* |
| Cloud browse + preview | Code complete *(live calls device-gated)* |
| Backup engine (queue, state machine, WorkManager) | Code complete *(live upload device-gated)* |
| Recognition & deduplication (SHA-256) | Code complete *(remote half device-gated)* |
| Restore / save-to-device | Code complete *(download + MediaStore write device-gated)* |
| Settings | Complete *(app lock + cache clearing device-gated)* |
| Media Details | Complete |
| Search | Not implemented (deliberately deferred) |
| App lock | Complete *(device-credential prompt device-gated)* |

### Device validation status

Telepic is built and unit-tested entirely in CI, but its Telegram and device-bound surfaces can only
be verified on a **real ARM device with an authenticated Telegram session**. None of the following has
been performed yet — in every case the code path exists and is unit-tested with fakes, but it has
never run against the real thing.

| Surface | Why it is device-gated |
|---|---|
| TDLib request/response shapes | Written against `io.github.tdlib-android:core:0.1.1` (TDLib 1.8.x); the generated `TdApi` surface must be finalized on-device |
| Telegram auth round-trip | Needs a real phone number, code, and optional 2FA — or, for an account registered by email, the emailed code (and possibly a Telegram Premium purchase) |
| Cloud browse / preview / original download | Needs a populated channel and a live session |
| Backup upload (`SendMessage` + `InputFile`) | Same bring-up; until then `TdLibCloudDataSource.upload` throws an honest transient failure and items stay `QUEUED`/`WAITING` — never falsely `BACKED_UP` |
| MediaStore write (restore) and delete consent | Provider behaviour and the system consent dialog are device-only |
| ExoPlayer playback, osmdroid rendering, Coil GIF decode | Rendering is device-only |
| App lock (`KeyguardManager` device-credential prompt) | The credential prompt and the activity `ON_STOP`/`ON_START` round trip are device-only |

### Roadmap

Next up: **full-device integration & hardening** (Phases 11–12), plus **Search**.

<details>
<summary>Development history (phase by phase)</summary>

A chronological log of what each phase built. Two status labels are used:

- **complete** — implemented, unit-tested, and CI-verified.
- **code complete; … pending device validation** — implemented and unit-tested with fakes, but the live/device path has not run.

### Phase 1 — Foundation & Material You Design System *(complete)*

- Valid Android Kotlin project using Jetpack Compose + Material 3
- Telepic branding (app name, blue/black/white identity, adaptive launcher icon)
- Centralized **Telepic design system**: color, typography, shape, and spacing tokens
- **Dark (default)**, **Light**, and **System default** themes with a persisted preference
- Four navigation tabs in a Material 3 bottom bar — **Photos · Cloud · Albums · Map** — with **Settings** in the top-right corner of every tab (it is a destination you visit, not one you stay in, so it does not take a quarter of the bar)
- Reusable UI components: `EmptyState`, `LoadingState`, `ErrorState`, `ScreenHeader`
- Foundation settings screen (Account, Backup, Appearance, Storage, About)
- Layered architecture foundation with a lightweight manual DI container
- Unit-test foundation (navigation, theme, settings persistence)
- GitHub Actions CI producing a debug APK artifact

### Phase 2 — Onboarding & Permissions *(complete)*

- **Six-screen first-run onboarding**: Welcome · How Telepic Works · Permissions · Telegram Login · Backup Preferences · Ready
- Persistent onboarding state (completion + backup preference) via DataStore; completed users launch straight into the main app, fresh installs start onboarding
- Polished Material 3 onboarding: subtle step-progress indicator, back navigation, vector-only illustrations (no stock imagery), and short screen transitions
- **Version-aware media permissions**:
  - Android 13+ — granular `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`
  - Android 14+ — partial "selected photos" access (`READ_MEDIA_VISUAL_USER_SELECTED`)
  - Android 12 and below — the legacy storage model
  - Honest granted / partial / denied / permanently-denied states with an open-Settings recovery path
  - No unrelated permissions are requested
- Backup preference selection (`BACKUP_ALL` / `SELECT_FOLDER` / `NOT_NOW`), persisted for later phases
- **Telegram Login UI foundation**: a real state contract (`TelegramAuthState`) and a controller abstraction that Phase 4 (TDLib) implements — no fake authentication, accounts, or channels
- Reusable onboarding components (scaffold, progress, illustration, choice card), Compose UI tests (Robolectric), and JVM unit tests for persistence, routing, and permission logic

### Phase 3 — Local Media Library *(complete)*

- **Real local library from MediaStore** — photos, videos, and GIFs are read from `MediaStore.Files` as a single, newest-first, paged stream (no full-library scans in memory, no filesystem-path identity — content URIs are used)
- **Domain model** `LocalMedia` + type-safe `MediaType` (PHOTO / VIDEO / GIF), normalized from MediaStore MIME metadata, carrying metadata for later features (bucket id/name, relative path, dimensions, size, dates, duration)
- **Paging 3** (`PagingSource` → `Repository` → `ViewModel` → `LazyVerticalGrid`) for fast, incremental, memory-efficient loading with stable keys and cancellation
- **Thumbnail-first grid**: adaptive columns, rounded tiles, video play + duration indicators, GIF badges, **Coil** image loading (with video-frame decoding) — full originals are never decoded for the grid
- **Chronological date grouping** with full-width day headers and a subtle **right-side date rail** foundation for quick timeline navigation
- **Permission-aware** (reuses the Phase 2 model): queries only when access is available; honest granted / partial / denied / blocked states, each with appropriate UI and a recovery path
- Distinct **Loading / Empty / Permission / Error** states with retry, plus MediaStore **change detection** via a lifecycle-safe `ContentObserver` that invalidates the paged data
- Viewer **navigation contract** (passes only a stable media id); the full Viewer is a later phase
- No backup, hashing, Telegram, or cloud is triggered by viewing media

### Phase 4 — Telegram / TDLib Integration *(code complete; live login pending device validation)*

- **Java TDLib** bindings (`org.drinkless.tdlib.Client` / `TdApi`) via the `io.github.tdlib-android:core` AAR — **no JSON client, no hand-written JNI shim**
- **ARM-only** native libraries (`arm64-v8a`, `armeabi-v7a`) selected via `ndk.abiFilters`; the APK excludes x86/x86_64. `minSdk` raised to **26** (required by the TDLib artifact)
- Clean layer boundary: **UI → `TelegramAuthController` → `TelegramSessionManager` → `TdLibClientGateway` → TDLib**. The UI never touches `Client` directly
- Full authentication state machine mapping **every** TDLib authorization state to `TelegramAuthState` (initializing → phone number → code → optional 2FA password → authorized; closing/closed; errors) — including the states TDLib uses for accounts registered by email (`WaitEmailAddress`, `WaitEmailCode`) and `WaitPremiumPurchase`
- **Persistent, app-private TDLib database** (`noBackupFilesDir`) with a **Keystore-wrapped encryption key** (AES-256-GCM) — login survives app restart and process death, and is never stored on shared storage
- Real onboarding Telegram UI: phone / code / 2FA inputs, account identity on success, and recoverable errors (invalid credentials, **flood wait**, network) — **never fakes a login**
- **Email sign-in is a real path, not a dead end.** `WaitEmailAddress` renders an email field and `WaitEmailCode` a code field with a resend action, so an email-registered account can finish signing in. Both previously fell through to the `Initializing` spinner — a screen with no input and no button, and therefore no way forward. A rejected address or code keeps its field and shows the reason inline, and a **late or duplicated reply is dropped rather than re-opening the input**, so a session that has already signed in cannot be dragged back.
- **`WaitPremiumPurchase` says so honestly.** TDLib can gate a login behind a Telegram Premium purchase; Telepic has no store front, so instead of spinning forever it states that the purchase must be completed in the official Telegram app and offers the support contact TDLib supplied.
- **Non-blocking startup**: TDLib initializes without ever delaying or crashing the local Photos flow
- **No sensitive data in logs**: the API hash, phone number, authentication code, 2FA password, and the encryption key cannot leak through logs. Everything the app logs goes through one seam (`diagnostics/TelepicLog`), which documents those values as forbidden and names only the subsystem that degraded.

### Phase 5 — Telegram Cloud *(code complete; live cloud calls pending device validation)*

- **Room** introduced (KSP) with an explicit schema and **no destructive fallback**: `cloud_destination` (a single validated row per provider) and `cloud_media_manifest` (stable identity `chatId + messageId`, plus a hash field reserved for Phase 7)
- Clean layering: **Cloud screen → `CloudViewModel` → `CloudRepository` → `CloudDataSource` → TDLib**, reusing the **single** Phase 4 client/session/gateway (never a second Telegram client)
- Domain models: `TelepicCloudDestination`, `CloudMedia` (IMAGE / VIDEO / GIF, GIF kept distinct), `CloudPreview`, `LocalDownloadedMedia`; and a rich `CloudStatus` (initializing / connecting / refreshing / ready / empty / offline / not-authenticated / destination-missing / invalid / error)
- `ChatValidator` enforces a **safe destination** (an accessible channel the account can post to, with the expected title) — never the title alone, so a same-name channel owned by someone else is rejected
- Repository orchestration (discover → create-if-needed → validate → persist → refresh → manifest upsert with de-dup → state), race-guarded, cancellable, on background dispatchers; **no uploads**
- A functional **Cloud screen** (adaptive grid, video/GIF indicators, preview-on-demand) that **never downloads originals to browse** and distinguishes "empty" from "offline/error"

### Phase 6 — Reliable Backup Engine *(code complete; live upload pending device validation)*

- **Persistent backup queue** in Room (`backup_queue`) — the source of truth for backup state. It survives process death, app restart, and reboot. The **unique `localMediaId`** (the MediaStore id, never the filename) prevents duplicate active rows, so a repeated `enqueue(media)` is idempotent.
- **Explicit state machine** (`NOT_BACKED_UP · QUEUED · PREPARING · UPLOADING · BACKED_UP · FAILED · CANCELLED`, plus the non-terminal `WAITING_FOR_NETWORK` / `WAITING_FOR_AUTH`). The **core invariant** — nothing reaches `BACKED_UP` except from `UPLOADING` — is encoded once in `BackupStateMachine` and unit-tested. The only success path is: upload → Telegram confirms → **remote identity persisted → THEN `BACKED_UP`** (the identity write is atomic with the state).
- **Room migration 1 → 2**: the Phase 5 database is preserved by an explicit, non-destructive migration. A real migration test builds a version-1 database and verifies Phase 5 rows survive and the queue becomes usable.
- **Crash recovery**: on worker start, rows left mid-flight (`PREPARING` / `UPLOADING`) with no persisted remote id return to `QUEUED`; a row that *does* carry a remote id is finalized. The queue never assumes an upload failed just because the process died, and never claims success without confirmation.
- **Bounded retry**: transient failures (network / Telegram / TDLib) wait for network and consume a capped retry; permanent failures (missing/unsupported media, rejected upload) fail immediately and can be retried by the user. `retryCount`, `lastError`, and `updatedAt` are persisted.
- **Cooperative cancellation** — a cancelled item never becomes `BACKED_UP` unless Telegram already confirmed it; an in-flight item is left for recovery rather than lied about.
- **Safe staging** — a MediaStore content URI is streamed (fixed-size buffer, never `readBytes()`) into app-private storage, used for the upload, and cleaned up in a `finally`. The **original is never modified or deleted**.
- **WorkManager orchestration**: `BackupWorkScheduler` → `BackupWorker` (a `CoroutineWorker`) with a **network constraint** and an honest **foreground notification** ("Uploading n of m"). Offline just leaves rows `QUEUED` and resumes on reconnect; the engine never depends on a screen staying open.
- **Cloud upload contract** — the engine calls `CloudRepository.uploadMedia(request, onProgress)` and knows **no TDLib details**; the real Telegram request shapes stay behind the data layer.
- **Backup preference honored**: `BACKUP_ALL` incrementally discovers and enqueues eligible media; `NOT_NOW` never auto-enqueues; `SELECT_FOLDER` scans **exactly the folders the user chose** — one bucket-scoped loader per selected folder — and an empty selection backs up nothing, which is the honest equivalent of `NOT_NOW` rather than a silent fallback to the whole library.
- **Real folder picker, scoped to what will actually be uploaded** — choosing "a folder" opens a multi-select list of the device's media folders (MediaStore buckets), identified by the stable **bucket id**, never by name. The list and its per-folder counts come from the **backup** scope rather than the library view, so a folder whose media the timeline currently hides is still offered, and the count beside it matches what the pass discovers. Where two folders share a name (`DCIM/Camera` vs `Pictures/Camera`), the folder's path is shown so the choice is unambiguous.
- **Backup Center** foundation (reached contextually from Settings): status, pending / uploading / completed / failed counts, and per-item retry / cancel — no upload polish, no full restore.
- **Phase 7 ready**: the queue keeps nullable `contentHash` / timestamps and the manifest keeps its reserved hash field, but **no SHA-256 recognition or dedup is performed** here.

### Phase 7 — Backup Recognition & Deduplication *(code complete; remote recognition pending device validation)*

- **SHA-256 content identity** — streaming, cancellable hashing on `Dispatchers.IO` via `MessageDigest("SHA-256")` (no third-party hashing lib, no `readBytes()` of whole files), producing a consistent **64-char lowercase hex** digest persisted across the queue, the remote manifest, and tests. Recognition identity is **content — never filename, id, date, or album** (unit-tested: same bytes under different filenames are duplicates; same filename with different bytes are not).
- **Hash cache with correct invalidation** — a stored hash is reused only while **both** content size and modified timestamp are unchanged; a change in either forces a re-hash. A MediaStore URI is never trusted as the cache key on its own.
- **Content-change-during-hash guard** — the hasher re-checks the provider size after streaming and discards a torn hash rather than persisting one for the wrong file version.
- **Remote manifest is queryable by hash** — `findByContentHash` / `findByContentHashAndSize` (both backed by an explicit `contentHash` **index**), returning a deterministic earliest message id when several equivalent records exist, so recognition never triggers a new upload just because duplicates already exist. The whole manifest is never scanned into memory.
- **Room migration 2 → 3** — adds `contentSizeBytes`, `hashedAt`, and a `contentHash` index to `backup_queue`, plus a `contentHash` index to `cloud_media_manifest`; **no destructive fallback**. A migration test builds a real version-2 database, reopens it at version 3 through the explicit migration, and proves Phase 5/6 rows **and existing hashes survive**.
- **Recognition before enqueue** — the coordinator recognizes each item first: `AlreadyBackedUp` and `Pending` never create a queue row; `NeedsBackup` enqueues carrying the fresh hash (persisted up front); a hash/read failure surfaces `Unavailable` and still attempts backup (never silently skipped, never falsely "backed up"). Cross-identity dedup stops identical bytes reached through two local ids from being queued twice.
- **Reinstall-proof by design** — recognition matches on content hash against the remote manifest, so a fresh install with new MediaStore ids recognizes existing Telegram media without a second upload. (The model and DAO/query boundaries for the future reinstall UI are in place; the UI itself is not.)
- **Phase 6 preserved entirely** — state machine, WorkManager, retry, cancellation, crash recovery, staging, and the `CloudRepository` upload abstraction are untouched; recognition feeds the existing queue rather than replacing it. On a real upload result the content hash is persisted into the manifest as part of success.
- **No TDLib internals leak into recognition** — the engine knows only `CloudRepository`, the manifest, and a remote identity; never `TdApi` / `SendMessage` / `InputFile`. No JSON or JNI was introduced.

### Phase 8 — Photos UI, Timeline, Date Rail & Backup Status *(complete)*

- **Top app bar** with the Photos title and a **Settings** action that navigates to the existing Settings destination (single-top, identical to tapping the Settings tab). No Search was added.
- **Adaptive, thumbnail-first grid** (`LazyVerticalGrid`, `GridCells.Adaptive`) over the Phase 3 Paging 3 stream with **stable keys**, square center-cropped cells, and no full-resolution decode.
- **Localized relative day headers** — Today / Yesterday / weekday / "October 8" / full date via `java.time` and the device locale. Grouping identity is a stable ISO **day key** (never the display text) resolved in the **device time zone**, so a photo never shifts days. No English-only hardcode.
- **Right-side date rail** built from the *loaded* day headers only — it grows with paging and never force-loads the library — with the active day emphasized and tap-to-scroll to the loaded group.
- **Backup indicators from the repository, never inferred by the UI** — `BackupStatusRepository` maps the Room queue into one batched `Map<mediaId, MediaBackupVisualState>` snapshot; tiles read `states[id] ?: NONE`. `BACKED_UP` reflects only confirmed remote success. States shown: QUEUED / UPLOADING (genuinely indeterminate — no fabricated percentages) / BACKED_UP / FAILED; NONE renders nothing, to keep the grid media-first.
- **No per-tile cost** — a tile runs **no Room query, no hashing, no network call**; status comes from the shared snapshot map. The content hash stays a recognition-only identity and is **never** the UI/navigation key (the MediaStore id remains it).
- **Paging UX** — a lightweight skeleton on first load, a small end-of-list spinner when appending, an inline retry on append failure, and a refresh failure that **keeps loaded photos** with a retry affordance (never a full-screen empty error).
- **Pull-to-refresh** wired to the repository's MediaStore invalidation only — refreshing Photos does **not** trigger a remote Telegram sync.
- **Permission honesty** — full / **partial** / denied / permanently-denied states stay distinct; a subtle partial-access banner states that the library is limited to the selected media. MediaStore change observation (Phase 3) is preserved; local deletion never touches the Telegram cloud copy.
- **Theming** — dark (default), light, and system all render through the existing Material 3 design tokens; new spacing tokens (`railGutter`, `railWidth`) were added rather than scattered dp values.
- **Accessibility** — meaningful merged content descriptions on tiles (type + localized date + duration + backup state), a labeled Settings action, a labeled date rail, and a rail touch column at Material's target width.

> **Known boundary at the time:** the full Viewer, Media Details, Albums, Map, Favorites/Archive/Trash, and Search were not implemented yet. Device testing and the real-Telegram backup-indicator path remained NOT TESTED (live upload is device-gated); indicators were verified with repository states, not real uploads.

### Phase 9 — Cloud, Albums & Full-Screen Viewer *(code complete; live cloud content pending device validation)*

- **Typed media-source navigation** — a `MediaSource` (Local = MediaStore id, Cloud = `chatId` + `messageId`) is the *only* identity that crosses the Viewer boundary, so a Telegram message is never mistaken for a local id and back navigation returns to the originating screen. Photos and album tiles open `viewer/local/{id}`; Cloud tiles open `viewer/cloud/{chatId}/{messageId}`.
- **Full-screen Viewer** for photos, videos, and GIFs: pinch-to-zoom / pan with double-tap reset (pure, unit-tested `ZoomState` math), a labelled source (on-device vs Telegram cloud), the item's date, and previous/next **within the originating collection** — local neighbours come from two cheap `_ID`-bounded MediaStore queries, cloud neighbours from the Room manifest's order. No whole-library load for adjacency.
- **Missing media is honest** — an unknown local id, or a manifest-absent `(chatId, messageId)`, renders a "no longer available" state and never substitutes another item.
- **Video playback via Media3/ExoPlayer** (`PlayerView`), isolated in one opt-in component with proper release; local videos play their content URI directly. **Cloud video has no fake streaming** — there is no verified TDLib streaming path, so a cloud video plays only its explicitly-downloaded original and otherwise shows the honest download step.
- **Cloud originals download only on explicit request** — opening the Viewer never fetches the original; the download action runs `CloudRepository.downloadOriginal`, and success is shown only with a verified local file path. Until the TDLib `DownloadFile` / `GetFile` shapes are device-finalized the result is `Unavailable` (never a fabricated file), and previews remain the browse mechanism.
- **Local Albums from real MediaStore buckets** — `AlbumGrouper` (pure, unit-tested) groups a projection-limited, newest-first query by `BUCKET_ID`: newest item as cover, real counts, provider bucket names with a neutral fallback (never a filename), null-bucket rows skipped. Permission-aware, with distinct loading / genuinely-empty / error states, like Photos.
- **Album contents reuse the exact Photos pipeline** — the same `MediaPagingSource`, mapper, projection, and ordering filtered by bucket, rendered through the same `MediaGrid` (day headers, date rail, backup badges), so an album behaves identically to the timeline. Paging 3 throughout; no full-album memory load.
- **Cloud grid → Viewer wiring** — tapping a cloud tile opens the shared Viewer by remote identity (previously a static grid); animated **GIF playback in the Viewer** via a Coil GIF decoder registered on the app `ImageLoader`.
- **Phases 1–8 preserved** — onboarding, theme, permissions, Photos timeline/date rail/backup status, the backup engine, recognition/dedup, and all prior contracts and tests are untouched; the old untyped viewer route was replaced by the typed source, and nothing else in navigation changed.

### Phase 10 — Map, Media Organization, Restore & Real Telegram Integration *(code complete; live Telegram, map rendering, and MediaStore write/delete pending device validation)*

This phase attacked the long-standing device-gated Telegram blocker first, then completed the interactive map, Favorites/Archive/Trash, and restore.

**Part A — Real Telegram cloud (request shapes finalized against the resolved API)**

- The defensive stubs were replaced with **actual TDLib calls** built against the exact `io.github.tdlib-android:core:0.1.1` (TDLib 1.8.x) `TdApi` surface — verified by inspecting the bundled generated classes, not guessed from another version: `SearchChatsOnServer` (with a channel type filter), `GetChat` / `GetChatMember`, `CreateNewSupergroupChat` (a channel), `GetChatHistory` (newest-first paging), synchronous `DownloadFile` with a verified local path, `SendMessage` with media-appropriate `InputMessagePhoto` / `InputMessageVideo` / `InputMessageAnimation` over `InputFileLocal` from the staged file, and `GetMessage` (to poll a sent message's sending state).
- Destination posting rights come from the account's **real chat membership** (creator, or admin with the post right) — never the channel title. The single shared TDLib client is reused (no second client). An upload is reported as a confirmed remote identity **only after the sent message's sending state clears to success** (polled, not raced): a rejected send is permanent, a flood-wait / 5xx is transient, and a confirmation timeout is treated as transient so the queue can retry — a queue item is never marked `BACKED_UP` on a guess.
- JVM unit tests construct and map real `TdApi` value objects (no native library) and drive the data source through a **fake gateway** — request construction, response mapping, discovery→membership, history paging, download verification, and upload confirm/reject/flood/timeout.
- **Live Telegram behaviour is device-gated and NOT PERFORMED here.** What is done: the concrete requests and response mapping are implemented and unit-tested. What is missing: a real authorized round-trip on a device. Nothing is claimed to work live.

**Part B — Interactive OpenStreetMap photo map**

- osmdroid (Apache-2.0, no API key) rendered via a Compose `AndroidView`, with proper OSM attribution and a public MAPNIK tile source — documented as not suitable for unlimited production traffic.
- Photo positions come **only from embedded EXIF GPS**, parsed by a pure, unit-tested `GpsCoordinates` (DMS + hemisphere → decimal, range-validated, malformed/non-finite rejected). **No coordinate is ever fabricated** — media without valid GPS simply never gets a pin, and non-geotagged photos stay in Photos. **No device-location permission is requested, and no coordinates or EXIF are uploaded.**
- Extracted coordinates are cached in a new `media_location` Room table (explicit, non-destructive migration 4 → 5) so full-resolution EXIF is not re-read on every render; a pure `MapClusterer` groups nearby markers while preserving every item id. Selecting a marker opens the shared Viewer by that MediaStore id. `INTERNET` / `ACCESS_NETWORK_STATE` were added for tiles.
- **The scan remembers what it has already looked at** (`media_location_scan`, additive migration 7 → 8). Previously only coordinate-bearing items were written down, so a file with no GPS could never be recognised as "already checked": its EXIF was re-read on every open, and because the sweep was capped at 40 pages that whole budget went on the same prefix — leaving a geotagged photo further back **permanently unreachable** and the screen stuck on its loading state. Every examined item is now recorded, so a sweep costs one EXIF read per file *once* and can afford to walk the entire library. The screen also stops waiting on the sweep after a short window, rendering the map with an inline "scanning" hint instead, so a long first run can never present as a hang.
- GPS parsing, clustering, and the cache are CI-tested with fakes. **Map pan/zoom/tiles/marker rendering are device-only and NOT TESTED**, and **no offline tile caching is implemented** (tiles require connectivity).

**Part C — Favorites, Archive & Trash**

- Durable per-item state in a new `media_organization` Room table (explicit migration 3 → 4), keyed by the MediaStore id — **independent of backup state** (favoriting/archiving/trashing never touches the queue, remote identity, or cloud status; unit-tested).
- **Favorite** stays visible in the library; **Archive** is removed from the primary timeline via **DB-side** filtering (`_ID NOT IN (...)` in the MediaStore and album-bucket queries) but is stored and restorable; **Trash** is hidden from normal browsing until recovered or permanently deleted.
- Viewer favorite/archive/trash actions, plus contextual Favorites / Archive / Trash collections reached from Albums (no new bottom-navigation destinations), with undo (unfavorite / unarchive / restore).
- **Permanent delete** goes through the Android-sanctioned **`MediaStore.createDeleteRequest`** system consent (API 29+), is confirmation-gated, and only forgets an item after a consented success. The app-level trash flag is kept **distinct** from an actual file removal (`deletedFromStore`), and the legacy per-file consent path (API 26–28) is documented as unsupported rather than faked. Repository persistence, migration, timeline hidden-id computation, and the collection ViewModel are CI-tested; the **system delete consent is device-only and NOT TESTED**.

**Part D — Restore / download original to device**

- `RestoreRepository`: explicit download of a cloud original → **SHA-256 verification** of the bytes (against the trusted manifest hash when present) → publish into public media storage via a `MediaStore` `ContentResolver` insert + streamed copy (correct MIME, scoped `Pictures|Movies/Telepic` path, `IS_PENDING` commit, no overwrite of unrelated files, partial-write cleanup). A **"Save to device"** action lives in the Viewer for cloud items; success is reported only after a committed publication, and every earlier step is a distinct, honest failure.
- Publishing needs **write access on API ≤ 28** (the scoped-storage columns only exist on API 29+), so the app declares `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="29"` and requests it alongside media access on those versions only.
- CI-tested with a fake cloud source and a fake publisher (success, unavailable/missing, verify mismatch, low-storage, publish failure). **Real Telegram download and the MediaStore write are device-gated and NOT TESTED**; byte-for-byte preservation of a restored *image* is not claimed (Telegram's photo pipeline may recompress), consistent with the upload note.

**Cross-phase:** Phases 1–9 are preserved; the new tables are additive (non-destructive migrations); ARM-only packaging and `minSdk 26` are unchanged; and GitHub Secrets remain the only credential source.

</details>

---

## Limitations

- **Device validation is outstanding.** The entire live Telegram path (auth, cloud browse/preview, upload, download), MediaStore write/delete consent, all rendering (ExoPlayer, osmdroid, GIF decode), and the app lock's credential prompt are unverified on a device. See [Device validation status](#device-validation-status).
- **App lock re-locks on a grace window, not instantly.** Returning to the foreground within 30 seconds does not re-prompt, because launching a system activity (the photo picker, the delete-consent dialog, the Telegram link) stops this activity and an instant re-lock would interrupt the user's own flow. It locks on cold start and after longer background trips; see `AppLockTimer` for the exact rule.
- **Hiding videos or GIFs does not change what gets backed up.** The Media preferences filter the library *views* only; backup eligibility stays governed by the backup preference. The folder picker therefore lists folders by what a backup would take, not by what the timeline happens to show.
- **No instrumented tests.** All tests are JVM/Robolectric; there is no `app/src/androidTest` source set, so nothing device-bound is covered by automated tests.
- **Logging is minimal.** One seam exists (`diagnostics/TelepicLog`), and it is wired only at the fallbacks that used to be invisible — an aborted backup pass, a denied foreground notification, a swallowed TDLib request failure, a refused MediaStore delete. There is no analytics, no crash reporting, and no log of the happy path, so a device-gated failure is still easier to reproduce than to diagnose from the outside.
- **No offline map tiles.** The map requires connectivity.
- **`SELECT_FOLDER` is keyed to MediaStore buckets.** A folder *is* a bucket, and the bucket id is derived from the folder's path — so a folder renamed or moved outside the app drops out of the selection and has to be picked again. Only folders that currently hold photos or videos are offered (an empty folder produces no bucket), and on Android 9 and below there is no `RELATIVE_PATH`, so two folders sharing a name cannot be told apart in the picker there.
- **Restored images are not byte-for-byte identical** to the source, because Telegram may recompress photos; only the *restored* bytes are hashed.
- **The open-source licence list is hand-maintained** (`OpenSourceLicenses.kt`), so it has to be updated whenever a runtime dependency changes. It mirrors `gradle/libs.versions.toml` rather than being generated.
- **ARM-only**, `minSdk 26`.
- **No shipping release is built.** The `release-smoke` CI job compiles the R8/minified configuration on every push (so shrinking cannot rot unnoticed), but only the debug APK is published as an artifact.

---

## Testing

All tests are JVM-based and run in CI; no instrumented tests exist yet.

```bash
# Unit tests (JUnit 4 + kotlinx-coroutines-test + Robolectric)
./gradlew testDebugUnitTest

# Android lint
./gradlew lintDebug

# Assemble the debug APK
./gradlew assembleDebug
```

These are exactly the commands the [CI workflow](.github/workflows/android-ci.yml) runs. If you build
locally, you will need JDK 17 and the Android SDK; if you don't, CI will run them for you on push.

What the suite covers: the backup state machine, all Room migrations, hashing and recognition/dedup,
viewer race conditions, navigation, permission policy, persistence, and the pure logic seams
(`MediaDay`, `AlbumGrouper`, `MapClusterer`, `GpsCoordinates`, `ZoomState`). Device-bound paths
(MediaStore write/delete, TDLib round-trips, ExoPlayer, osmdroid) are **not** covered.

---

## Contributing

Contributions are welcome. Because Telepic builds in CI, you do not need a local Android toolchain to
contribute.

**Workflow**

1. Fork the repository and create a branch for your change.
2. Make your change, following the conventions below.
3. Push (or open a pull request). **CI must be green** — it runs `testDebugUnitTest`, `lintDebug`, and `assembleDebug`.
4. Open a pull request describing what changed and why.

**Conventions to follow**

- **Match the existing architecture.** Respect the layering (UI → ViewModel → domain → repository → data source) and the interface-per-repository pattern; new data access belongs behind a repository, not in a composable.
- **Never fake success.** The codebase's core principle is that a failed or unverified operation reports an honest failure state. Don't stub out a success path — add a real failure state instead.
- **Add tests for new logic.** Pure logic (grouping, clustering, parsing, state machines) should be unit-tested. If you touch the backup engine, extend the existing fakes rather than mocking the framework.
- **Room changes require an explicit migration.** Never enable a destructive fallback. Add a new `Migration`, bump the database version, and add a migration test that proves existing rows survive.
- **Never commit credentials.** Telegram API credentials and signing keys are supplied only through GitHub Actions secrets.
- **Keep the design system centralized.** Use the tokens in `ui/theme/` rather than scattering raw `dp` or color literals.
- **Write descriptive commit messages** explaining the change.

**Where things live** — see [Architecture](#architecture) for the full package layout. In short:
`ui/` for Compose screens and components, `domain/` for pure models and state machines,
`data/` for repositories and data sources, `telegram/` for TDLib, and `di/` for wiring.

**Reporting issues** — when reporting a bug, please include the Android version, device model (ARM),
whether it involves Telegram or backup, and the exact steps to reproduce.

---

## License

**No license has been chosen yet.** There is no `LICENSE` file in this repository, which means the
code is under default copyright — all rights reserved. If you would like to use this code, please
open an issue to discuss licensing.

---

## Acknowledgements

Telepic stands on the shoulders of these projects:

- [TDLib](https://github.com/tdlib/td) — the Telegram client library (via the
  `io.github.tdlib-android:core` packaging).
- [osmdroid](https://github.com/osmdroid/osmdroid) and [OpenStreetMap](https://www.openstreetmap.org/) contributors — map tiles and rendering.
- [Jetpack Compose](https://developer.android.com/jetpack/compose), [Media3](https://developer.android.com/media/media3), [Room](https://developer.android.com/training/data-storage/room), [Paging](https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data), [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager), and [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) — AndroidX.
- [Coil](https://coil-kt.github.io/coil/) — image loading.
- [Kotlin](https://kotlinlang.org/) and [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines).
