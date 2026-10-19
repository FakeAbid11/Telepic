# Development history (phase by phase)

> Extracted verbatim from the [README](../README.md) so the landing page stays short.
> Two status labels are used: **complete** (implemented, unit-tested, CI-verified) and
> **code complete; … pending device validation** (unit-tested with fakes, live path not run).

A chronological log of what each phase built. Two status labels are used:

- **complete** — implemented, unit-tested, and CI-verified.
- **code complete; … pending device validation** — implemented and unit-tested with fakes, but the live/device path has not run.

## Phase 1 — Foundation & Material You Design System *(complete)*

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

## Phase 2 — Onboarding & Permissions *(complete)*

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

## Phase 3 — Local Media Library *(complete)*

- **Real local library from MediaStore** — photos, videos, and GIFs are read from `MediaStore.Files` as a single, newest-first, paged stream (no full-library scans in memory, no filesystem-path identity — content URIs are used)
- **Domain model** `LocalMedia` + type-safe `MediaType` (PHOTO / VIDEO / GIF), normalized from MediaStore MIME metadata, carrying metadata for later features (bucket id/name, relative path, dimensions, size, dates, duration)
- **Paging 3** (`PagingSource` → `Repository` → `ViewModel` → `LazyVerticalGrid`) for fast, incremental, memory-efficient loading with stable keys and cancellation
- **Thumbnail-first grid**: adaptive columns, rounded tiles, video play + duration indicators, GIF badges, **Coil** image loading (with video-frame decoding) — full originals are never decoded for the grid
- **Chronological date grouping** with full-width day headers and a subtle **right-side date rail** foundation for quick timeline navigation
- **Permission-aware** (reuses the Phase 2 model): queries only when access is available; honest granted / partial / denied / blocked states, each with appropriate UI and a recovery path
- Distinct **Loading / Empty / Permission / Error** states with retry, plus MediaStore **change detection** via a lifecycle-safe `ContentObserver` that invalidates the paged data
- Viewer **navigation contract** (passes only a stable media id); the full Viewer is a later phase
- No backup, hashing, Telegram, or cloud is triggered by viewing media

## Phase 4 — Telegram / TDLib Integration *(code complete; live login pending device validation)*

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

## Phase 5 — Telegram Cloud *(code complete; live cloud calls pending device validation)*

- **Room** introduced (KSP) with an explicit schema and **no destructive fallback**: `cloud_destination` (a single validated row per provider) and `cloud_media_manifest` (stable identity `chatId + messageId`, plus a hash field reserved for Phase 7)
- Clean layering: **Cloud screen → `CloudViewModel` → `CloudRepository` → `CloudDataSource` → TDLib**, reusing the **single** Phase 4 client/session/gateway (never a second Telegram client)
- Domain models: `TelepicCloudDestination`, `CloudMedia` (IMAGE / VIDEO / GIF, GIF kept distinct), `CloudPreview`, `LocalDownloadedMedia`; and a rich `CloudStatus` (initializing / connecting / refreshing / ready / empty / offline / not-authenticated / destination-missing / invalid / error)
- `ChatValidator` enforces a **safe destination** (an accessible channel the account can post to, with the expected title) — never the title alone, so a same-name channel owned by someone else is rejected
- Repository orchestration (discover → create-if-needed → validate → persist → refresh → manifest upsert with de-dup → state), race-guarded, cancellable, on background dispatchers; **no uploads**
- A functional **Cloud screen** (adaptive grid, video/GIF indicators, preview-on-demand) that **never downloads originals to browse** and distinguishes "empty" from "offline/error"

## Phase 6 — Reliable Backup Engine *(code complete; live upload pending device validation)*

- **Persistent backup queue** in Room (`backup_queue`) — the source of truth for backup state. It survives process death, app restart, and reboot. The **unique `localMediaId`** (the MediaStore id, never the filename) prevents duplicate active rows, so a repeated `enqueue(media)` is idempotent.
- **Explicit state machine** (`NOT_BACKED_UP · QUEUED · PREPARING · UPLOADING · BACKED_UP · FAILED · CANCELLED`, plus the non-terminal `WAITING_FOR_NETWORK` / `WAITING_FOR_AUTH`). The **core invariant** — nothing reaches `BACKED_UP` except from `UPLOADING` — is enforced in `BackupQueueDao`'s SQL guards (every state `UPDATE` is `WHERE state NOT IN ('BACKED_UP', 'CANCELLED', 'FAILED')`, and the `BACKED_UP` write persists the remote identity in the same statement). The only success path is: upload → Telegram confirms → **remote identity persisted → THEN `BACKED_UP`** (the identity write is atomic with the state).
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

