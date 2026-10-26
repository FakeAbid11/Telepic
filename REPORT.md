# Telepic — Static Technical & Product Review

**Repository:** `FakeAbid11/Telepic`  
**Reviewed:** 26 October 2026  
**Method:** static review only. No Gradle invocation, build, test run, emulator, or device execution. Every claim below is anchored to the current tree. This report was produced independently from the code.

**Measured baseline**

| Metric | Value |
|---|---|
| Main Kotlin files / LOC | 205 / 31,050 |
| Test files / LOC | 125 / 19,071 |
| `@Test` / `@Ignore` | 909 / 0 |
| `androidTest` source set | none |
| XML layouts | 0 |
| `!!` in main | 0 |
| `GlobalScope` / `runBlocking` | 0 / 0 |
| Direct `android.util.Log` calls | 2 (both inside the logging seam) |
| String resources | 464 |
| Room version / migrations | 11 / 10 |
| Interfaces in main | 73 |

---

## Critical findings first

1. **The app lock can fail open on its own recovery path.** After an unreadable preference puts the app into `RecoveryRequired`, tapping “Retry” and successfully reading `Known(enabled = true)` publishes `Unlocked`, not `Locked` ([AppLockViewModel.kt:109-158](app/src/main/java/com/telepic/settings/AppLockViewModel.kt#L109)). The gallery opens without a credential prompt even though the lock is on. The existing test covers only `Known(false)` ([AppLockViewModelTest.kt:109-116](app/src/test/java/com/telepic/settings/AppLockViewModelTest.kt#L109)).
2. **“Delete forever” can forget files it did not delete on Android 10.** The API 29 batch path deletes item-by-item and returns consent for the first refusal; the ViewModel records the whole selection as consented and removes every row on success ([LocalMediaDeleter.kt:124-142](app/src/main/java/com/telepic/data/organization/LocalMediaDeleter.kt#L124), [OrganizationViewModel.kt:253-285](app/src/main/java/com/telepic/ui/screens/organization/OrganizationViewModel.kt#L253)).
3. **The cloud destination is trusted forever and is not account-scoped.** Logout clears in-memory Telegram state only; the destination row, manifest, queue and restore ledger survive, and `ensureDestination()` returns the stored chatId without revalidation ([TelegramCloudRepository.kt:272-275](app/src/main/java/com/telepic/data/cloud/TelegramCloudRepository.kt#L272), [CloudDaos.kt:17-18](app/src/main/java/com/telepic/data/cloud/db/CloudDaos.kt#L17), [SettingsViewModel.kt:314-334](app/src/main/java/com/telepic/ui/screens/settings/SettingsViewModel.kt#L314)).
4. **The release pipeline cannot produce the signed AAB the README promises.** `release-smoke` never passes `RELEASE_*` environment variables, so `signingConfigs` is never created even when the secrets exist; the job also injects placeholder Telegram credentials, defeating the documented release gate ([android-ci.yml:138-143](.github/workflows/android-ci.yml#L138), [app/build.gradle.kts:45](app/build.gradle.kts#L45), [README.md:190-193](README.md#L190)).

The code itself is unusually disciplined: zero `!!`, zero `GlobalScope`/`runBlocking`, no committed secrets, 909 tests, explicit Room migrations, SQL-enforced queue transitions, and a documented “never fabricate success” rule. The defects above are concentrated in recovery/security edge cases, scale, accessibility, and release engineering — not in everyday coding hygiene.

---

## 1. App Overview

### What the app does

Telepic is a **local-first Android photo/video gallery whose remote backup target is the user’s own private Telegram channel**, accessed through TDLib. There is no bespoke cloud backend, no account system, no analytics SDK, and no advertising. Media stays on device; backup is opt-in; cloud browsing is preview-only by design; restore is an explicit “Save to device” action.

### Target platform

- Android only, `minSdk 26` (Android 8.0), `compileSdk`/`targetSdk` 36 ([app/build.gradle.kts:49-58](app/build.gradle.kts#L49)).
- ARM-only ABI filter (`arm64-v8a`, `armeabi-v7a`) because of TDLib native libraries ([app/build.gradle.kts:66-68](app/build.gradle.kts#L66)).
- Java/Kotlin target 17; single debug-milestone version `0.1.0` (`versionCode 1`).
- Phone and tablet/foldable layouts; an adaptive rail replaces the bottom bar at ≥600dp ([TelepicApp.kt:68-101](app/src/main/java/com/telepic/ui/TelepicApp.kt#L68)).

### Core user flows

1. **First run** — six-step onboarding: Welcome → How It Works → Permissions → Telegram login (optional) → Backup choice → Ready ([OnboardingScreen.kt:70-157](app/src/main/java/com/telepic/ui/onboarding/OnboardingScreen.kt#L70)).
2. **Browse** — Photos timeline (paged MediaStore stream, day headers, date rail, search); Albums (MediaStore folders + user-created albums); Map (EXIF GPS only, osmdroid).
3. **View** — photo/GIF/video viewer with zoom, pan, swipe navigation scoped to the originating collection, and a details sheet.
4. **Organise** — favourite, archive, trash, undo, bulk actions, user-created albums.
5. **Back up** — recognition and content-hash dedup → Room queue → WorkManager foreground worker → TDLib send → confirmed remote identity.
6. **Cloud / restore** — browse the channel manifest, download previews/originals, explicitly save an original back into MediaStore.
7. **Settings / security** — theme, backup transport, media filters, library counts, cache clearing, licences, app lock.

### Architecture pattern

**Single-module, single-activity, Compose-only MVVM with unidirectional data flow and a repository layer.** It is not strict Clean Architecture: `domain/` contains pure models and state machines, but there is no use-case/interactor layer, and ViewModels call repositories directly. It is not MVI either: UI events are ordinary ViewModel method calls, with state exposed as `StateFlow`/Paging streams.

```
Compose screens
   ↓ events / state
ViewModel (StateFlow, Paging3)
   ↓
Repository interface  →  Default / Android implementation
   ↓
MediaStore | Room | DataStore | TDLib | WorkManager | filesystem
```

- **DI:** hand-written `DefaultAppContainer` with `by lazy` singletons; no Hilt/Koin ([AppContainer.kt:75-136](app/src/main/java/com/telepic/di/AppContainer.kt#L75)).
- **Navigation:** Navigation Compose with typed route objects (`ViewerRoute`, `AlbumRoute`, `OrganizationRoute`); the NavHost wires 11 ViewModels inline and is effectively the composition root ([TelepicNavHost.kt:115-330](app/src/main/java/com/telepic/navigation/TelepicNavHost.kt#L115)).
- **Storage:** Room v11 for queue/manifest/organisation/location/albums; Preferences DataStore for settings/onboarding/cursors/ledger.
- **Background:** WorkManager with a foreground `dataSync` worker; one long-running queue drain per run.
- **Cloud:** one shared TDLib client for both auth and cloud; manual state machine.
- **Rendering:** Coil for thumbnails/GIF/video frames, Media3 ExoPlayer for video, osmdroid for maps.

**Product position:** privacy-oriented Telegram users who want local-first storage with an off-device copy. The main product dependencies are Telegram’s API and channel semantics; the main product limitations are English-only UI, no editing/sharing/casting, no multi-account, no non-Telegram backend, and no end-to-end encryption beyond Telegram’s own cloud model.

---

## 2. Feature Inventory

State definitions: **Complete** = implemented end-to-end and covered by JVM/Robolectric tests; **Device-gated** = code complete but the real TDLib/MediaStore/render/credential path has never run on hardware; **Partial** = implemented with a known functional or accessibility gap; **Not implemented** = no code path.

| # | Feature / screen | State | Evidence and approach |
|---:|---|---|---|
| 1 | Six-step onboarding | Complete | Compose + DataStore; `OnboardingViewModel`; step routing in [OnboardingScreen.kt](app/src/main/java/com/telepic/ui/onboarding/OnboardingScreen.kt) |
| 2 | Version-aware media permissions | Complete (prompt behaviour device-gated) | `ActivityResultContracts` + SDK policy in [MediaPermissionPolicy.kt](app/src/main/java/com/telepic/permissions/MediaPermissionPolicy.kt) |
| 3 | Notification permission | Complete (device behaviour unvalidated) | [NotificationPermissionController.kt](app/src/main/java/com/telepic/permissions/NotificationPermissionController.kt) / policy |
| 4 | Photos timeline | Complete | Paging 3 over `MediaStore.Files`, day headers, stable keys; [PhotosViewModel.kt](app/src/main/java/com/telepic/ui/screens/photos/PhotosViewModel.kt) |
| 5 | Search (filename + folder) | Complete (no date/place search) | Same query + `LIKE … ESCAPE`; [MediaSelectionBuilder.kt:75-82](app/src/main/java/com/telepic/data/media/MediaSelectionBuilder.kt#L75) |
| 6 | Date rail / scrubber | Complete | Pure Compose + derived state; `DateScrubber.kt`, `DateRailWindow.kt` |
| 7 | Backup status badges | Complete (scales poorly) | Batched Room projection; [BackupStatusRepository.kt](app/src/main/java/com/telepic/data/backup/BackupStatusRepository.kt) |
| 8 | Device-folder albums | Complete | MediaStore bucket grouping; `MediaStoreAlbumRepository.kt` |
| 9 | User-created albums | Complete (stale membership after permanent delete) | Room `custom_album` + `custom_album_media` |
| 10 | Album contents | Complete (no permission state) | Reuses the timeline pager; `AlbumContentsScreen.kt` |
| 11 | Viewer — photo/GIF | Complete (device rendering unvalidated) | Compose gestures + Coil; [ViewerScreen.kt](app/src/main/java/com/telepic/ui/screens/viewer/ViewerScreen.kt) |
| 12 | Viewer — video | Complete (device playback unvalidated) | Media3 ExoPlayer + custom transport/gestures; `VideoViewer.kt` |
| 13 | Map | Partial — device-gated, online-only, inaccessible pins | osmdroid + pure `MapClusterer`; `MapScreen.kt` |
| 14 | Favourites / Archive / Trash | Complete; permanent delete device-gated and API 29-buggy | Room organisation table + `MediaStore.createDeleteRequest` / recoverable consent |
| 15 | Bulk actions | Complete; per-item writes | Shared `BulkOrganizationController`; [BulkOrganization.kt](app/src/main/java/com/telepic/ui/components/BulkOrganization.kt) |
| 16 | Telegram authentication | Complete code / device-gated | TDLib phone, code, 2FA, email-code flows; `TelegramSessionManager.kt` |
| 17 | Cloud browse / preview | Complete code / device-gated; single account | TDLib history + Room manifest; `CloudScreen.kt` |
| 18 | Backup engine | Complete code / device-gated | Room queue + WorkManager foreground worker; `data/backup/**` |
| 19 | Recognition / SHA-256 dedup | Complete code; remote half device-gated | Streaming SHA-256 + caption marker; [CloudBackupMarker.kt](app/src/main/java/com/telepic/domain/cloud/CloudBackupMarker.kt) |
| 20 | Restore / Save to device | Complete code / device-gated | Download, verify, publish via MediaStore |
| 21 | Backup Center | Complete | Queue flows, retry/cancel/start; `BackupCenterScreen.kt` |
| 22 | Settings | Complete | DataStore + ViewModel; `SettingsScreen.kt` |
| 23 | App lock + `FLAG_SECURE` | Partial — recovery bypass, prompt device-gated | `BiometricPrompt` + `KeyguardManager`; [AppLockViewModel.kt](app/src/main/java/com/telepic/settings/AppLockViewModel.kt) |
| 24 | Media Details | Complete | `ExifInterface`; `MediaDetailsSheet.kt` |
| 25 | Storage accounting / licences | Complete | File walk + hand-maintained licence list |
| 26 | Share / edit / cast | Not implemented | No `ACTION_SEND`, editing, or casting pipeline |

---

## 3. Code Quality Review

### Defect register (verified statically)

| ID | Sev | Evidence | Defect and impact |
|---|---|---|---|
| D1 | **Critical** | [AppLockViewModel.kt:109-158](app/src/main/java/com/telepic/settings/AppLockViewModel.kt#L109) | After `RecoveryRequired`, `retry()` → `Loading`; a successful `Known(true)` hits the final `else -> Unlocked` because `firstResolution` is already false. The gallery opens without authentication. The init collector also completes on the first read error, so later lock toggles are not observed. |
| D2 | High | [LocalMediaDeleter.kt:124-142](app/src/main/java/com/telepic/data/organization/LocalMediaDeleter.kt#L124), [OrganizationViewModel.kt:253-285](app/src/main/java/com/telepic/ui/screens/organization/OrganizationViewModel.kt#L253) | On API 29, the loop deletes some items, then returns `NeedsConsent` for one refusal. The ViewModel treats the entire selection as consented and removes every row on success, including files never attempted. “Deleted” may be false. |
| D3 | High | [TelegramCloudRepository.kt:272-275](app/src/main/java/com/telepic/data/cloud/TelegramCloudRepository.kt#L272), [CloudDaos.kt:17-18](app/src/main/java/com/telepic/data/cloud/db/CloudDaos.kt#L17), [SettingsViewModel.kt:314-334](app/src/main/java/com/telepic/ui/screens/settings/SettingsViewModel.kt#L314) | The destination, manifest, queue and restore ledger are not account-scoped or cleared on logout. A stale chatId is trusted forever; after an account switch or channel deletion, cloud/backup fail with no in-app recovery and old metadata can be shown to the next account. |
| D4 | High | [BackupStager.kt:105-116](app/src/main/java/com/telepic/data/backup/BackupStager.kt#L105), [DefaultBackupRepository.kt:293-297](app/src/main/java/com/telepic/data/backup/DefaultBackupRepository.kt#L293), [AppContainer.kt:357](app/src/main/java/com/telepic/di/AppContainer.kt#L357) | Every non-cancellation staging failure — including ENOSPC while copying into `cacheDir` — becomes `null` and then a permanent `FAILED` “Local media is no longer available”. No free-space preflight; no transient retry. |
| D5 | High | [BackupQueueDao.kt:31](app/src/main/java/com/telepic/data/backup/db/BackupQueueDao.kt#L31), [BackupStatusRepository.kt:27-43](app/src/main/java/com/telepic/data/backup/BackupStatusRepository.kt#L27), [PhotosViewModel.kt:99-106](app/src/main/java/com/telepic/ui/screens/photos/PhotosViewModel.kt#L99) | `observeStatusRows()` selects the whole queue (including every historical `BACKED_UP` row). The full map is rebuilt per emission in the ViewModel collector, which runs on `Main.immediate`. Rows are never pruned, so cost grows with the library and every queue write. |
| D6 | High | [MediaPagingSource.kt:24](app/src/main/java/com/telepic/data/media/MediaPagingSource.kt#L24), [MediaStoreMediaLoader.kt:60-86](app/src/main/java/com/telepic/data/media/MediaStoreMediaLoader.kt#L60), [MediaSortOrder.kt:18-21](app/src/main/java/com/telepic/data/media/MediaSortOrder.kt#L18) | Offset paging over `COALESCE(NULLIF(DATE_TAKEN,0), DATE_MODIFIED*1000)`, which cannot use an ordinary index. Each page re-sorts/steps the matching set; inserts at the head shift offsets, causing duplicates/skips. `getRefreshKey` returns null, so invalidation jumps to the top. |
| D7 | High | [MediaSelectionBuilder.kt:84-94](app/src/main/java/com/telepic/data/media/MediaSelectionBuilder.kt#L84), [MediaStoreAlbumRepository.kt:84-93](app/src/main/java/com/telepic/data/media/MediaStoreAlbumRepository.kt#L84) | Archived/trashed ids are inlined as an unbounded `NOT IN (...)` literal and rebuilt per page and per album scan. SQL length and provider parse cost grow with the hidden set. |
| D8 | High | [android-ci.yml:138-143](.github/workflows/android-ci.yml#L138), [app/build.gradle.kts:45](app/build.gradle.kts#L45), [README.md:190-193](README.md#L190) | `release-smoke` never passes `RELEASE_KEYSTORE_*`; the AAB is always unsigned even if the secrets are configured. It also supplies placeholder Telegram credentials, so the release credential gate is bypassed. |
| D9 | High | [ViewerScreen.kt:461-575](app/src/main/java/com/telepic/ui/screens/viewer/ViewerScreen.kt#L461), [MapScreen.kt:429-491](app/src/main/java/com/telepic/ui/screens/map/MapScreen.kt#L429), [SelectionUi.kt:224-230](app/src/main/java/com/telepic/ui/components/SelectionUi.kt#L224) | Accessibility blockers: photo next/previous exists only as a horizontal swipe with no semantics actions; map pins are canvas overlays with only a single “N locations” node; the selection overflow button has no content description. |
| D10 | High | [LibraryBanners.kt:66-92](app/src/main/java/com/telepic/ui/components/LibraryBanners.kt#L66), [PermissionsStep.kt:102-106](app/src/main/java/com/telepic/ui/onboarding/steps/PermissionsStep.kt#L102), [strings.xml:215](app/src/main/res/values/strings.xml#L215) | Partial access (“Select photos”) is text-only. The copy sends users to Settings, but Settings has no photo-permission row and no screen re-opens the picker. The user is permanently limited to the initial selection unless they find Android’s app settings manually. |
| D11 | High | [TelepicLog.kt:20-56](app/src/main/java/com/telepic/diagnostics/TelepicLog.kt#L20), [TelepicApplication.kt:109-119](app/src/main/java/com/telepic/TelepicApplication.kt#L109), [TelegramSessionManager.kt:298-306](app/src/main/java/com/telepic/telegram/TelegramSessionManager.kt#L298), [TelegramCloudRepository.kt:205-219](app/src/main/java/com/telepic/data/cloud/TelegramCloudRepository.kt#L205) | No durable log sink, no `ApplicationExitInfo`, no crash/telemetry channel. TDLib auth rejections and cloud failures are recorded only in in-memory/UI state; ANR/native deaths leave nothing. “Backup stuck” and “login loop” are not diagnosable from the field. |
| D12 | Medium | [OrganizationViewModel.kt:288-303](app/src/main/java/com/telepic/ui/screens/organization/OrganizationViewModel.kt#L288), [CustomAlbumDao.kt:20-28](app/src/main/java/com/telepic/data/media/db/CustomAlbumDao.kt#L20), [MapLocationRepository.kt:150-157](app/src/main/java/com/telepic/data/media/MapLocationRepository.kt#L150) | Permanent delete removes only the organisation row. Custom-album membership/count/cover persists permanently; location rows survive until a full map sweep, producing ghost album counts and map pins for files that no longer exist. |
| D13 | Medium | [BackupWorkScheduler.kt:84-88](app/src/main/java/com/telepic/data/backup/work/BackupWorkScheduler.kt#L84), [BackupWorker.kt:186-206](app/src/main/java/com/telepic/data/backup/work/BackupWorker.kt#L186) | `KEEP` discards a schedule requested while a run is finishing. Rows that become actionable after the worker’s final batch (login completes, user retry) can remain parked until process restart or a manual “Start”. |
| D14 | Medium | [BackupCoordinator.kt:263-291](app/src/main/java/com/telepic/data/backup/BackupCoordinator.kt#L263) | A failed discovery page returns before `persistCursor`. The comment says the next pass resumes at the failed page, but the old offset is retained, so a permanently failing page pins the scan at the pass start. |
| D15 | Medium | [TelegramCloudRepository.kt:171-221](app/src/main/java/com/telepic/data/cloud/TelegramCloudRepository.kt#L171), [BackupCoordinator.kt:107](app/src/main/java/com/telepic/data/backup/BackupCoordinator.kt#L107) | `networkMutex` is held across up to 200 history pages; `prepare()` walks history before every automatic pass and every manual backup. Previews/downloads queue behind the whole walk. |
| D16 | Medium | [ViewerViewModel.kt:708-722](app/src/main/java/com/telepic/ui/screens/viewer/ViewerViewModel.kt#L708), [CloudDaos.kt:23-28](app/src/main/java/com/telepic/data/cloud/db/CloudDaos.kt#L23) | Every cloud Viewer open/swipe calls `cloudRepository.media.first()` — the entire manifest, sorted and mapped — then uses `indexOfFirst`. Browsing is O(library) per item. |
| D17 | Medium | [BulkOrganization.kt:96](app/src/main/java/com/telepic/ui/components/BulkOrganization.kt#L96), [MediaOrganizationRepository.kt:82-92](app/src/main/java/com/telepic/data/organization/MediaOrganizationRepository.kt#L82), [PhotosViewModel.kt:389-393](app/src/main/java/com/telepic/ui/screens/photos/PhotosViewModel.kt#L389) | Bulk favourite/trash/undo writes one transaction per id; each emission invalidates the pager, so a large selection produces a long write burst and a paging invalidation storm. |
| D18 | Medium | [MapLocationRepository.kt:92-157](app/src/main/java/com/telepic/data/media/MapLocationRepository.kt#L92) | Every map open re-pages the whole library (up to 2,000 × 120 items); the prune deletes rows one at a time. Already-scanned items are skipped for EXIF but not for the MediaStore query. |
| D19 | Medium | [TdLibCloudDataSource.kt:247-256](app/src/main/java/com/telepic/data/cloud/TdLibCloudDataSource.kt#L247), [TelegramCloudRepository.kt:45-48](app/src/main/java/com/telepic/data/cloud/TelegramCloudRepository.kt#L45), [TdLibCloudDataSource.kt:389-395](app/src/main/java/com/telepic/data/cloud/TdLibCloudDataSource.kt#L389) | “Message is missing” is decided by English substring matching; localization or a TDLib wording change can flip the outcome. Flood-wait `retryAfterSeconds` is computed and documented but never consumed by the queue, which uses fixed WorkManager backoff. |
| D20 | Medium | [ViewerScreen.kt:191-197](app/src/main/java/com/telepic/ui/screens/viewer/ViewerScreen.kt#L191) | Neighbour prefetch enqueues `ImageRequest` with no size, so Coil decodes full-resolution neighbours into the memory cache; two large photos per swipe can reach hundreds of MB. |
| D21 | Medium | [RestoreRepository.kt:135-137](app/src/main/java/com/telepic/data/restore/RestoreRepository.kt#L135), [RestoreLedger.kt:58-64](app/src/main/java/com/telepic/data/restore/RestoreLedger.kt#L58), [ViewerViewModel.kt:586-590](app/src/main/java/com/telepic/ui/screens/viewer/ViewerViewModel.kt#L586) | The file is already published when `ledger.record()` runs. A DataStore write failure propagates and is mapped to `DOWNLOAD_UNAVAILABLE`; the user is told the save failed, and a retry publishes a second copy. |
| D22 | Medium | [BackupRecognitionRepository.kt:104-127](app/src/main/java/com/telepic/data/backup/BackupRecognitionRepository.kt#L104), [CloudDaos.kt:92-97](app/src/main/java/com/telepic/data/cloud/db/CloudDaos.kt#L92) | For pre-marker rows, a filename + exact size match is treated as proof the remote bytes exist; the local hash is then stamped onto the remote row. A same-name/same-size different file can make Telepic skip a real upload and later restore the wrong file. |
| D23 | Medium | [ChatValidator.kt:41-49](app/src/main/java/com/telepic/domain/cloud/ChatValidator.kt#L41), [CloudScreen.kt:222-228](app/src/main/java/com/telepic/ui/screens/cloud/CloudScreen.kt#L222) | The only consent before using a postable-but-unowned channel is a non-unique title. The UI does not show chat id, username, member count, creation date, or whether the channel is public. A same-named attacker channel is indistinguishable. |
| D24 | Medium | [TelegramStep.kt:140](app/src/main/java/com/telepic/ui/onboarding/steps/TelegramStep.kt#L140) | Phone number, SMS code, email and emailed code use `rememberSaveable`; saved instance state is persisted by the system on process death. The 2FA password is correctly excluded, but the other credentials/PII are not. |
| D25 | Medium | [MediaPermissionPolicy.kt:67-72](app/src/main/java/com/telepic/permissions/MediaPermissionPolicy.kt#L67), [PhotosScreen.kt:208-228](app/src/main/java/com/telepic/ui/screens/photos/PhotosScreen.kt#L208) | On Android 13, granting `READ_MEDIA_IMAGES` but denying `READ_MEDIA_VIDEO` resolves to `Denied` because `fullAccess` requires both. The Photos screen hides readable images behind a permission gate. |
| D26 | Medium | [strings.xml:277-278](app/src/main/res/values/strings.xml#L277), [BackupCoordinator.kt:179-187](app/src/main/java/com/telepic/data/backup/BackupCoordinator.kt#L179), [BackupNetworkPreference.kt:24-26](app/src/main/java/com/telepic/settings/BackupNetworkPreference.kt#L24) | The consent card says “Back up all photos”, but `BACKUP_ALL` discovers videos and GIFs too. The default transport is `ANY_NETWORK` (mobile data included), and onboarding never asks about network or cost. |

**Additional verified low-severity issues**

- Undated media resolves to epoch 0 and appears under a 1970 header ([MediaDateResolver.kt:11-15](app/src/main/java/com/telepic/data/media/MediaDateResolver.kt#L11), [MediaPagingSource.kt:38-44](app/src/main/java/com/telepic/data/media/MediaPagingSource.kt#L38)).
- A null-MIME video is classified as a photo and given an Images content URI ([MediaStoreMediaMapper.kt:17-19](app/src/main/java/com/telepic/data/media/MediaStoreMediaMapper.kt#L17)).
- Concurrent favourite/archive toggles can overwrite each other’s flags in the Viewer ([ViewerViewModel.kt:496-530](app/src/main/java/com/telepic/ui/screens/viewer/ViewerViewModel.kt#L496)).
- TDLib history paging repeats the boundary message on every page ([TdLibCloudDataSource.kt:97-104](app/src/main/java/com/telepic/data/cloud/TdLibCloudDataSource.kt#L97)).
- Clearing the backup cache deletes staging files a running upload may still be reading ([SettingsViewModel.kt:426-432](app/src/main/java/com/telepic/ui/screens/settings/SettingsViewModel.kt#L426)).
- Dead APIs/columns: `enqueueAll`, coordinator observe helpers, `mediaIds`, `markDeletedFromStore`/`deletedFromStore`, `GpsCoordinates`, `TdLibClientGateway.isOpen` ([BackupRepository.kt:28](app/src/main/java/com/telepic/data/backup/BackupRepository.kt#L28), [BackupCoordinator.kt:42-43](app/src/main/java/com/telepic/data/backup/BackupCoordinator.kt#L42), [MediaOrganizationRepository.kt:36](app/src/main/java/com/telepic/data/organization/MediaOrganizationRepository.kt#L36), [GpsCoordinates.kt:11](app/src/main/java/com/telepic/data/media/GpsCoordinates.kt#L11)).
- Docs drift: test count says 882/123 but the tree has 909/125; roadmap says Phases 11–12 are next while the phase log reaches 14; the release-credential claim is false; only schema JSONs 9–11 exist; there is no release runbook ([README.md:496](README.md#L496), [README.md:466](README.md#L466), [README.md:519-522](README.md#L519)).
- `androidx.fragment` is used by `MainActivity`/`AppLockScreen` but not declared; it compiles only through the biometric dependency ([MainActivity.kt:11](app/src/main/java/com/telepic/MainActivity.kt#L11)).
- `androidx.room:room-testing` is declared but unused; `MigrationTestHelper` is not used.
- The foreground backup run has no wall-clock budget and can hit Android 15’s 6-hour `dataSync` cap ([BackupWorker.kt:190-198](app/src/main/java/com/telepic/data/backup/work/BackupWorker.kt#L190)).

### 3.1 UI / Compose layer

**Strengths**

- 100% Compose + Material 3 with a real token system (`Color`, `Type`, `Shape`, `Spacing`, `TelepicTokens`); no XML layouts and no hardcoded `Text("…")` literals — 464 string resources.
- Adaptive navigation (bottom bar → rail at 600dp), reduced-motion handling, shared-element transitions, skeletons, and honest empty/error/partial states.
- Accessibility foundation is better than most apps: tiles merge semantics, announce type/date/backup state and selection, contrast is tested, and icon-only controls usually have descriptions ([MediaTile.kt:106](app/src/main/java/com/telepic/ui/screens/photos/MediaTile.kt#L106)).
- The UI does not infer backup success or fabricate counts; “unknown” and “loading” are distinct states.

**Weaknesses**

- **God screens:** `ViewerScreen.kt` is 1,057 lines and mixes window/immersive control, Coil cache warming, the photo gesture engine, and all chrome bars. `MapScreen.kt` and `VideoViewer.kt` are 813 lines each; `SettingsScreen.kt` is 674. Restore is rendered twice, with different affordances for photo and video.
- **Accessibility blockers** (D9): the Viewer has no accessible next/previous action; map pins are unreachable canvas overlays; the selection overflow button announces an unnamed button.
- **Partial access dead end** (D10): the banner promises a Settings action that does not exist.
- **Cloud tiles are indistinguishable to TalkBack** — every tile announces only “Cloud photo/video/GIF” with no date or index.
- **Backup consent copy is wrong** (D26): “photos” includes videos.
- **English-only** despite localized date formatting; no `values-xx` resources. This is documented nowhere in the limitations section.

### 3.2 ViewModel / state management

**Strengths**

- Consistent `StateFlow` + `SharingStarted.WhileSubscribed(5_000)`; Paging is `cachedIn(viewModelScope)` after `flatMapLatest`, avoiding per-search leaked pagers ([PhotosViewModel.kt:89-93](app/src/main/java/com/telepic/ui/screens/photos/PhotosViewModel.kt#L89)).
- Explicit sealed/loaded/error states instead of nullable booleans: `OrgItemsState`, `BackupUiState.isLoaded/loadFailed`, `AppLockState` ([BackupViewModel.kt:38-60](app/src/main/java/com/telepic/ui/screens/backup/BackupViewModel.kt#L38)).
- Cancellation is rethrown consistently; user actions are guarded so a tap cannot crash the process.
- Busy flags and event ids prevent double-submits and conflated messages.

**Weaknesses**

- **Optional production dependencies mask wiring bugs.** `PhotosViewModel` accepts four nullable collaborators; `ViewerViewModel` has 12 constructor parameters and six nullable collaborators ([ViewerViewModel.kt:185-205](app/src/main/java/com/telepic/ui/screens/viewer/ViewerViewModel.kt#L185)). A forgotten argument disables a feature silently instead of failing at construction.
- **Selection and folder-picker logic is duplicated** across Photos, Album Contents and Organization, and between Onboarding and Settings.
- **Business policy lives in screen ViewModels** — e.g. the MediaStore-change backup trigger and debounce belong to Photos, not to the backup layer ([PhotosViewModel.kt:406-421](app/src/main/java/com/telepic/ui/screens/photos/PhotosViewModel.kt#L406)).
- D1 is a state-machine defect in the security-critical ViewModel.
- A concurrent favourite/archive toggle can overwrite the other flag (see Additional verified low-severity issues).

### 3.3 Data layer / repositories

**Strengths**

- Repository interface + `Default*` implementation is used consistently; the backup state machine is enforced in SQL guards, not UI branches ([BackupQueueDao.kt:89-239](app/src/main/java/com/telepic/data/backup/db/BackupQueueDao.kt#L89)).
- The core invariant is real: `BACKED_UP` is written in the same statement as the remote identity, and a cancelled row cannot be resurrected by a late worker.
- Content-addressed dedup uses streaming SHA-256 with cancellation checkpoints; staging streams rather than buffering whole files.
- MediaStore mapping is defensive about missing columns, null MIME, EXIF failures and chunked id lookups.
- Room has explicit migrations, no destructive fallback, and tests that reopen the real database through `Room.databaseBuilder`.

**Weaknesses**

- **Media query scalability** is the biggest structural weakness: unbounded `NOT IN`, offset paging over an expression sort, and full-library album/group scans (D6, D7, D18).
- **Permanent deletion has no single owner.** The ViewModel deletes the MediaStore row and the organisation row itself; custom-album membership, location cache and queue rows are not part of the operation (D12). A data-layer deletion service should own the invariant.
- **Two owners of the cloud manifest:** `TelegramCloudRepository` owns it under `dbMutex`, while `BackupRecognitionRepository` writes it without that lock ([BackupRecognitionRepository.kt:104-127](app/src/main/java/com/telepic/data/backup/BackupRecognitionRepository.kt#L104)). A refresh read/merge can overwrite a recovered hash.
- **Duplicate MediaStore loaders:** `MediaStoreBucketLoader` is a near-copy of `MediaStoreMediaLoader`; the projection and visibility/exclusion rules are hand-copied across three loaders.
- **Per-item recognition queries** during discovery (`findByLocalId`, manifest lookups) make a 25k-item pass expensive.
- D22’s filename+size fallback weakens the content-identity guarantee.
- `domain/media` contains Android/Compose-dependent helpers (`MediaDay` uses `android.text.format.DateFormat`; `MapClusterer` imports `androidx.compose.runtime.Immutable`), so “pure domain” is not fully pure.

### 3.4 Networking / TDLib

**Strengths**

- One client, one auth state machine, one gateway seam; no JSON or hand-written JNI.
- The TDLib DB key is generated once, wrapped with a non-exportable Android Keystore AES key, written atomically, and stored in `noBackupFilesDir` ([SecureTdLibKeyProvider.kt:53-129](app/src/main/java/com/telepic/telegram/SecureTdLibKeyProvider.kt#L53)).
- Remote identity is persisted at the moment TDLib accepts a send; a timeout/process death confirms that message rather than re-uploading.
- Error classification distinguishes flood wait, timeouts, transient network failures and permanent rejection.
- History rebuild is paginated with a stop rule and a hard backstop.

**Weaknesses**

- **No account scoping** (D3). This is the most serious architectural gap in the cloud layer.
- **Long lock hold** across history rebuilds (D15); `prepare()` is called before every backup pass and every manual backup.
- **Full-manifest viewer lookups** (D16).
- **No durable failure signal** (D11); auth/cloud catch blocks update a flow but emit nothing.
- **Fragile error matching** (D19).
- **Fixed polling cost:** up to 200 × 300 ms send-confirmation polls per upload, serialized behind `networkMutex`, with no overall wall-clock budget.
- **Supply chain:** `io.github.tdlib-android:core:0.1.1` is a third-party repackaging, resolved without Gradle dependency verification/locking. It handles the api_hash, the DB key and every media byte.

### 3.5 Local storage

**Strengths**

- Room v11, ten migrations, no destructive fallback; fresh and upgraded installs converge.
- The backup queue is a real source of truth with indexed content hashes, retry counts and terminal-state guards.
- DataStore reads degrade to safe defaults; app-lock enforcement uses an explicit `Unknown` state rather than a silent `false`.
- TDLib state and the wrapped key live outside the backup set; `allowBackup="false"`.
- Restore dedup is keyed by true remote identity, not filename/size/MIME.

**Weaknesses**

- **Schema history is incomplete:** only `9.json`, `10.json`, `11.json` exist, while the README claims a committed baseline per version. There is also no direct v9→v10 migration test; that step is only traversed transitively.
- **The queue and location tables grow without pruning**, feeding D5/D12.
- **Preferences are unversioned.** Renaming a DataStore key or changing the restore-ledger encoding silently resets state; there is no corruption handler.
- **Restore ledger writes are not best-effort** (D21) and the encoding is an unversioned raw string.
- **Staging is in `cacheDir`**, which the OS may evict under pressure (D4).
- `deletedFromStore` and `markDeletedFromStore` are dead schema/API.

### 3.6 Background tasks / services

**Strengths**

- WorkManager constraints are rebuilt from the user’s transport preferences, so Wi-Fi-only/battery/charging are real constraints rather than worker-side checks.
- The foreground service type is correctly `dataSync`, with the manifest merge and permission declared.
- The worker repairs interrupted rows, keeps pending remote identity, drains batches inside a run, and hands the remainder to a fresh run.
- Startup sweeps orphaned staging copies; auth restoration resumes parked rows.

**Weaknesses**

- **No wall-clock budget**; the loop can run for hours and hit Android 15’s `dataSync` cap.
- **`KEEP` loses post-snapshot work** (D13).
- **Discovery has no single-flight guard** and is triggered from app startup, the 12-hour worker, Photos changes, onboarding and Settings. Concurrent passes can hash the same files and overwrite the cursor.
- **A failed discovery page is not persisted** (D14).
- **No run summary telemetry** — uploaded/waiting/failed counts are returned but not logged (D11).
- **No notification when the permission is denied**; the worker logs and continues.

---

## 4. Cross-cutting Concerns

### Null safety

- **Zero `!!`** in main sources, zero `GlobalScope`, zero `runBlocking`. Nullability is generally explicit.
- Remaining gaps: nullable-optional collaborators (a wiring risk), `id = row.long(...) ?: 0L` in the mapper, and a null MIME defaulting to `PHOTO`/Images URI.
- `lateinit var container` in `TelepicApplication` is initialized in `onCreate` before use; acceptable.

### Exception handling

- The app is unusually consistent about `CancellationException` — rethrown at repository, ViewModel, worker and TDLib boundaries.
- “Never fabricate success” is enforced in the queue, restore pipeline and backup coordinator.
- Over-broad `catch (Throwable)` appears where a typed result would be better: staging failures (D4), auth requests, cloud refresh. Some of these are intentional degradation, but they hide the difference between “source gone”, “disk full”, “provider refused” and “local bug”.
- Silent failures remain in auth/cloud refresh (D11) and in the restore-ledger write (D21).

### Logging

- One narrow seam exists (`TelepicLog`), with a `Telepic/` tag prefix, debug gating and a stated no-secrets rule.
- There is **no durable sink**, no rotation, no in-app export, and no `ApplicationExitInfo`; release diagnostics depend on ADB or nothing.
- Several high-value paths are unlogged: TDLib auth rejections, cloud manifest/destination failures, restore failures, paging provider failures. Conversely, per-item warn lines with full stack traces in hashing/staging/preview paths can flood logcat.
- Debug builds route raw TDLib log messages through the seam (truncated); the seam performs no redaction despite a comment calling it a “redacting logger”.

### Security and privacy

**Strong**

- No secrets committed; credentials come from environment/CI secrets; release builds are gated on non-placeholder credentials.
- Keystore-wrapped TDLib key, `noBackupFilesDir`, atomic key writes, `allowBackup="false"`.
- Fail-closed design intent for app lock and `FLAG_SECURE`; no exported providers/receivers; only the launcher activity is exported.
- App lock stores no secret of its own; it delegates to the device credential.
- No PII in the inspected `TelepicLog` format strings; phone/code/password are not logged.

**Weak / risky**

- **D1 is a critical fail-open** in the app’s only privacy control.
- **D3** leaves the previous account’s destination, manifest, queue and ledger in place.
- **D23** makes a same-named channel indistinguishable in the consent UI.
- **D24** persists phone/code/email in saved instance state.
- `TELEGRAM_API_HASH` is a readable constant in the DEX by construction; anyone extracting it can impersonate the app’s API credentials.
- CI artifacts built with real credentials are downloadable and debuggable; the README recommends the debug APK.
- The TDLib artifact is third-party and unverified; no Gradle dependency verification.
- Map tiles reveal the approximate region of the user’s photo library to CARTO, and the tile cache persists under `filesDir`.
- Telegram channels are cloud chats, not end-to-end encrypted; the app’s “private channel” phrasing should not be read as E2E. The README’s “no coordinates or EXIF are ever uploaded” is also too absolute: backup uploads original media bytes, and video/GIF metadata can travel with them.

### Dependencies / platform compatibility

| Item | Version | Assessment |
|---|---|---|
| AGP / Kotlin / KSP | 9.4.1 / 2.2.10 / 2.2.10-2.0.2 | Aligned; AGP 9 built-in Kotlin |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | Play-ready target; edge-to-edge handled |
| Compose BOM | 2024.10.01 | **Deliberately frozen** to avoid a FlowRow ABI break; now a coordinated multi-library migration |
| lifecycle / activity / navigation | 2.8.6 / 1.9.3 / 2.8.3 | Frozen with the BOM |
| Room / WorkManager / Paging | 2.8.5 / 2.12.0 / 3.3.2 | Current |
| Media3 | 1.4.1 | Older than current 1.5/1.6 line |
| Coil | 2.7.0 | Last 2.x; Coil 3 migration outstanding |
| TDLib | `io.github.tdlib-android:core:0.1.1` | Third-party repackaging; largest supply-chain risk |
| osmdroid | 6.1.20 | Blanket R8 keep; no consumer rules |
| `androidx.fragment` | undeclared | Compiles through biometric transitive |
| `androidx.room:room-testing` | unused | Remove |
| 16 KB native alignment | verified | `libtdjni.so` LOAD segments are `p_align = 0x4000`; ARM-only ABIs are compatible |

### Lifecycle awareness

- No `GlobalScope`/`runBlocking`; `MediaChangeWatcher` uses owner-keyed registration and releases the observer on the 1→0 transition; MapView is paused/detached; TDLib is process-scoped by design.
- App-scope collectors in `TelepicApplication` are intentional but never cancelled; that is acceptable for process-lifetime work, though discovery has no single-flight.
- Coil neighbor prefetch requests are not cancelled when the neighbor changes; they complete into the cache even after the user navigates.
- D1 is the lifecycle/state bug with the highest impact.

### Test coverage

- 909 tests, 0 ignored, 19k test LOC; migration tests reopen a real Room database; backup state machine, queue guards, cloud mapping, viewer race, navigation contracts, settings and Compose UI states are covered.
- **Missing:** any `androidTest`/instrumented suite; real `KeystoreTdLibKeyProvider`; `WorkManagerBackupScheduler`; `MediaStoreBackupStager`; `AndroidMediaStorePublisher`; `MediaPermissionController`; `AndroidMediaMetadataReader`; the real `TelepicNavHost` graph; MainActivity `FLAG_SECURE`; and the app-lock recovery path that D1 exposes.
- The production migration list is hand-duplicated in tests; a future migration added to `TelepicDatabase` but not `AppContainer` would pass every migration test and crash on upgrade.
- No coverage tool or threshold.

---

## 5. Ratings

| Dimension | Score | Justification |
|---|---:|---|
| **Architecture & Code Organization** | **8 / 10** | Clear single-module MVVM, repository seams, typed routes, and consistent state modeling. Deductions: NavHost is the composition root, `AppContainer` is a 420-line god-object, ViewModels accept nullable collaborators, three screen files exceed 800 lines, and several loaders/paging configs are duplicated. |
| **UI/UX Implementation** | **7 / 10** | Polished Compose design system, adaptive layout, strong empty/error states, reduced motion, and good tile semantics. Deductions: viewer/map/selection accessibility blockers, partial-access dead end, misleading backup copy, identical cloud tile labels, English-only resources, and no screenshot tests. |
| **Performance & Efficiency** | **5 / 10** | Small-library paths are thoughtful (paged MediaStore reads, thumbnail-first Coil, batched status flow, streaming hashes). At 20k+ items, the full-queue status map on the main thread, non-indexable offset paging, unbounded `NOT IN`, full-manifest cloud lookups, per-item bulk writes, and full-library map rescans become material. |
| **Error Handling & Robustness** | **8 / 10** | Best-executed dimension: cancellation discipline, SQL-guarded transitions, durable retry cap, pending remote identity, honest failure states, no fabricated success. Deductions: ENOSPC misclassified as permanent, scheduler `KEEP` can lose work, discovery cursor is not persisted, stale cloud identity has no recovery, and auth/cloud failures are under-observed. |
| **Security** | **6 / 10** | Strong at-rest design (Keystore, noBackup, allowBackup=false), fail-closed intent, FLAG_SECURE, no secrets in the tree. The critical app-lock recovery bypass, cross-account cloud state, title-only channel consent, credentials in saved state, embedded API hash, and third-party TDLib artifact outweigh the otherwise good posture. |
| **Test Coverage** | **7 / 10** | 909 tests, 0 ignored, migration coverage, and behavioral tests for the riskiest state machines. Missing instrumented tests, key provider, scheduler, stager, publisher, permission controller, real nav graph, and the app-lock retry path; no coverage gate. |
| **Maintainability** | **8 / 10** | Exceptional in-tree documentation, version catalog with rationale, migration baselines, CI gates, and consistent naming in most places. Deductions: god files, duplicated loaders/selection/folder-picker logic, `Default*` vs `*Impl` inconsistency, stale comments, and a frozen Compose BOM. |
| **Overall Readiness for Release** | **4 / 10** | Not a code-quality score. The app has never run its Telegram/MediaStore/render/credential paths on hardware, has no crash reporting or durable logs, has no instrumented tests, cannot currently produce a signed AAB from CI, and carries a critical app-lock bypass plus large-library scaling defects. Code-complete is not release-ready. |

---

## 6. Prioritized Recommendations

| # | Action | Impact | Effort |
|---:|---|---|---|
| 1 | **Fix the app-lock recovery path.** Treat any `RecoveryRequired → Known(enabled)` transition as `Locked` (or track an explicit authenticated flag), and re-subscribe to the preference flow instead of a one-shot read. Add the missing `Known(true)` recovery test. | Critical | Low |
| 2 | **Fix permanent delete.** Make a data-layer deletion service own the operation; on API 29, resume the remaining deletes after consent and only forget confirmed ids; remove custom-album membership, location and queue rows in the same transaction. | High | Medium |
| 3 | **Make staging resilient.** Preflight free space, move staging out of `cacheDir`, and distinguish “source gone” (permanent) from “write/provider failed” (transient) so ENOSPC does not mark items permanently failed. | High | Medium |
| 4 | **Account-scope or clear cloud state.** Store the owning Telegram user id with the destination/manifest/queue; revalidate the destination on reuse; clear or re-scope state on logout/account switch; provide an in-app “reset destination” recovery. | High | Medium |
| 5 | **Bound backup-status work.** Replace the full-queue status projection with a compact non-terminal/backed-up-id projection, prune or archive terminal rows, and move map construction off `Main`. | High | Medium |
| 6 | **Make media paging scale.** Move to keyset paging on the composite sort key, use an indexable ordering (or maintain a computed date column), and build the hidden-id selection once per hidden-set version instead of per page. | High | High |
| 7 | **Add durable diagnostics.** A small rotating `warn`/`error` file in `noBackupFilesDir`, a Settings “Share diagnostics” action, `ApplicationExitInfo` at startup, and one run summary plus one warn per terminal item failure. | High | Low–Medium |
| 8 | **Fix accessibility blockers.** Add next/previous semantics/actions in the Viewer, expose the map’s geotagged set as an accessible list, label the selection overflow, and give partial access a real “choose photos”/system-settings action. | High | Medium |
| 9 | **Fix backup scheduling semantics.** Add a discovery single-flight, persist the cursor on a failed page, schedule a continuation when auth/retry work arrives during a finishing run, and add a wall-clock/foreground budget. | Medium | Medium |
| 10 | **Repair the release pipeline.** Pass `RELEASE_*` into the release job, require all four signing variables when the keystore is present, remove placeholder credentials from release-smoke, bump `versionCode`, and add a written release runbook. | High | Low–Medium |
| 11 | **Add device validation and instrumented smoke tests** for TDLib auth/upload/download, MediaStore publish/delete, ExoPlayer, osmdroid and the credential prompt. Until that exists, the app’s most important paths remain unverified. | High | High |
| 12 | **Correct product copy and defaults.** “Back up all photos and videos”, disclose Telegram upload and mobile-data use, default to Wi-Fi-only or ask explicitly, and document that Telegram storage is not end-to-end encrypted. | Medium | Low |

---

## Verification limits

This review is static. The following claims are inherently unverified without a real ARM device and a live Telegram session: TDLib request/response shapes, authentication round-trip, cloud history/preview/download, live upload and confirmation, MediaStore write/delete consent, ExoPlayer playback, osmdroid rendering, Coil GIF decode, and the device-credential prompt. No build or test was run; the 909-test figure and all counts come from the source tree.
