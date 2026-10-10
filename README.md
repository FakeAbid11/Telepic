# Telepic

A modern, standalone Android photo & video library with Telegram-powered cloud backup — built with **Kotlin**, **Jetpack Compose**, and **Material 3 / Material You**.

Telepic organizes your local media into a fast, beautiful timeline and (in later phases) backs it up to a private Telegram channel, letting you browse cloud media by preview without automatically downloading originals.

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
| Persistence (app data) | Room (cloud destination, media manifest, backup queue) |
| Image loading | Coil (thumbnail-first, video-frame decoding, animated GIFs) |
| Video playback | Media3 ExoPlayer (`PlayerView`), device-gated |
| Map | **osmdroid** + OpenStreetMap tiles (Apache-2.0, no API key); EXIF GPS via `androidx.exifinterface` |
| Recognition / dedup | Streaming SHA-256 content identity (no third-party hashing lib) |
| Cloud backend | Telegram via **Java TDLib** (`org.drinkless.tdlib`), ARM-only |
| Background work | **WorkManager** (network-constrained, foreground backup worker) |
| Map | OpenStreetMap tiles via **osmdroid**; EXIF GPS, marker clustering |
| Build system | **GitHub Actions only** |

---

## Building Telepic (no local build required)

**You do not need Android Studio, Gradle, or the Android SDK on your machine.** Telepic is built entirely by **GitHub Actions**.

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
4. Download **`telepic-debug-apk`** and install the APK on your device.

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
- Telepic branding (app name, blue/black/white identity, adaptive launcher icon)
- Centralized **Telepic design system**: colors, typography, shapes, spacing tokens
- **Dark (default)**, **Light**, and **System default** themes with persisted preference
- Five primary navigation destinations with a Material 3 bottom navigation bar:
  **Photos · Cloud · Albums · Map · Settings**
- Reusable UI components: `EmptyState`, `LoadingState`, `ErrorState`, `ScreenHeader`
- Foundation settings screen (Account, Backup, Appearance, Storage, About)
- Layered architecture foundation with a lightweight manual DI container
- Unit test foundation (navigation, theme, settings persistence)
- GitHub Actions CI producing a debug APK artifact

### ✅ Phase 2 — Onboarding & Permissions *(complete)*

- **Six-screen first-run onboarding**: Welcome · How Telepic Works · Permissions ·
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
- Domain models: `TelepicCloudDestination`, `CloudMedia` (IMAGE / VIDEO / GIF, GIF kept distinct),
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

### ✅ Phase 6 — Reliable Backup Engine *(architecture + queue + persistence + CI complete; live TDLib upload pending device bring-up)*

Implemented and CI-verified against fakes:

- **Persistent backup queue** in Room (`backup_queue`) — the source of truth for backup state. It
  survives process death, app restart and reboot; the **unique `localMediaId`** (the MediaStore id,
  never the filename) prevents duplicate active rows, so `enqueue(media)` repeated is idempotent.
- **Explicit state machine** (`NOT_BACKED_UP · QUEUED · PREPARING · UPLOADING · BACKED_UP · FAILED ·
  CANCELLED` plus non-terminal `WAITING_FOR_NETWORK` / `WAITING_FOR_AUTH`). The **core invariant** —
  nothing reaches `BACKED_UP` except from `UPLOADING` — is encoded once in `BackupStateMachine` and
  unit-tested. The only success path is: upload → Telegram confirms → **remote identity persisted →
  THEN `BACKED_UP`** (the identity write is atomic with the state).
- **Room migration 1 → 2**: the Phase 5 database (`cloud_destination`, `cloud_media_manifest`) is
  preserved by an explicit, non-destructive migration; a real migration test builds a version-1
  database and verifies Phase 5 rows survive and the queue becomes usable.
- **Crash recovery**: on worker start, rows left mid-flight (`PREPARING` / `UPLOADING`) with no
  persisted remote id return to `QUEUED`; a row that *does* carry a remote id is finalized — the
  queue never assumes an upload failed just because the process died, and never claims success
  without confirmation.