## Phase 7 — Backup Recognition & Deduplication *(code complete; remote recognition pending device validation)*

- **SHA-256 content identity** — streaming, cancellable hashing on `Dispatchers.IO` via `MessageDigest("SHA-256")` (no third-party hashing lib, no `readBytes()` of whole files), producing a consistent **64-char lowercase hex** digest persisted across the queue, the remote manifest, and tests. Recognition identity is **content — never filename, id, date, or album** (unit-tested: same bytes under different filenames are duplicates; same filename with different bytes are not).
- **Hash cache with correct invalidation** — a stored hash is reused only while **both** content size and modified timestamp are unchanged; a change in either forces a re-hash. A MediaStore URI is never trusted as the cache key on its own.
- **Content-change-during-hash guard** — the hasher re-checks the provider size after streaming and discards a torn hash rather than persisting one for the wrong file version.
- **Remote manifest is queryable by hash** — `findByContentHash` / `findByContentHashAndSize` (both backed by an explicit `contentHash` **index**), returning a deterministic earliest message id when several equivalent records exist, so recognition never triggers a new upload just because duplicates already exist. The whole manifest is never scanned into memory.
- **Room migration 2 → 3** — adds `contentSizeBytes`, `hashedAt`, and a `contentHash` index to `backup_queue`, plus a `contentHash` index to `cloud_media_manifest`; **no destructive fallback**. A migration test builds a real version-2 database, reopens it at version 3 through the explicit migration, and proves Phase 5/6 rows **and existing hashes survive**.
- **Recognition before enqueue** — the coordinator recognizes each item first: `AlreadyBackedUp` and `Pending` never create a queue row; `NeedsBackup` enqueues carrying the fresh hash (persisted up front); a hash/read failure surfaces `Unavailable` and still attempts backup (never silently skipped, never falsely "backed up"). Cross-identity dedup stops identical bytes reached through two local ids from being queued twice.
- **Reinstall-proof by design** — recognition matches on content hash against the remote manifest, so a fresh install with new MediaStore ids recognizes existing Telegram media without a second upload. (The model and DAO/query boundaries for the future reinstall UI are in place; the UI itself is not.)
- **Phase 6 preserved entirely** — state machine, WorkManager, retry, cancellation, crash recovery, staging, and the `CloudRepository` upload abstraction are untouched; recognition feeds the existing queue rather than replacing it. On a real upload result the content hash is persisted into the manifest as part of success.
- **No TDLib internals leak into recognition** — the engine knows only `CloudRepository`, the manifest, and a remote identity; never `TdApi` / `SendMessage` / `InputFile`. No JSON or JNI was introduced.

## Phase 8 — Photos UI, Timeline, Date Rail & Backup Status *(complete)*

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

## Phase 9 — Cloud, Albums & Full-Screen Viewer *(code complete; live cloud content pending device validation)*

