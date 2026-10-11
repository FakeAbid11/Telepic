# Telepic

A modern, standalone Android photo & video library with Telegram-powered cloud backup — built with **Kotlin**, **Jetpack Compose**, and **Material 3 / Material You**.

Telepic reads your device library through `MediaStore` and presents it as a fast, beautiful timeline. It backs media up to a private Telegram channel and lets you browse that cloud library by **preview**, downloading originals only when you explicitly ask.

> **Local-first photo management with Telegram-powered cloud backup.**

---

## Current status

Telepic is a **debug-milestone** project. Every feature below is implemented and verified by CI
(compile + unit tests + lint), but the Telegram and device-bound surfaces have **not** been exercised
on a real device yet — see [Device validation status](#device-validation-status).

| Feature | Status |
|---|---|
| Onboarding (6 steps) | Complete |
| Version-aware media permissions | Complete |
| Photos timeline (adaptive grid, day headers, date rail, paging) | Complete |
| Backup status indicators | Complete *(real-upload path device-gated)* |
| Albums + album contents | Complete |
| Full-screen Viewer (photo / GIF / video) | Complete *(playback device-validated only)* |
| Map (EXIF GPS, marker clustering) | Complete *(rendering device-validated only)* |
| Favorites / Archive / Trash | Complete *(system delete consent device-validated only)* |
| Telegram authentication (TDLib) | Code complete *(live login device-gated)* |
| Cloud browse + preview | Code complete *(live calls device-gated)* |
| Backup engine (queue, state machine, WorkManager) | Code complete *(live upload device-gated)* |
| Recognition & deduplication (SHA-256) | Code complete *(remote half device-gated)* |
| Restore / save-to-device | Code complete *(download + MediaStore write device-gated)* |
| Settings | **Partial** — see [Known limitations](#known-limitations) |
| Media Details | Not implemented |
| Search | Not implemented (deliberately deferred) |
| App lock | Not implemented (reserved) |

---

## Requirements

- **Android 8.0+** (`minSdk 26`) — required by the TDLib Android artifact.
- **ARM only** — the APK ships `arm64-v8a` and `armeabi-v7a` native libraries (`ndk.abiFilters`) and
  excludes x86/x86_64.
- **No local toolchain** — you do not need Android Studio, Gradle, or the Android SDK to build
  Telepic; GitHub Actions builds it (see below).

---

## Technology stack

| Area | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 (Material You) — **no XML layouts** |
| Architecture | Layered, unidirectional state flow: UI → ViewModel → Domain → Repository → Data source |
| Async | Kotlin Coroutines + Flow / StateFlow |
| Navigation | Navigation for Compose, with a typed `MediaSource` across the Viewer boundary |
| Dependency injection | Hand-written container (`di/AppContainer.kt`) — **no Hilt/Koin** |
| Local media | Android `MediaStore` + Paging 3 |
| App-data storage | Room (KSP) — 5 tables, see [Architecture](#architecture) |
| Settings storage | Jetpack DataStore (Preferences) |
| Image loading | Coil — thumbnail-first, video-frame decoding, animated GIFs |
| Video playback | Media3 ExoPlayer (`PlayerView`) |
| Map | osmdroid + OpenStreetMap tiles (Apache-2.0, no API key) |
| EXIF / GPS | `androidx.exifinterface` — coordinates read from file EXIF only |
| Recognition / dedup | Streaming SHA-256 content identity (`MessageDigest`; no third-party hashing lib) |
| Cloud backend | Telegram via Java TDLib (`org.drinkless.tdlib`), ARM-only |
| Background work | WorkManager — network-constrained, with a foreground backup worker |
| Testing | JUnit 4, `kotlinx-coroutines-test`, Robolectric (all JVM; **no instrumented tests**) |
| Build & CI | GitHub Actions only |

---

## Build & run (GitHub Actions only)

Telepic is built entirely by **GitHub Actions**. Every push to `main` and every pull request runs the
[`Android CI`](.github/workflows/android-ci.yml) workflow, which:

1. Checks out the repository
2. Sets up JDK 17
3. Sets up Gradle with dependency caching
4. Compiles the app and runs the unit tests
5. Runs Android lint (static verification)
6. Assembles a **debug APK**
7. Uploads the APK (and test/lint reports) as workflow artifacts

### Getting the debug APK

1. Open the repository's **Actions** tab.
2. Select the latest run on `main` (or your pull request).
3. Scroll to **Artifacts**.
4. Download **`telepic-debug-apk`** and install it on your device.

You can also trigger a build manually via **Run workflow** (`workflow_dispatch`).

### Telegram credentials

Telegram (TDLib) login needs **API credentials supplied as GitHub Actions Secrets**. Without them the
debug build still compiles and runs — login is simply unavailable, so CI stays green.

Configure them under **Settings → Secrets and variables → Actions**:

- `TELEGRAM_API_ID` — numeric, from your [Telegram developer account](https://my.telegram.org)
- `TELEGRAM_API_HASH` — string

CI passes them to Gradle (masked); Gradle injects them into `BuildConfig`. They are **never**
committed, hardcoded, or logged. Without them, debug builds use a safe placeholder (`api_id = 0`) and
Telegram login is unavailable. A **release** build refuses to run at all unless real credentials are
present, so a secret-less release cannot ship with a permanently dead login.

### Verifying Telegram login on a device

Real-device Telegram login must be done manually by the developer, on an ARM Android device:

1. Install the debug APK.
2. Complete onboarding.
3. On the **Telegram Login** step, enter your phone number, the Telegram code, and (if enabled) your
   2FA password.
4. Confirm the authorized state, restart the app (the session should persist), and test
   logout/re-login.

---

## Architecture

**Layered, unidirectional, single-module.** Everything lives in the `:app` Gradle module
(`settings.gradle.kts`).

```
UI (Compose, stateless where possible)
  ↓
ViewModel (StateFlow / Paging 3)
  ↓
Domain (pure models + state machines)
  ↓
Repository (interface + Default implementation)
  ↓
Data sources: MediaStore · Room · TDLib · File system · DataStore
```

Dependency injection is a small hand-written container (`di/AppContainer.kt`) using `by lazy`
singletons, deliberately shaped so a future Hilt/Koin migration can swap implementations without
touching callers.

**Room schema — version 6, with explicit migrations and no destructive fallback:**

| Table | Purpose | Introduced |
|---|---|---|
| `cloud_destination` | The single validated Telepic Backup channel | v1 |
| `cloud_media_manifest` | Cloud media index, keyed by `chatId + messageId` | v1 |
| `backup_queue` | Source of truth for backup state | 1 → 2 |
| `media_organization` | Favorites / Archive / Trash state | 3 → 4 |
| `media_location` | EXIF-GPS location cache | 4 → 5 |

Migrations are `1 → 2`, `2 → 3`, `3 → 4`, `4 → 5`, and `5 → 6` (the last adds the `pendingTelegram*`
columns to `backup_queue`). Each has a migration test that builds the previous version and proves
existing rows survive.

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

### Package namespace

`com.telepic` — used consistently across the application ID, namespace, and source packages.

---

## Device validation status

Telepic is built and unit-tested entirely in CI, but its Telegram and device-bound surfaces can only
be verified on a **real ARM device with an authenticated Telegram session**. None of the following
has been performed yet. In every case the code path exists and is unit-tested with fakes — it has
simply never run against the real thing.

| Surface | Why it is device-gated |
|---|---|
| TDLib request/response shapes | Written against `io.github.tdlib-android:core:0.1.1` (TDLib 1.8.x); the generated `TdApi` surface must be finalized on-device |
| Telegram auth round-trip | Needs a real phone number, code, and optional 2FA |
| Cloud browse / preview / original download | Needs a populated channel and a live session |
| Backup upload (`SendMessage` + `InputFile`) | Same bring-up; until then `TdLibCloudDataSource.upload` throws an honest transient failure and items stay `QUEUED`/`WAITING` — never falsely `BACKED_UP` |
| MediaStore write (restore) and delete consent | Provider behaviour and the system consent dialog are device-only |
| ExoPlayer playback, osmdroid rendering, Coil GIF decode | Rendering is device-only |

Where a phase below says **NOT TESTED**, it means exactly this: implemented and CI-verified with
fakes, never exercised on a device.

---

## Development history

A chronological log of what each phase built. Two status labels are used:

- **complete** — implemented, unit-tested, and CI-verified.
- **code complete; … pending device validation** — implemented and unit-tested with fakes, but the
  live/device path has not run (see [Device validation status](#device-validation-status)).

### Phase 1 — Foundation & Material You Design System *(complete)*

- Valid Android Kotlin project using Jetpack Compose + Material 3
- Telepic branding (app name, blue/black/white identity, adaptive launcher icon)
- Centralized **Telepic design system**: color, typography, shape, and spacing tokens
- **Dark (default)**, **Light**, and **System default** themes with a persisted preference
- Five primary navigation destinations in a Material 3 bottom bar:
  **Photos · Cloud · Albums · Map · Settings**
- Reusable UI components: `EmptyState`, `LoadingState`, `ErrorState`, `ScreenHeader`
- Foundation settings screen (Account, Backup, Appearance, Storage, About)
- Layered architecture foundation with a lightweight manual DI container
- Unit-test foundation (navigation, theme, settings persistence)
- GitHub Actions CI producing a debug APK artifact

### Phase 2 — Onboarding & Permissions *(complete)*

- **Six-screen first-run onboarding**: Welcome · How Telepic Works · Permissions · Telegram Login ·
  Backup Preferences · Ready
- Persistent onboarding state (completion + backup preference) via DataStore; completed users launch
  straight into the main app, fresh installs start onboarding
- Polished Material 3 onboarding: subtle step-progress indicator, back navigation, vector-only
  illustrations (no stock imagery), and short screen transitions
- **Version-aware media permissions**:
  - Android 13+ — granular `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`
  - Android 14+ — partial "selected photos" access (`READ_MEDIA_VISUAL_USER_SELECTED`)
  - Android 12 and below — the legacy storage model
  - Honest granted / partial / denied / permanently-denied states with an open-Settings recovery path
  - No unrelated permissions are requested
- Backup preference selection (`BACKUP_ALL` / `SELECT_FOLDER` / `NOT_NOW`), persisted for later phases
- **Telegram Login UI foundation**: a real state contract (`TelegramAuthState`) and a controller
  abstraction that Phase 4 (TDLib) implements — no fake authentication, accounts, or channels
- Reusable onboarding components (scaffold, progress, illustration, choice card), Compose UI tests
  (Robolectric), and JVM unit tests for persistence, routing, and permission logic

### Phase 3 — Local Media Library *(complete)*

- **Real local library from MediaStore** — photos, videos, and GIFs are read from `MediaStore.Files`
  as a single, newest-first, paged stream (no full-library scans in memory, no filesystem-path
  identity — content URIs are used)
- **Domain model** `LocalMedia` + type-safe `MediaType` (PHOTO / VIDEO / GIF), normalized from
  MediaStore MIME metadata, carrying metadata for later features (bucket id/name, relative path,
  dimensions, size, dates, duration)
- **Paging 3** (`PagingSource` → `Repository` → `ViewModel` → `LazyVerticalGrid`) for fast,
  incremental, memory-efficient loading with stable keys and cancellation
- **Thumbnail-first grid**: adaptive columns, rounded tiles, video play + duration indicators, GIF
  badges, **Coil** image loading (with video-frame decoding) — full originals are never decoded for
  the grid
- **Chronological date grouping** with full-width day headers and a subtle **right-side date rail**
  foundation for quick timeline navigation
- **Permission-aware** (reuses the Phase 2 model): queries only when access is available; honest
  granted / partial / denied / blocked states, each with appropriate UI and a recovery path
- Distinct **Loading / Empty / Permission / Error** states with retry, plus MediaStore **change
  detection** via a lifecycle-safe `ContentObserver` that invalidates the paged data
- Viewer **navigation contract** (passes only a stable media id); the full Viewer is a later phase
- No backup, hashing, Telegram, or cloud is triggered by viewing media

### Phase 4 — Telegram / TDLib Integration *(code complete; live login pending device validation)*

- **Java TDLib** bindings (`org.drinkless.tdlib.Client` / `TdApi`) via the
  `io.github.tdlib-android:core` AAR — **no JSON client, no hand-written JNI shim**
- **ARM-only** native libraries (`arm64-v8a`, `armeabi-v7a`) selected via `ndk.abiFilters`; the APK
  excludes x86/x86_64. `minSdk` raised to **26** (required by the TDLib artifact)
- Clean layer boundary: **UI → `TelegramAuthController` → `TelegramSessionManager` →
  `TdLibClientGateway` → TDLib**. The UI never touches `Client` directly
- Full authentication state machine mapping **every** TDLib authorization state to `TelegramAuthState`
  (initializing → phone number → code → optional 2FA password → authorized; closing/closed; errors)
- **Persistent, app-private TDLib database** (`noBackupFilesDir`) with a **Keystore-wrapped
  encryption key** (AES-256-GCM) — login survives app restart and process death, and is never stored
  on shared storage
- Real onboarding Telegram UI: phone / code / 2FA inputs, account identity on success, and recoverable
  errors (invalid credentials, **flood wait**, network) — **never fakes a login**
- **Non-blocking startup**: TDLib initializes without ever delaying or crashing the local Photos flow
- **No sensitive data in logs**: the API hash, phone number, authentication code, 2FA password, and
  the encryption key cannot leak through logs. (The app performs no logging at all today — a
  structured logging layer is planned.)

### Phase 5 — Telegram Cloud *(code complete; live cloud calls pending device validation)*

- **Room** introduced (KSP) with an explicit schema and **no destructive fallback**: `cloud_destination`
  (a single validated row per provider) and `cloud_media_manifest` (stable identity
  `chatId + messageId`, plus a hash field reserved for Phase 7)
- Clean layering: **Cloud screen → `CloudViewModel` → `CloudRepository` → `CloudDataSource` → TDLib**,
  reusing the **single** Phase 4 client/session/gateway (never a second Telegram client)
- Domain models: `TelepicCloudDestination`, `CloudMedia` (IMAGE / VIDEO / GIF, GIF kept distinct),
  `CloudPreview`, `LocalDownloadedMedia`; and a rich `CloudStatus` (initializing / connecting /
  refreshing / ready / empty / offline / not-authenticated / destination-missing / invalid / error)
- `ChatValidator` enforces a **safe destination** (an accessible channel the account can post to,
  with the expected title) — never the title alone, so a same-name channel owned by someone else is
  rejected
- Repository orchestration (discover → create-if-needed → validate → persist → refresh → manifest
  upsert with de-dup → state), race-guarded, cancellable, on background dispatchers; **no uploads**
- A functional **Cloud screen** (adaptive grid, video/GIF indicators, preview-on-demand) that
  **never downloads originals to browse** and distinguishes "empty" from "offline/error"

### Phase 6 — Reliable Backup Engine *(code complete; live upload pending device validation)*

- **Persistent backup queue** in Room (`backup_queue`) — the source of truth for backup state. It
  survives process death, app restart, and reboot. The **unique `localMediaId`** (the MediaStore id,
  never the filename) prevents duplicate active rows, so a repeated `enqueue(media)` is idempotent.
- **Explicit state machine** (`NOT_BACKED_UP · QUEUED · PREPARING · UPLOADING · BACKED_UP · FAILED ·
  CANCELLED`, plus the non-terminal `WAITING_FOR_NETWORK` / `WAITING_FOR_AUTH`). The **core
  invariant** — nothing reaches `BACKED_UP` except from `UPLOADING` — is encoded once in
  `BackupStateMachine` and unit-tested. The only success path is: upload → Telegram confirms →
  **remote identity persisted → THEN `BACKED_UP`** (the identity write is atomic with the state).
- **Room migration 1 → 2**: the Phase 5 database is preserved by an explicit, non-destructive
  migration. A real migration test builds a version-1 database and verifies Phase 5 rows survive and
  the queue becomes usable.
- **Crash recovery**: on worker start, rows left mid-flight (`PREPARING` / `UPLOADING`) with no
  persisted remote id return to `QUEUED`; a row that *does* carry a remote id is finalized. The queue
  never assumes an upload failed just because the process died, and never claims success without
  confirmation.
- **Bounded retry**: transient failures (network / Telegram / TDLib) wait for network and consume a
  capped retry; permanent failures (missing/unsupported media, rejected upload) fail immediately and
  can be retried by the user. `retryCount`, `lastError`, and `updatedAt` are persisted.
- **Cooperative cancellation** — a cancelled item never becomes `BACKED_UP` unless Telegram already
  confirmed it; an in-flight item is left for recovery rather than lied about.
- **Safe staging** — a MediaStore content URI is streamed (fixed-size buffer, never `readBytes()`)
  into app-private storage, used for the upload, and cleaned up in a `finally`. The **original is
  never modified or deleted**.
- **WorkManager orchestration**: `BackupWorkScheduler` → `BackupWorker` (a `CoroutineWorker`) with a
  **network constraint** and an honest **foreground notification** ("Uploading n of m"). Offline just
  leaves rows `QUEUED` and resumes on reconnect; the engine never depends on a screen staying open.
- **Cloud upload contract** — the engine calls `CloudRepository.uploadMedia(request, onProgress)` and
  knows **no TDLib details**; the real Telegram request shapes stay behind the data layer.
- **Backup preference honored**: `BACKUP_ALL` incrementally discovers and enqueues eligible media;
  `NOT_NOW` never auto-enqueues; `SELECT_FOLDER` is **not faked** as enforceable — folder selection
  has no real implementation yet, so the queue treats it as "nothing automatic" and the pipeline is
  ready for a folder filter.
- **Backup Center** foundation (reached contextually from Settings): status, pending / uploading /
  completed / failed counts, and per-item retry / cancel — no upload polish, no full restore.
- **Phase 7 ready**: the queue keeps nullable `contentHash` / timestamps and the manifest keeps its
  reserved hash field, but **no SHA-256 recognition or dedup is performed** here.

### Phase 7 — Backup Recognition & Deduplication *(code complete; remote recognition pending device validation)*

- **SHA-256 content identity** — streaming, cancellable hashing on `Dispatchers.IO` via
  `MessageDigest("SHA-256")` (no third-party hashing lib, no `readBytes()` of whole files), producing
  a consistent **64-char lowercase hex** digest persisted across the queue, the remote manifest, and
  tests. Recognition identity is **content — never filename, id, date, or album** (unit-tested: same
  bytes under different filenames are duplicates; same filename with different bytes are not).
- **Hash cache with correct invalidation** — a stored hash is reused only while **both** content size
  and modified timestamp are unchanged; a change in either forces a re-hash. A MediaStore URI is never
  trusted as the cache key on its own.
- **Content-change-during-hash guard** — the hasher re-checks the provider size after streaming and
  discards a torn hash rather than persisting one for the wrong file version.
- **Remote manifest is queryable by hash** — `findByContentHash` / `findByContentHashAndSize` (both
  backed by an explicit `contentHash` **index**), returning a deterministic earliest message id when
  several equivalent records exist, so recognition never triggers a new upload just because duplicates
  already exist. The whole manifest is never scanned into memory.
- **Room migration 2 → 3** — adds `contentSizeBytes`, `hashedAt`, and a `contentHash` index to
  `backup_queue`, plus a `contentHash` index to `cloud_media_manifest`; **no destructive fallback**. A
  migration test builds a real version-2 database, reopens it at version 3 through the explicit
  migration, and proves Phase 5/6 rows **and existing hashes survive**.
- **Recognition before enqueue** — the coordinator recognizes each item first: `AlreadyBackedUp` and
  `Pending` never create a queue row; `NeedsBackup` enqueues carrying the fresh hash (persisted up
  front); a hash/read failure surfaces `Unavailable` and still attempts backup (never silently
  skipped, never falsely "backed up"). Cross-identity dedup stops identical bytes reached through two
  local ids from being queued twice.
- **Reinstall-proof by design** — recognition matches on content hash against the remote manifest, so
  a fresh install with new MediaStore ids recognizes existing Telegram media without a second upload.
  (The model and DAO/query boundaries for the future reinstall UI are in place; the UI itself is not.)
- **Phase 6 preserved entirely** — state machine, WorkManager, retry, cancellation, crash recovery,
  staging, and the `CloudRepository` upload abstraction are untouched; recognition feeds the existing
  queue rather than replacing it. On a real upload result the content hash is persisted into the
  manifest as part of success.
- **No TDLib internals leak into recognition** — the engine knows only `CloudRepository`, the
  manifest, and a remote identity; never `TdApi` / `SendMessage` / `InputFile`. No JSON or JNI was
  introduced.

### Phase 8 — Photos UI, Timeline, Date Rail & Backup Status *(complete)*

- **Top app bar** with the Photos title and a **Settings** action that navigates to the existing
  Settings destination (single-top, identical to tapping the Settings tab). No Search was added.
- **Adaptive, thumbnail-first grid** (`LazyVerticalGrid`, `GridCells.Adaptive`) over the Phase 3
  Paging 3 stream with **stable keys**, square center-cropped cells, and no full-resolution decode.
- **Localized relative day headers** — Today / Yesterday / weekday / "October 8" / full date via
  `java.time` and the device locale. Grouping identity is a stable ISO **day key** (never the display
  text) resolved in the **device time zone**, so a photo never shifts days. No English-only hardcode.
- **Right-side date rail** built from the *loaded* day headers only — it grows with paging and never
  force-loads the library — with the active day emphasized and tap-to-scroll to the loaded group.
- **Backup indicators from the repository, never inferred by the UI** — `BackupStatusRepository` maps
  the Room queue into one batched `Map<mediaId, MediaBackupVisualState>` snapshot; tiles read
  `states[id] ?: NONE`. `BACKED_UP` reflects only confirmed remote success. States shown: QUEUED /
  UPLOADING (genuinely indeterminate — no fabricated percentages) / BACKED_UP / FAILED; NONE renders
  nothing, to keep the grid media-first.
- **No per-tile cost** — a tile runs **no Room query, no hashing, no network call**; status comes from
  the shared snapshot map. The content hash stays a recognition-only identity and is **never** the
  UI/navigation key (the MediaStore id remains it).
- **Paging UX** — a lightweight skeleton on first load, a small end-of-list spinner when appending, an
  inline retry on append failure, and a refresh failure that **keeps loaded photos** with a retry
  affordance (never a full-screen empty error).
- **Pull-to-refresh** wired to the repository's MediaStore invalidation only — refreshing Photos does
  **not** trigger a remote Telegram sync.
- **Permission honesty** — full / **partial** / denied / permanently-denied states stay distinct; a
  subtle partial-access banner states that the library is limited to the selected media. MediaStore
  change observation (Phase 3) is preserved; local deletion never touches the Telegram cloud copy.
- **Theming** — dark (default), light, and system all render through the existing Material 3 design
  tokens; new spacing tokens (`railGutter`, `railWidth`) were added rather than scattered dp values.
- **Accessibility** — meaningful merged content descriptions on tiles (type + localized date +
  duration + backup state), a labeled Settings action, a labeled date rail, and a rail touch column at
  Material's target width.

> **Known boundary at the time:** the full Viewer, Media Details, Albums, Map,
> Favorites/Archive/Trash, and Search were not implemented yet. Device testing and the
> real-Telegram backup-indicator path remained NOT TESTED (live upload is device-gated); indicators
> were verified with repository states, not real uploads.

### Phase 9 — Cloud, Albums & Full-Screen Viewer *(code complete; live cloud content pending device validation)*

- **Typed media-source navigation** — a `MediaSource` (Local = MediaStore id, Cloud = `chatId` +
  `messageId`) is the *only* identity that crosses the Viewer boundary, so a Telegram message is never
  mistaken for a local id and back navigation returns to the originating screen. Photos and album tiles
  open `viewer/local/{id}`; Cloud tiles open `viewer/cloud/{chatId}/{messageId}`.
- **Full-screen Viewer** for photos, videos, and GIFs: pinch-to-zoom / pan with double-tap reset
  (pure, unit-tested `ZoomState` math), a labelled source (on-device vs Telegram cloud), the item's
  date, and previous/next **within the originating collection** — local neighbours come from two cheap
  `_ID`-bounded MediaStore queries, cloud neighbours from the Room manifest's order. No whole-library
  load for adjacency.
- **Missing media is honest** — an unknown local id, or a manifest-absent `(chatId, messageId)`,
  renders a "no longer available" state and never substitutes another item.
- **Video playback via Media3/ExoPlayer** (`PlayerView`), isolated in one opt-in component with proper
  release; local videos play their content URI directly. **Cloud video has no fake streaming** — there
  is no verified TDLib streaming path, so a cloud video plays only its explicitly-downloaded original
  and otherwise shows the honest download step.
- **Cloud originals download only on explicit request** — opening the Viewer never fetches the
  original; the download action runs `CloudRepository.downloadOriginal`, and success is shown only
  with a verified local file path. Until the TDLib `DownloadFile` / `GetFile` shapes are
  device-finalized the result is `Unavailable` (never a fabricated file), and previews remain the
  browse mechanism.
- **Local Albums from real MediaStore buckets** — `AlbumGrouper` (pure, unit-tested) groups a
  projection-limited, newest-first query by `BUCKET_ID`: newest item as cover, real counts, provider
  bucket names with a neutral fallback (never a filename), null-bucket rows skipped. Permission-aware,
  with distinct loading / genuinely-empty / error states, like Photos.
- **Album contents reuse the exact Photos pipeline** — the same `MediaPagingSource`, mapper,
  projection, and ordering filtered by bucket, rendered through the same `MediaGrid` (day headers,
  date rail, backup badges), so an album behaves identically to the timeline. Paging 3 throughout; no
  full-album memory load.
- **Cloud grid → Viewer wiring** — tapping a cloud tile opens the shared Viewer by remote identity
  (previously a static grid); animated **GIF playback in the Viewer** via a Coil GIF decoder registered
  on the app `ImageLoader`.
- **Phases 1–8 preserved** — onboarding, theme, permissions, Photos timeline/date rail/backup status,
  the backup engine, recognition/dedup, and all prior contracts and tests are untouched; the old
  untyped viewer route was replaced by the typed source, and nothing else in navigation changed.

### Phase 10 — Map, Media Organization, Restore & Real Telegram Integration *(code complete; live Telegram, map rendering, and MediaStore write/delete pending device validation)*

This phase attacked the long-standing device-gated Telegram blocker first, then completed the
interactive map, Favorites/Archive/Trash, and restore.

**Part A — Real Telegram cloud (request shapes finalized against the resolved API)**

- The defensive stubs were replaced with **actual TDLib calls** built against the exact
  `io.github.tdlib-android:core:0.1.1` (TDLib 1.8.x) `TdApi` surface — verified by inspecting the
  bundled generated classes, not guessed from another version: `SearchChatsOnServer` (with a channel
  type filter), `GetChat` / `GetChatMember`, `CreateNewSupergroupChat` (a channel), `GetChatHistory`
  (newest-first paging), synchronous `DownloadFile` with a verified local path, `SendMessage` with
  media-appropriate `InputMessagePhoto` / `InputMessageVideo` / `InputMessageAnimation` over
  `InputFileLocal` from the staged file, and `GetMessage` (to poll a sent message's sending state).
- Destination posting rights come from the account's **real chat membership** (creator, or admin with
  the post right) — never the channel title. The single shared TDLib client is reused (no second
  client). An upload is reported as a confirmed remote identity **only after the sent message's
  sending state clears to success** (polled, not raced): a rejected send is permanent, a flood-wait /
  5xx is transient, and a confirmation timeout is treated as transient so the queue can retry — a
  queue item is never marked `BACKED_UP` on a guess.
- JVM unit tests construct and map real `TdApi` value objects (no native library) and drive the data
  source through a **fake gateway** — request construction, response mapping, discovery→membership,
  history paging, download verification, and upload confirm/reject/flood/timeout.
- **Live Telegram behaviour is device-gated and NOT PERFORMED here.** What is done: the concrete
  requests and response mapping are implemented and unit-tested. What is missing: a real authorized
  round-trip on a device. Nothing is claimed to work live.

**Part B — Interactive OpenStreetMap photo map**

- osmdroid (Apache-2.0, no API key) rendered via a Compose `AndroidView`, with proper OSM attribution
  and a public MAPNIK tile source — documented as not suitable for unlimited production traffic.
- Photo positions come **only from embedded EXIF GPS**, parsed by a pure, unit-tested `GpsCoordinates`
  (DMS + hemisphere → decimal, range-validated, malformed/non-finite rejected). **No coordinate is ever
  fabricated** — media without valid GPS simply never gets a pin, and non-geotagged photos stay in
  Photos. **No device-location permission is requested, and no coordinates or EXIF are uploaded.**
- Extracted coordinates are cached in a new `media_location` Room table (explicit, non-destructive
  migration 4 → 5) so full-resolution EXIF is not re-read on every render; a pure `MapClusterer` groups
  nearby markers while preserving every item id. Selecting a marker opens the shared Viewer by that
  MediaStore id. `INTERNET` / `ACCESS_NETWORK_STATE` were added for tiles.
- GPS parsing, clustering, and the cache are CI-tested with fakes. **Map pan/zoom/tiles/marker
  rendering are device-only and NOT TESTED**, and **no offline tile caching is implemented** (tiles
  require connectivity).

**Part C — Favorites, Archive & Trash**

- Durable per-item state in a new `media_organization` Room table (explicit migration 3 → 4), keyed by
  the MediaStore id — **independent of backup state** (favoriting/archiving/trashing never touches the
  queue, remote identity, or cloud status; unit-tested).
- **Favorite** stays visible in the library; **Archive** is removed from the primary timeline via
  **DB-side** filtering (`_ID NOT IN (...)` in the MediaStore and album-bucket queries) but is stored
  and restorable; **Trash** is hidden from normal browsing until recovered or permanently deleted.
- Viewer favorite/archive/trash actions, plus contextual Favorites / Archive / Trash collections
  reached from Albums (no new bottom-navigation destinations), with undo (unfavorite / unarchive /
  restore).
- **Permanent delete** goes through the Android-sanctioned **`MediaStore.createDeleteRequest`** system
  consent (API 30+), is confirmation-gated, and only forgets an item after a consented success. The
  app-level trash flag is kept **distinct** from an actual file removal (`deletedFromStore`), and the
  legacy per-file consent (API 26–29) is documented as unsupported rather than faked. Repository
  persistence, migration, timeline hidden-id computation, and the collection ViewModel are CI-tested;
  the **system delete consent is device-only and NOT TESTED**.

**Part D — Restore / download original to device**

- `RestoreRepository`: explicit download of a cloud original → **SHA-256 verification** of the bytes
  (against the trusted manifest hash when present) → publish into public media storage via a
  `MediaStore` `ContentResolver` insert + streamed copy (correct MIME, scoped
  `Pictures|Movies/Telepic` path, `IS_PENDING` commit, no overwrite of unrelated files, partial-write
  cleanup). A **"Save to device"** action lives in the Viewer for cloud items; success is reported only
  after a committed publication, and every earlier step is a distinct, honest failure.
- Publishing needs **write access on API ≤ 28** (the scoped-storage columns only exist on API 29+), so
  the app declares `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="29"` and requests it alongside media
  access on those versions only.
- CI-tested with a fake cloud source and a fake publisher (success, unavailable/missing, verify
  mismatch, low-storage, publish failure). **Real Telegram download and the MediaStore write are
  device-gated and NOT TESTED**; byte-for-byte preservation of a restored *image* is not claimed
  (Telegram's photo pipeline may recompress), consistent with the upload note.

**Cross-phase:** Phases 1–9 are preserved; the new tables are additive (non-destructive migrations);
ARM-only packaging and `minSdk 26` are unchanged; and GitHub Secrets remain the only credential
source.

### Remaining work

Media Details · Settings & security hardening · full-device integration & hardening (Phases 11–12).

---

## Known limitations

- **Device validation is outstanding.** The entire live Telegram path (auth, cloud browse/preview,
  upload, download), MediaStore write/delete consent, and all rendering (ExoPlayer, osmdroid, GIF
  decode) are unverified on a device. See [Device validation status](#device-validation-status).
- **Settings is incomplete** relative to the product spec. Present: Account (account, connection,
  backup channel, sign-out), Backup (preferences, manual backup, Backup Center link), Appearance
  (theme), Storage (cache sizes, clear cache), About (version). Missing: Wi-Fi / mobile-data /
  charging backup preferences, backup quality/strategy, the Media section (GIF behaviour, video
  behaviour, thumbnail/cache settings), local/cloud information, open-source licenses, and app lock.
- **No instrumented tests.** All tests are JVM/Robolectric; there is no `app/src/androidTest` source
  set, so nothing device-bound is covered by automated tests.
- **No logging.** The app performs no logging at all today, which makes field diagnosis of a
  device-gated failure difficult. Structured logging is planned.
- **No offline map tiles.** The map requires connectivity.
- **`SELECT_FOLDER` has no real folder filter.** Folder selection is persisted, but the backup engine
  treats it as "nothing automatic" rather than faking enforcement.
- **Restored images are not byte-for-byte identical** to the source, because Telegram may recompress
  photos; only the *restored* bytes are hashed.
- **ARM-only**, `minSdk 26`.

---

## License

To be determined.