- **Bounded retry**: transient failures (network / Telegram / TDLib) wait for network and consume a
  retry (capped); permanent failures (missing/unsupported media, rejected upload) fail immediately
  and can be retried by the user. `retryCount`, `lastError` and `updatedAt` are persisted.
- **Cooperative cancellation** — a cancelled item never becomes `BACKED_UP` unless Telegram already
  confirmed it; an in-flight item is left for recovery rather than lied about.
- **Safe staging** — a MediaStore content URI is streamed (fixed-size buffer, never
  `readBytes`) into app-private storage, used for the upload, and cleaned up in a `finally`; the
  **original is never modified or deleted**.
- **WorkManager orchestration**: `BackupWorkScheduler` → `BackupWorker` (a `CoroutineWorker`) with a
  **network constraint** and an honest **foreground notification** ("Uploading n of m"). Offline just
  leaves rows `QUEUED` and resumes on reconnect; the engine never depends on a screen staying open.
- **Cloud upload contract** — the engine calls `CloudRepository.uploadMedia(request, onProgress)`
  and knows **no TDLib details**; the real Telegram request shapes stay behind the data layer.
- **Backup preference honored**: `BACKUP_ALL` incrementally discovers and enqueues eligible media;
  `NOT_NOW` never auto-enqueues; `SELECT_FOLDER` is **not faked** as enforceable (folder selection
  has no real implementation yet — the queue treats it as "nothing automatic" and the pipeline is
  ready for a folder filter).
- **Backup Center** foundation (reached contextually from Settings): status, pending / uploading /
  completed / failed counts, and per-item retry / cancel — no upload polish, no full restore.
- **Phase 7 ready**: the queue keeps nullable `contentHash` / timestamps and the manifest keeps the
  reserved hash field, but **no SHA-256 recognition or dedup is performed** here.

Pending (requires an authenticated session on a real ARM device) — **shared with the Phase 5 cloud
data source**: the concrete TDLib cloud request shapes (`searchChatsOnServer`/`getChats`,
`createNewSupergroupChat`, `getChatHistory`, `download`/`getFile`) **and the upload path**
(`sendMessage` + the correct `InputFile` / Photo-vs-Document representation for byte-preserving
quality) are written defensively against **TDLib 1.8.64**, whose generated `TdApi` surface must be
finalized on-device. Until that bring-up `TdLibCloudDataSource.upload` throws an honest transient
failure, so items stay `QUEUED`/`WAITING` and are **never falsely marked `BACKED_UP`**. Everything
downstream is complete and tested with a fake that does succeed, so finalizing only the request
shapes activates real backup.

### ✅ Phase 7 — Backup Recognition & Deduplication *(engine + hashing + persistence complete; live remote recognition pending device bring-up)*

Implemented and CI-verified against fakes:

- **SHA-256 content identity** — streaming, cancellable hashing on `Dispatchers.IO` via
  `MessageDigest("SHA-256")` (no third-party hashing lib, no `readBytes()` of whole files), producing
  a consistent **64-char lowercase hex** digest persisted across the queue, the remote manifest, and
  tests. Recognition identity is **content, never filename/id/date/album** (unit-tested: same bytes
  under different filenames are duplicates; same filename with different bytes are not).
- **Hash cache with correct invalidation** — a stored hash is reused only while **both** content size
  and modified timestamp are unchanged; a size or timestamp change forces a re-hash. A MediaStore URI
  is never trusted as the cache key on its own.
- **Content-change-during-hash guard** — the hasher re-checks the provider size after streaming and
  discards a torn hash rather than persisting one for the wrong file version.
- **Remote manifest is queryable by hash** — `findByContentHash` / `findByContentHashAndSize` (both
  backed by an explicit `contentHash` **index**), returning a deterministic earliest message id when
  several equivalent records exist, so recognition never triggers a new upload just because duplicates
  already exist. The whole manifest is never scanned into memory.