- **Typed media-source navigation** — a `MediaSource` (Local = MediaStore id, Cloud = `chatId` + `messageId`) is the *only* identity that crosses the Viewer boundary, so a Telegram message is never mistaken for a local id and back navigation returns to the originating screen. Photos and album tiles open `viewer/local/{id}`; Cloud tiles open `viewer/cloud/{chatId}/{messageId}`.
- **Full-screen Viewer** for photos, videos, and GIFs: pinch-to-zoom / pan with double-tap reset (pure, unit-tested `ZoomState` math), a labelled source (on-device vs Telegram cloud), the item's date, and previous/next **within the originating collection** — local neighbours come from two cheap `_ID`-bounded MediaStore queries, cloud neighbours from the Room manifest's order. No whole-library load for adjacency.
- **Missing media is honest** — an unknown local id, or a manifest-absent `(chatId, messageId)`, renders a "no longer available" state and never substitutes another item.
- **Video playback via Media3/ExoPlayer** (`PlayerView`), isolated in one opt-in component with proper release; local videos play their content URI directly. **Cloud video has no fake streaming** — there is no verified TDLib streaming path, so a cloud video plays only its explicitly-downloaded original and otherwise shows the honest download step.
- **Cloud originals download only on explicit request** — opening the Viewer never fetches the original; the download action runs `CloudRepository.downloadOriginal`, and success is shown only with a verified local file path. Until the TDLib `DownloadFile` / `GetFile` shapes are device-finalized the result is `Unavailable` (never a fabricated file), and previews remain the browse mechanism.
- **Local Albums from real MediaStore buckets** — `AlbumGrouper` (pure, unit-tested) groups a projection-limited, newest-first query by `BUCKET_ID`: newest item as cover, real counts, provider bucket names with a neutral fallback (never a filename), null-bucket rows skipped. Permission-aware, with distinct loading / genuinely-empty / error states, like Photos.
- **Album contents reuse the exact Photos pipeline** — the same `MediaPagingSource`, mapper, projection, and ordering filtered by bucket, rendered through the same `MediaGrid` (day headers, date rail, backup badges), so an album behaves identically to the timeline. Paging 3 throughout; no full-album memory load.
- **Cloud grid → Viewer wiring** — tapping a cloud tile opens the shared Viewer by remote identity (previously a static grid); animated **GIF playback in the Viewer** via a Coil GIF decoder registered on the app `ImageLoader`.
- **Phases 1–8 preserved** — onboarding, theme, permissions, Photos timeline/date rail/backup status, the backup engine, recognition/dedup, and all prior contracts and tests are untouched; the old untyped viewer route was replaced by the typed source, and nothing else in navigation changed.

## Phase 10 — Map, Media Organization, Restore & Real Telegram Integration *(code complete; live Telegram, map rendering, and MediaStore write/delete pending device validation)*

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
- **Permanent delete** goes through the Android-sanctioned **`MediaStore.createDeleteRequest`** system consent (API 30+), is confirmation-gated, and only forgets an item after a consented success. The app-level trash flag is kept **distinct** from an actual file removal (`deletedFromStore`), and the legacy per-file consent (API 26–29) is documented as unsupported rather than faked. Repository persistence, migration, timeline hidden-id computation, and the collection ViewModel are CI-tested; the **system delete consent is device-only and NOT TESTED**.

**Part D — Restore / download original to device**