- **Room migration 2 → 3** — adds `hashedAt` and a `contentHash` index to `backup_queue` and a
  `contentHash` index to `cloud_media_manifest`; **no destructive fallback**. A migration test builds
  a real version-2 database, reopens at version 3 through the explicit migration, and proves Phase
  5/6 rows **and existing hashes survive**.
- **Recognition before enqueue** — the coordinator recognizes each item first: `AlreadyBackedUp` and
  `Pending` never create a queue row, `NeedsBackup` enqueues carrying the fresh hash (persisted up
  front), and a hash/read failure surfaces `Unavailable` and still tries backup (never silently
  skipped, never falsely "backed up"). Cross-identity dedup stops identical bytes reached through two
  local ids from being queued twice.
- **Reinstall-proof by design** — recognition matches on content hash against the remote manifest, so
  a fresh install with new MediaStore ids recognizes existing Telegram media without a second upload
  (the model + DAO/query boundaries for the future reinstall UI are in place; the UI itself is not).
- **Phase 6 preserved entirely** — state machine, WorkManager, retry, cancellation, crash recovery,
  staging and the `CloudRepository` upload abstraction are untouched; recognition feeds the existing
  queue rather than replacing it. On a real upload result the content hash is persisted into the
  manifest as part of success (§32).
- **No TDLib internals leak into recognition** — the engine knows only `CloudRepository` / the
  manifest / a remote identity, never `TdApi`/`sendMessage`/`InputFile`; no JSON/JNI introduced.

Pending (real Telegram) — **inherits the Phase 5/6 device bring-up dependency**: recognition against a
remote manifest populated by *actual* Telegram uploads (and therefore real duplicate prevention on a
device) cannot be exercised yet because the live upload path is not device-finalized. The **local
recognition engine, hashing, cache invalidation, duplicate protection, concurrency and the manifest
queries are all CI-tested with fakes**; a device test of real Telegram duplicate prevention is
reported as NOT TESTED.

### ✅ Phase 8 — Photos UI, Timeline, Date Rail & Backup Status *(complete; local-first presentation only)*

Implemented and CI-verified (Robolectric + JVM), with all Phase 1–7 tests still green:

- **Top app bar** with the Photos title and a **Settings** action that navigates to the existing
  Settings destination (single-top, identical to tapping the Settings tab). No Search added.
- **Adaptive, thumbnail-first grid** (`LazyVerticalGrid`, `GridCells.Adaptive`) over the Phase 3
  Paging 3 stream with **stable keys**, square center-cropped cells and no full-resolution decode.
- **Localized relative day headers** — Today / Yesterday / weekday / "October 8" / full date via
  `java.time` + the device locale; grouping identity is a stable ISO **day key** (never the display
  text) resolved in the **device time zone** so a photo doesn't shift days. No English-only hardcode.
- **Right-side date rail** built from the *loaded* day headers only — it grows with paging and never
  force-loads the library — with the active day emphasized and tap-to-scroll to the loaded group.
- **Backup indicators from the repository, never inferred by the UI** — `BackupStatusRepository`
  maps the Room queue into one batched `Map<mediaId, MediaBackupVisualState>` snapshot; tiles read
  `states[id] ?: NONE`. `BACKED_UP` reflects only confirmed remote success (§86). States shown:
  QUEUED / UPLOADING (genuinely indeterminate — no fabricated percentages) / BACKED_UP / FAILED; NONE
  renders nothing to keep the grid media-first.
- **No per-tile cost** — a tile runs **no Room query, no hashing, no network call**; status comes
  from the shared snapshot map. The content hash stays a recognition-only identity and is **never**
  the UI/navigation key (MediaStore id remains it).
- **Paging UX** — lightweight skeleton on first load, a small end-of-list spinner when appending, an
  inline retry on append failure, and a refresh failure that **keeps loaded photos** with a retry
  affordance (never a full-screen empty error).
- **Pull-to-refresh** wired to the repository's MediaStore invalidation only — refreshing Photos does
  **not** trigger a remote Telegram sync.
- **Permission honesty** — full / **partial** / denied / permanently-denied states stay distinct; a
  subtle partial-access banner states the library is limited to the selected media. MediaStore change
  observation (Phase 3) is preserved; local deletion never touches the Telegram cloud copy.
- **Theming** — dark (default), light and system all render through the existing Material 3 design
  tokens; new spacing tokens (`railGutter`, `railWidth`) added rather than scattered dp values.
- **Accessibility** — meaningful merged content descriptions on tiles (type + localized date +
  duration + backup state), a labeled Settings action, a labeled date rail, and a rail touch column at
  Material's target width.

Known boundary: the **full Viewer, Media Details, Albums, Map, Favorites/Archive/Trash and Search are
not implemented** (later phases) — the grid navigates to the existing viewer placeholder contract.
Device testing and the real-Telegram backup-indicator path remain NOT PERFORMED / NOT TESTED (live
upload is still device-gated); indicators were verified with repository states, not real uploads.

### ✅ Phase 9 — Cloud, Albums & Full-Screen Viewer *(UI + navigation + data layers complete; live cloud content pending device bring-up)*

Implemented and CI-verified (209 tests, Robolectric + JVM), with all Phase 1–8 tests still green:

- **Typed media-source navigation** — a `MediaSource` (Local = MediaStore id, Cloud = chatId +
  messageId) is the *only* identity that crosses the Viewer boundary, so a Telegram message is never
  mistaken for a local id and back navigation returns to the originating screen. Photos and album
  tiles open `viewer/local/{id}`; Cloud tiles open `viewer/cloud/{chatId}/{messageId}`.
- **Full-screen Viewer** for photos, videos and GIFs: pinch-to-zoom / pan with double-tap reset
  (pure, unit-tested `ZoomState` math), a labelled source (on-device vs Telegram cloud), the item's
  date, and previous/next **within the originating collection** — local neighbours come from two
  cheap `_ID`-bounded MediaStore queries, cloud neighbours from the Room manifest's order. No
  whole-library load for adjacency.
- **Missing media is honest** — an unknown local id or a manifest-absent (chatId, messageId) renders
  a "no longer available" state and never substitutes another item.
- **Video playback via Media3/ExoPlayer** (`PlayerView`), isolated in one opt-in component with
  proper release; local videos play their content URI directly. **Cloud video has no fake streaming**
  — there is no verified TDLib streaming path, so a cloud video plays only its explicitly-downloaded
  original and otherwise shows the honest download step. Playback itself is device-only and reported
  NOT TESTED here.
- **Cloud originals download only on explicit request** — opening the Viewer never fetches the
  original; the download action runs `CloudRepository.downloadOriginal`, and success is shown only
  with a verified local file path. Until the TDLib `download`/`getFile` shapes are device-finalized
  the result is `Unavailable` (never a fabricated file), and previews remain the browse mechanism.
- **Local Albums from real MediaStore buckets** — `AlbumGrouper` (pure, unit-tested) groups a
  projection-limited, newest-first query by `BUCKET_ID`: newest item as cover, real counts, provider
  bucket names with a neutral fallback (never a filename), null-bucket rows skipped. Permission-aware
  with distinct loading / genuinely-empty / error states, like Photos.
- **Album contents reuse the exact Photos pipeline** — the same `MediaPagingSource`, mapper,
  projection and ordering filtered by bucket, rendered through the same `MediaGrid` (day headers,
  date rail, backup badges), so an album behaves identically to the timeline. Paging 3 throughout;
  no full-album memory load.
- **Cloud grid → Viewer wiring** — tapping a cloud tile now opens the shared Viewer by remote
  identity (previously a static grid); animated **GIF playback in the Viewer** via a Coil GIF
  decoder registered on the app ImageLoader.
- **Phase 1–8 preserved** — onboarding, theme, permissions, Photos timeline/date rail/backup status,
  backup engine, recognition/dedup and all prior contracts and tests are untouched; the old untyped
  viewer route was replaced by the typed source, nothing else in navigation changed.