- `RestoreRepository`: explicit download of a cloud original, then an **integrity check of the downloaded bytes** (present, non-empty, and readable enough to stream through SHA-256), then publication into public media storage via a `MediaStore` `ContentResolver` insert + streamed copy (correct MIME, scoped `Pictures|Movies/Telepic` path, `IS_PENDING` commit, no overwrite of unrelated files, partial-write cleanup). The computed digest is the *published copy's* identity, **not** a comparison against a trusted manifest hash: Telegram may recompress the stored representation, so comparing the download to the original's hash would reject legitimate restores. A **"Save to device"** action lives in the Viewer for cloud items; success is reported only after a committed publication, and every earlier step is a distinct, honest failure. Repeat restores of the same item are deduplicated by the true remote identity (`chatId` + `messageId`) recorded in an app-owned ledger, never by display name/size/MIME.
- Publishing needs **write access on API ≤ 28** (the scoped-storage columns only exist on API 29+), so the app declares `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="29"` and requests it alongside media access on those versions only.
- CI-tested with a fake cloud source and a fake publisher (success, unavailable/missing, unreadable bytes, low-storage, publish failure, repeat-restore dedup by remote identity, and concurrent restores publishing once). **Real Telegram download and the MediaStore write are device-gated and NOT TESTED**; byte-for-byte preservation of a restored *image* is not claimed (Telegram's photo pipeline may recompress), consistent with the upload note.

**Cross-phase:** Phases 1–9 are preserved; the new tables are additive (non-destructive migrations); ARM-only packaging and `minSdk 26` are unchanged; and GitHub Secrets remain the only credential source.

## Phase 11 — Hardening the paths that were "complete" but not actually whole *(code complete; device validation still outstanding)*

A static review of the whole codebase found a set of defects that no unit test was looking for, because each one sat on a boundary the tests had stubbed. They are grouped here by the user-visible symptom they produced.

**Part A — The Telegram session could not be used again after signing out**

- `logout()` sent `LogOut` and stopped there. TDLib closes its client at `AuthorizationStateClosed` and the Java binding unregisters that client's update handler at the same moment, so the old client could never be replaced: `open()` returned early on the dead reference, and "use a different account" was an unrecoverable dead end that only an app restart fixed. The gateway can now be **reset**, and the closed state clears the account, the parameters latch and the collector — so a later sign-in opens a *fresh* client.
- The account used to survive the sign-out, which meant a re-authorization skipped the `GetMe` round trip and reported the **previous** account as signed in — the backup engine would then have uploaded to the wrong account's channel.
- Four of the five auth submit paths re-published their input screen on *any* error, so a slow or duplicated reply dragged a signed-in session back to the code prompt. Every further attempt was then rejected as "no code pending", the backup engine saw the session as unauthorized, and the whole queue parked. Each path now publishes only while the session is **still** waiting for that input — the guard `publishEmailCode` already had.
- The TDLib database key blob moved from `filesDir` (which Android Auto Backup copies to the cloud, where it is useless and a liability) to `noBackupFilesDir`, beside the database it protects. A key that can no longer be read no longer silently regenerates: the local session **cache** is discarded instead, so the user signs in again rather than finding a dead app.
- Request-level TDLib exceptions are no longer swallowed. The two-argument `Client.send` routes a request failure to the *default* handler, which is debug-gated — so a release build discarded the reason and the caller only saw a 30-second timeout. The three-argument overload installs a per-request handler, and TDLib's own logger now goes through the app's logging seam instead of straight to logcat.

**Part B — The backup engine could fail permanently, or take weeks**

- The staged copy was deleted in a `finally` as soon as `uploadMedia` returned — but TDLib was still *reading that file* while it uploaded. A video that took longer than the confirmation window to be accepted was therefore aborted and marked failed for good. A send that is still uploading is now reported as **pending** rather than as a failure: the staged copy survives, the pending remote identity is kept, and the next pass confirms the same message instead of re-sending it.
- A **rejected** send kept its pending identity, so every later attempt took the *confirm* branch, found the dead message, failed again, and the row could never be uploaded — however many times the user pressed Retry. A permanent failure now clears that identity in the same statement.
- The drain was handed back to WorkManager between batches with `Result.retry()`, which re-runs the *same* WorkSpec — so the exponential backoff grew between batches and pinned at its five-hour cap. A ten-thousand-item library needed a hundred batches, i.e. about **twenty days**. The worker now drains the queue inside one run, and the remainder of a very long queue goes to a **fresh** run (a new spec, a fresh backoff).
- An explicit **cancel** that arrived between the queue fetch and the upload still sent the file: the state write is terminal-guarded, but its affected-row count was discarded. The count is checked now, and a cancelled row sends nothing.
- Five `runCatching` blocks in the worker swallowed `CancellationException` — including around `setForeground` and the queue read — so a stopped worker kept uploading after WorkManager had told it to stop. Every one of them now rethrows the cancellation, like the rest of the codebase already does.
- The staging directory is swept at startup: a process killed between "staged" and "confirmed" left a full-size copy of the media in the cache forever, and nothing else ever removed it.
- The pending-send wait cap now grants exactly the number of waits its name promises, and `WAITING_FOR_AUTH` rows no longer starve the real work out of a batch.

**Part C — Three features that were "complete" but did not do what they promised**

- **User-created albums**: removing a photo wrote the membership row and never invalidated the album's pager, so the photo stayed in the grid behind a success bar. The root cause was shared: the repositories remembered only the *last* paging source they created, so a refresh was lost whenever two pagers were alive — which is also why hiding videos changed the Albums screen but not the Photos timeline. Every live pager is now tracked and invalidated.
- **Permanent delete**: on Android 10 the provider refuses media the app does not own, and the refusal was reported as a failure — so "Delete forever" silently did nothing on a supported release. The platform's own recoverable-consent prompt now travels with the refusal, exactly as API 30 offers it through `createDeleteRequest`.
- **Restore**: a restored photo landed in the timeline under the day it was *restored* rather than the day it was *taken*, silently corrupting the library's chronology. The capture time now travels with the published MediaStore row, and the copy failure — the likely one — leaves a trace instead of vanishing.

**Part D — Cross-cutting**

- Pending and system-trashed MediaStore rows are excluded from every library query, so a half-written row (including Telepic's own restore in progress) never appears in the timeline or in an album's count.
- The empty state's two actions are two separately focusable nodes again. They had been merged into the surrounding text, and a merged semantics node keeps a single `onClick` — the *last* child's — so the secondary action swallowed the primary one. On the Cloud consent card that meant a screen-reader user double-tapping the card ran "create a new channel" instead of "use this one": the one outcome the screen says Telepic must never choose for the user.
- The viewer's swipe targets are read live instead of being captured when the gesture block was created, so a neighbour change under the same item can no longer swipe to an item that is no longer adjacent. The system-bar effect was split in two, so the bars no longer flash into view for a frame on every chrome toggle.
- The Photos search no longer leaves a live MediaStore-backed pager behind for every term the user typed, and the album dialogs survive a rotation like every other dialog in the app.
- The map sweep no longer destroys the stored coordinates of an **archived** photo: the page loader hides archived and trashed media, and the sweep read "absent from the library" as "deleted from the device".
- A gateway timeout is now reported as *offline* rather than as a hard failure, a permanently rejected read is no longer reported as offline, and the two halves of the app no longer disagree about what TDLib's error code 499 means.

**Not changed, and why:** the timeline's sort order is deliberately a non-indexable expression, because the ordering it expresses (capture date, falling back to the modification date) is the ordering the app shows everywhere else, including the Viewer's neighbour seek. Replacing it with an indexed column would silently reorder the library for media without a capture date. The cost is that the provider sorts the media table per page load; it is a known, deliberate trade-off rather than an oversight.

## Phase 12 — File-by-file UI/UX review *(code complete; device validation still outstanding)*

Every file under `ui/`, plus the navigation, permission, theme and settings layers it depends on, was read end to end and checked for layout, responsiveness, accessibility, theme consistency and interaction correctness. This phase is the fixes that came out of it.

**Part A — A security feature that silently did not arm**

- The app lock was resolved **once**, at construction. Turning it on in Settings therefore did nothing for the rest of the session: the app never locked, and `FLAG_SECURE` was never applied, so the Recents snapshot still showed the library. The preference is now observed, and arming it while the app is in the foreground deliberately waits for the next foregrounding — which is what the Settings row promises. The grace window now measures with a monotonic clock, so a clock change while the app is backgrounded can no longer suppress the re-lock.

**Part B — Accessibility defects that are also correctness defects**

- `ErrorState` merged its Retry button into the card's semantics node, so the whole error card became one giant retry target and double-tapping the *title* fired a retry — the same bug `EmptyState` had and had been fixed for. `EmptyState` and `ErrorState` are now consistent.
- `MediaTile` put its description on a *parent* of the clickable node, leaving the actionable node unlabelled and the tile two accessibility stops. It now merges, the way the album card already did.
- The settings section titles and the album section titles were not headings, so the app's most section-heavy screen could not be navigated by heading.
- An informational settings row was dimmed to 2.47:1 (light) / 3.14:1 (dark) — under the 4.5:1 floor — on the app's only readout of *which* Telegram account is connected. A row with no action is now never dimmed, which is what the component's own contract already said.
- The `outline` colour paints the unselected radio glyph on the onboarding choice cards and measured 1.45:1 / 1.66:1 against its own surface. It now measures 3.73:1 / 4.09:1.
- The video duration was the one on-media overlay painted straight onto the photo, with no scrim — invisible on a bright frame. The Map's FAB and scanning chip were the same problem in reverse: theme-surface colours on a fixed **light** OSM tile style, measuring 1.03–1.28:1.

**Part C — States that were not whole**

- An album's failed refresh replaced the whole grid with an error page, so pulling to refresh could throw away the user's photos. It now reports the failure *over* the content, exactly as the Photos timeline does — the two banners are shared instead of one screen having them and the other not.
- Refreshing from the Albums empty or error state swapped it for a skeleton and gave the pull gesture no feedback at all.
- A cloud item's Details sheet could never be opened: the overflow menu that carries it sat behind a local-only gate. The local-only actions stay local-only; the menu does not.
- The Viewer's "Download original" was dead code — the bar took the first matching branch and the restore action was always present — so a cloud photo's full-resolution original could never be fetched.
- A cloud refresh cleared every *successfully loaded* preview, flashing the grid back to placeholders and re-downloading every thumbnail.
- An unrecognised backup state was labelled "Queued", claiming the row is in the upload pipeline; the "Stalled" rule was also re-derived in the screen instead of reusing the one the stat card uses.
- The Map announced its *cluster* count as a photo count, so it contradicted the count in its own header.

**Part D — Interactions that were broken**

- The photo viewer consumed every pointer event from the first pixel of movement, so a one- or two-pixel finger jitter cancelled the tap detector and the chrome toggle — the screen's primary interaction — stopped firing. The gesture now claims the pointer only past the touch slop, as the video path always did.
- A long-press speed boost was released only on a clean pointer-up, so a cancelled press left the video running at 2x with no visible cue.
- The video transport did not count as an interaction, so the chrome could auto-hide *while the seek slider was being dragged* and take the slider with it. The gesture HUD's auto-dismiss also never restarted for a repeated value, so it vanished mid-drag.
- The "Saved" tick was still a live button and re-ran the whole download, verify and publish pipeline.
- The selection header could push "Select all" off the screen at a large display font, and the media grid reserved no room for the selection bar that covers its bottom row.
- The onboarding steps lost their own state on a step change (a typed phone number was gone when the user came back), the Telegram step still offered "Set up later" after the account was connected, and a failed *completion* was answered with the backup copy — pointing at the wrong control.
- The "have we asked for media access" fact was a per-composition flag, so every screen had its own: a user who permanently denied on the Map tab was told "Allow access" on the Photos tab.
- The media grid took raw `Map` and `Set` parameters. Both are interfaces, so the compiler infers them as unstable and the grid could never be skipped — it re-ran on every backup-progress tick, taking the date rail with it. The snapshots are value types with content equality now.
- The country table was missing several very large countries — **India** above all — so a user whose SIM was Indian got the United States default. "00 44 20 …" is also a British number whatever the dropdown says, and it was being turned into a thirteen-digit US number.

**Part E — Consistency**

- The Map and the Albums screen did not say when media access was *partial*, so the same library looked complete on the Photos tab and partial on the other two.- The licence list named versions several releases behind the ones actually shipped, and omitted `androidx.biometric` — a missing notice for a component that is in the APK.
- The notification permission counted *launches* as attempts, so one denial could exhaust the budget and send the user to Settings while the platform would still have shown the dialog.
- The app shell's nav controller had a default, so a caller that omitted it silently got a second one; it is now required, which makes the ownership (hoisted in the root, above the lock gate) explicit.
- The app lock's biometric prompt was never cancelled, so it outlived the screen and its callback could fire into a disposed composition.
- The map framed its photos for a hard-coded 1080px viewport — too far out on a narrow phone, too far in on a tablet — and now uses the real width.
- The Organization screen's message bar was keyed on the message *value*, so two identical failures inside the dismissal window produced no second bar, no new timer and no reject haptic.

## Phase 13 — The map's photo-point UI *(code complete; device validation still outstanding)*

The map's markers and its tapped-pin preview were the last place in the app that still looked like a default Compose screen. The fixes keep the app's own visual language and its honesty rules.

**The markers are now one system**

- A **single photo** used to fall back to osmdroid's *stock* teardrop marker — a different colour and a different weight from the cluster bubbles — so the same map carried two unrelated visual languages. Both are drawn now: a teardrop for a single photo (it points *at* a spot) and a count bubble for a cluster, in the same brand blue with the same white ring.
- The fill is **fixed, not themed**, for the same reason the recentre button and the scanning chip are: the tiles are OSM Mapnik, a fixed **light** style that does not follow the app theme, and the dark scheme's light-blue `primary` measured about **1.9:1** against a typical land tile, so the clusters all but disappeared. The brand blue measures 4.7:1.

**The preview sheet is a card, not a column of loose elements**

- It was a bare cropped image with a coordinate string and an icon-less button under it — it read as debug output rather than UI. It is now a titled card: the photo in a rounded frame that shows a spinner while it decodes and says so when it cannot be shown (it used to leave a 180dp hole), the place as a labelled fact at the precision a pin can actually place (five decimals, about a metre, instead of six), the photo itself as a way into the Viewer, and one full-width primary action.
- The coordinates were also re-read with a scan over the whole geotagged library on every frame of the sheet's animation; they come from a map now.
- The OSM attribution is a **link** to their copyright page, which their tile usage policy asks for.

**Known limits, unchanged**

- osmdroid markers are canvas overlays with no accessibility node, so a screen reader meets the map as one surface that announces the photo count. The individual pins are reachable by touch and by the preview sheet; making them navigable would need a list of the geotagged photos beside the map.
- A video pin shows a still frame with no indication that it is a video: the map's data model carries only `(mediaId, location, contentUri)`, so the sheet genuinely cannot tell.