Pending (device bring-up, shared with Phases 5/6): real cloud **manifest content**, **preview
files** and **original downloads** require the TDLib 1.8.64 request shapes to be finalized against
an authorized session on an ARM device; until then the data source returns honest unavailable results
and the UI shows truthful states. ExoPlayer playback (local and downloaded) is likewise
device-validated — **no real-device test was performed for this phase**.

### ✅ Phase 10 — Map, Media Organization, Restore & Real Telegram Integration *(code + CI complete; live Telegram, map rendering, MediaStore write/delete pending device validation)*

This phase attacks the long-standing device-gated Telegram blocker first, then completes the
interactive map, Favorites/Archive/Trash, and restore.

**Part A — Real Telegram cloud (request shapes finalized against the resolved API)**
- The defensive stubs are replaced with **actual TDLib calls** built against the exact
  `io.github.tdlib-android:core:0.1.1` (TDLib 1.8.x) `TdApi` surface — verified by inspecting the
  bundled generated classes, not guessed from another version: `SearchChatsOnServer` (+ a channel
  type filter), `GetChat`/`GetChatMember`, `CreateNewSupergroupChat` (a channel), `GetChatHistory`
  (newest-first paging), synchronous `DownloadFile` with a verified local path, and `SendMessage`
  with media-appropriate `InputMessagePhoto`/`InputMessageVideo`/`InputMessageAnimation` over
  `InputFileLocal` from the staged file.
- Destination posting rights come from the account's **real chat membership** (creator, or admin with
  the post right) — never the channel title. The single shared TDLib client is reused (no second
  client). An upload is reported as a confirmed remote identity **only after the sent message's
  sending state clears to success** (polled, not raced); a rejected send is permanent, a flood-wait/
  5xx is transient, and a confirmation timeout is treated as transient so the queue can retry — a
  queue item is never marked `BACKED_UP` on a guess.
- JVM unit tests construct/mapping real `TdApi` value objects (no native library) and drive the data
  source through a **fake gateway** — request construction, response mapping, discovery→membership,
  history paging, download verification, and upload confirm/reject/flood/timeout.
- **Live Telegram behaviour is device-gated and NOT PERFORMED here** (no ARM device/authenticated
  session was used). What is done: the concrete requests and response mapping are implemented and
  unit-tested; what is missing: a real authorized round-trip on a device. Nothing is claimed to work
  live.

**Part B — Interactive OpenStreetMap photo map**
- osmdroid (Apache-2.0, no API key) rendered via a Compose `AndroidView`, with proper OSM attribution
  and a public MAPNIK tile source; documented as not suitable for unlimited production traffic.
- Photo positions come **only from embedded EXIF GPS**, parsed by a pure, unit-tested `GpsCoordinates`
  (DMS + hemisphere → decimal, range-validated, malformed/non-finite rejected). **No coordinate is
  ever fabricated** — media without valid GPS simply never gets a pin; non-geotagged photos stay in
  Photos. **No device-location permission is requested and no coordinates/EXIF are uploaded.**
- Extracted coordinates are cached in a new `media_location` Room table (explicit, non-destructive
  migration 4→5) so full-resolution EXIF is not re-read on every render; a pure `MapClusterer` groups
  nearby markers while preserving every item id. Selecting a marker opens the shared Viewer by that
  MediaStore id. `INTERNET` / `ACCESS_NETWORK_STATE` added for tiles.
- GPS parsing, clustering and the cache are CI-tested with fakes; **map pan/zoom/tiles/marker
  rendering are device-only and NOT TESTED**, and **no offline tile caching is implemented** (tiles
  require connectivity).

**Part C — Favorites, Archive & Trash**
- Durable per-item state in a new `media_organization` Room table (explicit migration 3→4), keyed by
  the MediaStore id — **independent of backup state** (favoriting/archiving/trashing never touches the
  queue, remote identity, or cloud status; unit-tested).
- **Favorite** stays visible in the library; **Archive** is removed from the primary timeline via
  **DB-side** filtering (`_ID NOT IN (...)` in the MediaStore and album-bucket queries) but is stored
  and restorable; **Trash** is hidden from normal browsing until recovered or permanently deleted.
- Viewer favorite/archive/trash actions; contextual Favorites / Archive / Trash collections reached
  from Albums (no new bottom-navigation destinations), with undo (unfavorite / unarchive / restore).
- **Permanent delete** goes through the Android-sanctioned **`MediaStore.createDeleteRequest`** system
  consent (API 30+), is confirmation-gated, and only forgets an item after a consented success. The
  app-level trash flag is kept **distinct** from an actual file removal (`deletedFromStore`), and the
  legacy Q-per-file consent is documented as unsupported rather than faked. Repository persistence,
  migration, timeline hidden-id computation and the collection ViewModel are CI-tested; the **system
  delete consent is device-only and NOT TESTED**.

**Part D — Restore / download original to device**
- `RestoreRepository`: explicit download of a cloud original → **SHA-256 verification** of the bytes
  (against the trusted manifest hash when present) → publish into public media storage via a
  `MediaStore` `ContentResolver` insert + streamed copy (correct MIME, scoped
  `Pictures|Movies/Telepic` path, `IS_PENDING` commit, no overwrite of unrelated files, partial-write
  cleanup). A **"Save to device"** action lives in the Viewer for cloud items; success is reported
  only after a committed publication, and every earlier step is a distinct honest failure.
- CI-tested with a fake cloud source + fake publisher (success, unavailable/missing, verify mismatch,
  low-storage, publish failure). **Real Telegram download + the MediaStore write are device-gated and
  NOT TESTED**; byte-for-byte preservation of a restored *image* is not claimed (Telegram's photo
  pipeline may recompress), consistent with the upload note.

Phases 1–9 are preserved; the new tables are additive (non-destructive migrations), ARM-only packaging
and `minSdk 26` are unchanged, and GitHub Secrets remain the only credential source.

### 🔜 Remaining

Media Details · Settings & security hardening · full-device integration & hardening (Phases 11–12).

---

## Project structure

```
app/src/main/java/com/telepic/
├── MainActivity.kt              # Single-activity Compose host
├── TelepicApplication.kt        # Owns the DI container + Coil image loader
├── di/                          # Lightweight manual dependency container
├── navigation/                  # Destinations + NavHost + viewer route
├── domain/media/                # LocalMedia, MediaType, PhotosItem, day grouping
├── domain/cloud/                # CloudMedia, destination, status, upload contract
├── domain/backup/               # BackupState + state machine, BackupItem, recognition models
├── data/media/                  # MediaStore loader, mapper, PagingSource, repository, observer
├── data/cloud/                  # Cloud repository + TDLib data source + Room (destination/manifest)
├── data/backup/                 # Backup queue/coordinator/repository, hashing, staging, WorkManager
│   ├── hash/                    # Streaming SHA-256 ContentHasher
│   ├── db/                      # backup_queue entity + DAO (content-hash indexed)
│   └── work/                    # BackupWorker, scheduler, worker factory
├── onboarding/                  # BackupPreference, repository, ViewModel, startup routing
├── permissions/                 # MediaPermissionState + SDK-aware policy + controller
├── settings/                    # ThemeMode, repository, ViewModel (DataStore)
├── telegram/                    # TDLib: controller, session manager, client gateway, key, states
└── ui/
    ├── TelepicRoot.kt            # Startup routing: onboarding vs main app
    ├── TelepicApp.kt            # Scaffold + bottom navigation
    ├── theme/                   # Design system: Color, Type, Shape, Spacing, Theme
    ├── components/              # Reusable Empty/Loading/Error/Header components
    ├── onboarding/              # OnboardingScreen, steps, and reusable onboarding components
    └── screens/                 # photos (timeline, adaptive grid, date rail, backup badges), cloud, albums, map, settings, viewer, backup center
```

---

## Package namespace

`com.telepic` — used consistently across the application ID, namespace, and source packages.

---

## License

To be determined.
