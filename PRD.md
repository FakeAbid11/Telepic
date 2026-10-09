Telepic

Master Product Requirements Document (PRD)

Repository: https://github.com/FakeAbid11/Telepix.git
Product: Telepic
Platform: Android
Language: Kotlin
UI: Jetpack Compose + Material 3 / Material You
Cloud backend: Telegram via TDLib
Database: Room
Background processing: WorkManager + foreground data-sync service where required
Map: OpenStreetMap
Build system: GitHub Actions only
Initial release target: Debug APK for real-device testing

1. Product Vision

Telepic is a modern Android photo and video management application inspired by the usability of Google Photos.

Its core purpose is to:

Organize the user's local photos and videos.

Provide a beautiful timeline-based photo experience.

Back up media to Telegram.

Allow users to browse backed-up media from Telegram without automatically downloading originals.

Recognize previously backed-up media and avoid unnecessary duplicates.

Restore/download backed-up media when requested.

Provide albums, favorites, archive, trash, metadata, maps, and a powerful media viewer.

Telepic should feel like a polished personal photo library while using Telegram as its cloud-storage layer.

Product principle

Local-first photo management with Telegram-powered cloud backup.

Telepic is a standalone product with its own visual identity, architecture, implementation, and UX.

2. Important Development Constraint

The developer has a low-end laptop and should not be required to build Telepic locally.

All Android builds must happen through GitHub Actions.

Required workflow

Code
  ↓
GitHub Repository
  ↓
GitHub Actions
  ↓
Gradle Build
  ↓
Unit Tests / Lint / Verification
  ↓
Debug APK
  ↓
GitHub Actions Artifact
  ↓
Android Device

No implementation phase should assume that Android Studio or Gradle can successfully build the project locally.

GitHub Actions must therefore be considered part of the project's architecture.

3. Repository

The official repository is:

FakeAbid11/Telepix

All implementation work must target this repository.

The repository should contain:

Android source

Gradle configuration

GitHub Actions workflows

Tests

Documentation

PRD

Build configuration

TDLib integration

Release/debug build configuration

4. Technology Stack

Android

Kotlin

Android SDK

Jetpack Compose

Material 3

Material You design principles

AndroidX

Architecture

Use a maintainable layered architecture:

UI
 ↓
Presentation/ViewModel
 ↓
Domain
 ↓
Repository
 ↓
Data Sources
 ├── MediaStore
 ├── Room
 ├── TDLib
 └── File system

Recommended architectural principles:

Unidirectional data flow

State-driven Compose UI

Repository pattern

Dependency injection

Coroutines

Kotlin Flow

ViewModels

Immutable UI state where practical

5. Telegram / TDLib

Telepic uses Telegram as its cloud storage backend.

The Telegram implementation must use the Java TDLib interface:

org.drinkless.tdlib.Client
org.drinkless.tdlib.TdApi

Do not build the application around a JSON Telegram abstraction.

Native ABIs

Initially support:

arm64-v8a

armeabi-v7a

Do not require:

x86

x86_64

unless explicitly added later.

6. Telegram Authentication

Telegram authentication is part of onboarding.

The application must handle the TDLib authorization lifecycle, including appropriate states for:

Initializing

Waiting for phone number

Waiting for authentication code

Waiting for password

Authorized

Logging out

Closing

Error

The UI must clearly communicate what the user needs to do.

The Telegram API ID and API Hash must never be hard-coded into source control.

They must be supplied through GitHub Secrets/build configuration.

7. Telepic Backup Channel

Telepic uses a Telegram channel named:

Telepic Backup

On first cloud setup:

Search for the Telepic Backup channel.

Determine whether the authenticated user owns/controls an appropriate channel.

Validate the channel.

If it does not exist, create it where Telegram permissions/API capabilities allow.

Store the selected channel ID locally.

Use that channel for Telepic backup operations.

The app must not blindly use an arbitrary channel with the same name.

A channel should be validated using Telepic-specific metadata/protocol information where practical.

8. Cloud Philosophy

The Cloud screen represents the user's Telegram-backed Telepic media library.

Browsing Cloud should prioritize:

Metadata

Thumbnails/previews

Remote state

over downloading original files.

Important behavior

Opening the Cloud screen must not automatically download every original file.

Original media should be downloaded only when required, such as:

User opens a remote item requiring the original

User explicitly downloads

User restores media

User requests local availability

This prevents unnecessary phone storage usage.

9. Media Quality

Telepic should preserve the highest practical media quality.

However, Telegram media APIs can transform/recompress certain media types depending on how they are uploaded.

Therefore the implementation must distinguish between:

Telegram-native photo/video uploads

Document/file uploads

The architecture should allow future changes to upload strategy.

The product must never falsely promise byte-for-byte preservation unless the selected upload method guarantees it.

10. Supported Media

Initial supported media should include Android MediaStore-supported:

Images

JPEG

JPG

PNG

WebP

HEIF/HEIC where supported

GIF

Videos

MP4

Other Android-supported video formats where practical

The media layer must be extensible.

11. Main Navigation

Telepic will use five primary destinations:

Photos

Cloud

Albums

Map

Settings

This is the primary navigation structure.

Additional functionality such as Favorites, Archive, Trash, Backup Center, Details, and Viewer should be reached contextually rather than creating an excessive number of bottom-navigation destinations.

12. Screen Architecture

Main screens

Telepic has 11 primary application screens:

1. Photos

The primary local media timeline.

2. Cloud

Telegram-backed media.

3. Albums

Local/system/user albums.

4. Map

Location-based media.

5. Viewer

Photo/video/GIF viewer.

6. Favorites

Favorite media.

7. Archive

Archived media.

8. Trash

Deleted media.

9. Backup Center

Backup queue, progress, errors, and status.

10. Settings

Application configuration.

11. Media Details

Detailed media information.

13. Onboarding

Telepic has six onboarding screens.

Screen 1 — Welcome

Introduce Telepic.

Explain:

Photo organization

Telegram backup

Cloud access

Primary action:

Get Started

Screen 2 — How Telepic Works

Explain the basic architecture:

Phone Photos
     ↓
Telepic
     ↓
Telegram Cloud

Explain that Telepic does not automatically download every cloud original.

Screen 3 — Permissions

Request required Android permissions.

Depending on Android version, handle:

Photo/media permissions

Notification permission where necessary

Other permissions required by implemented features

Do not request unnecessary permissions.

Screen 4 — Telegram Login

Authenticate the user through TDLib.

Screen 5 — Backup Preferences

Offer:

Backup all photos

Automatically back up supported media.

Select folder

Allow the user to choose a folder/source.

Not now

Continue without enabling backup.

The user can change this later.

Screen 6 — Ready

Show that Telepic is ready.

Possible information:

Telegram connection

Backup destination

Media scan status

Backup preference

Primary action:

Start Using Telepic

14. Photos Screen

The Photos screen is the primary experience.

It should be visually inspired by modern photo applications while maintaining Telepic's own design language.

Features:

Chronological timeline

Adaptive grid

Date grouping

Right-side date navigation rail

Pull-to-refresh where useful

Incremental/paged loading

Video indicators

GIF indicators

Backup status indicators

Selection mode

Example:

                 Telepic

Today

┌──────┬──────┬──────┐
│      │      │      │
│ IMG  │ IMG  │ 🎥   │
│      │      │      │
└──────┴──────┴──────┘

Yesterday

┌──────┬──────┬──────┐
│      │      │      │
│ IMG  │ GIF  │ IMG  │
└──────┴──────┴──────┘

                       │
                       │ Oct
                       │ Sep
                       │ Aug
                       │

15. Selection Mode

Long press or selection action should enter multi-selection mode.

Actions may include:

Backup

Favorite

Archive

Delete

Share

Restore where applicable

The selection UI should follow Material 3 conventions.

16. Cloud Screen

Cloud displays media stored in the Telepic Backup Telegram channel.

The Cloud UI should clearly distinguish:

Cloud-only

Locally available

Backed up

Downloading

Failed

A remote photo should be viewable using available Telegram thumbnails/previews without automatically storing the original locally.

17. Albums

Albums should support:

System albums

Examples:

Camera

Screenshots

Downloads

Videos

GIFs

User albums

Users can create custom albums.

Albums should not unnecessarily duplicate physical media.

The database should maintain album membership independently where appropriate.

18. Media Viewer

The viewer must support:

Full-screen images

Pinch-to-zoom

Swipe navigation

Video playback

GIF playback

Loading states

Error states

Local media

Cloud media

Download

Restore

Backup

Favorite

Archive

Trash

Viewer controls should remain visually minimal.

19. Media Details

Media details should expose:

Filename

File type

File size

Resolution

Date taken

Date modified

Device

Location

Backup status

Cloud association

Content hash where appropriate

EXIF parsing must be bounded and failure-tolerant.

Malformed metadata must never crash the viewer.

20. Backup Architecture

The backup engine is a core subsystem.

MediaStore
    ↓
Media Scanner
    ↓
Backup Queue
    ↓
Recognition
    ↓
Hashing
    ↓
Duplicate Check
    ↓
Staging
    ↓
Telegram Upload
    ↓
Verification
    ↓
Room Association

Possible states:

NOT_BACKED_UP
QUEUED
PREPARING
UPLOADING
BACKED_UP
FAILED
CANCELLED

21. Backup Queue

Room stores pending backup operations.

Each queue item should contain enough information to resume safely.

Recommended information includes:

Local media identifier

URI/path reference where appropriate

MIME type

Size

Modification time

Content hash

Creation/hash timestamp

Upload state

Retry count

Error information

Telegram message ID

Telegram chat/channel ID

Timestamps

The database must remain the source of truth for backup state.

22. Background Backup

Use WorkManager for reliable background orchestration.

Long-running uploads may require a foreground data-sync service.

The system must handle:

Network unavailable

Battery constraints

App restart

Process death

Device reboot where permitted

Telegram reconnect

Upload failure

Retry

Do not create uncontrolled background threads/services.

23. Backup Recognition

Telepic must recognize media that has already been backed up.

Use multiple levels of identification.

Fast identity

Use:

MediaStore ID

URI/reference

Size

Modification timestamp

Strong identity

Use:

SHA-256 content hash

Remote identity

Use Telepic cloud manifest information.

24. Deduplication

Before uploading:

Does local association exist?
       ↓
      YES
       ↓
Already backed up

       NO
       ↓
Check content hash
       ↓
Hash exists remotely?
   ↓          ↓
  YES         NO
  ↓            ↓
Associate     Upload

This is especially important after reinstalling Telepic.

25. Reinstall Recovery

A clean reinstall should not necessarily cause every photo to be uploaded again.

After Telegram authentication:

Locate Telepic Backup.

Scan remote manifest.

Obtain cloud media metadata.

Match remote records against local media.

Associate matching content.

Upload only genuinely new media.

26. Restore

Restore flow:

Telegram Cloud
      ↓
Original/selected file download
      ↓
Temporary staging
      ↓
Validation
      ↓
MediaStore writer
      ↓
Local media
      ↓
SHA-256 verification
      ↓
Backup association

Restore failures must leave the user with a clear error.

Temporary files must be cleaned safely.

27. Map

Map uses OpenStreetMap-based mapping.

Photo locations come from EXIF GPS metadata.

Features:

Interactive map

Photo markers

Marker clustering

Preview thumbnails

Open viewer from marker

Navigate between photos from a location

The map implementation must include proper attribution and a configurable/cache-conscious tile strategy.

The application should not assume a public tile server is suitable for unlimited production traffic.

28. Favorites

Favorite state is independent from backup state.

A media item can be:

Favorite
+
Backed up

or:

Favorite
+
Not backed up

Both must work.

29. Archive

Archived media:

Remains stored

Remains recoverable

Is removed from the primary Photos timeline

Can be viewed from Archive

Can be restored to the main timeline

30. Trash

Trash provides a safer deletion workflow.

The implementation must clearly distinguish:

Remove from Telepic/local view

Move to Trash

Permanent deletion

Cloud deletion

Cloud deletion must never happen accidentally as a side effect of ordinary local deletion.

The exact synchronization rules must be explicitly implemented and tested.

31. Settings

Settings should include:

Account

Telegram account

Connection state

Backup channel

Backup

Enable/disable backup

Backup source

Wi-Fi preference

Mobile data preference

Charging preference

Backup quality/strategy where applicable

Manual backup

Queue management

Appearance

Dark

Light

System default

Media

GIF behavior

Video behavior

Thumbnail/cache settings

Storage

Cache size

Clear cache

Local/cloud information

Security

App lock capability reserved for future/implementation as appropriate

About

Version

Open-source licenses

Telepic information

32. Design System

Visual identity

Primary colors:

Blue

Black

White

Avoid creating an overly generic Material app.

Material 3 should provide the component foundation, while Telepic's blue/black/white identity should define the visual personality.

33. Dark Theme

Dark theme is the default.

Design goals:

Deep black/dark surfaces

Blue primary actions

White/high-contrast typography

Minimal visual noise

Media remains visually dominant

The exact color tokens should be centralized in the theme rather than hard-coded throughout the UI.

34. Light Theme

Light theme:

White backgrounds

Blue primary color

Dark text

Subtle neutral surfaces

All components must support both themes.

35. Typography

Use Material 3 typography tokens.

The app should maintain:

Clear hierarchy

Readability

Strong media focus

Consistent title/body/caption relationships

Typography should not be individually hard-coded across screens.

36. Icons

Use Material Icons / Material Symbols.

Icons must:

Have consistent meaning

Follow Material conventions

Avoid unnecessary decorative icons

Support accessibility descriptions

37. Motion

Use Material 3-style motion.

Animations should be:

Short

Smooth

Purposeful

Interruptible

Respectful of reduced-motion settings where applicable

Do not over-animate the photo grid.

38. Accessibility

Telepic must support:

Content descriptions

Screen readers

Sufficient contrast

Touch targets

Scalable text

Keyboard/navigation compatibility where applicable

Clear error messaging

39. Room Database

Room stores application metadata rather than duplicating the entire MediaStore library.

Expected areas include:

Media association

Local media identifier

URI/reference

Metadata

Backup queue

Queue state

Hash

Upload state

Retry state

Cloud manifest

Telegram message ID

Telegram channel ID

Media metadata

Hash

Size

MIME type

Remote state

Albums

User albums

Membership

User state

Favorites

Archive

Trash

Database migrations must be explicit.

Never use destructive fallback migrations for production data.

40. Security

Security principles:

Never commit Telegram credentials.

Use GitHub Secrets for CI credentials.

Do not log sensitive Telegram authentication information.

Avoid logging message content unnecessarily.

Keep databases in app-private storage.

Exclude sensitive application data from Android backup where appropriate.

Protect temporary media files.

Clean staging files after upload/restore.

Validate Telegram cloud objects before trusting them.

Future hardening may include encrypted local database/session handling.

41. Error Handling

Every major operation must have:

Loading state

Success state

Empty state

Error state

Retry action where appropriate

Examples:

Media permission denied

Explain how to enable access.

Telegram unavailable

Provide retry/reconnect.

Backup failed

Show failed count and retry option.

Cloud unavailable

Do not display an empty cloud as though there is no data.

Media library failure

Distinguish:

No photos found

from:

Unable to access photo library

42. Offline Behavior

Telepic should remain useful without internet.

Offline:

Local Photos works.

Albums work.

Favorites work.

Archive works.

Local viewer works.

Backup queue records pending work.

Cloud operations show an appropriate offline state.

When connectivity returns, pending backup work resumes.

43. Caching

Use controlled caching for:

Thumbnails

Cloud previews

Map tiles

Temporary downloads

Cache must have a predictable cleanup mechanism.

Original media should not be permanently cached unless the user explicitly downloads/restores it.

44. Performance

Telepic must be designed for ordinary Android phones.

Important requirements:

Lazy grids

Paging/incremental loading

Thumbnail-first rendering

Avoid decoding full-resolution images in grids

Avoid loading all MediaStore records simultaneously

Avoid unnecessary database queries

Avoid blocking the main thread

Use coroutines for I/O

Cancel work when screens disappear where appropriate

45. GitHub Actions

GitHub Actions is mandatory for builds.

The workflow should support:

Pull requests

Compile

Unit tests

Lint/static checks

Architecture/build validation

Main branch

Full verification

Debug APK

Artifact upload

TDLib/native workflow

Native TDLib builds must be isolated/cached where practical.

Avoid rebuilding TDLib unnecessarily.

46. Secrets

Secrets must never be committed.

Potential GitHub Secrets include:

TELEGRAM_API_ID
TELEGRAM_API_HASH

Additional secrets should only be added when actually required.

The workflow must fail clearly if required secrets are unavailable.

47. Testing Strategy

Testing is required at multiple levels.

Unit tests

Test:

Backup recognition

Hashing

Deduplication

Queue transitions

Repository logic

Metadata extraction

Cloud manifest matching

Restore logic

Integration tests

Test:

Room migrations

Repository interactions

MediaStore interactions where practical

Backup state transitions

UI tests

Test critical flows:

Onboarding

Telegram login state handling

Photos

Cloud

Backup

Viewer

Restore

Settings

Device testing

Every major milestone should produce a GitHub Actions APK for real-device testing.

48. Logging

Logging should be structured and useful.

Log:

State transitions

Queue operations

Non-sensitive errors

Performance information where useful

Do not log:

Telegram authentication codes

Passwords

API secrets

Sensitive user content

Private message contents unnecessarily

49. Data Integrity

Backup operations must be resumable and crash-safe.

If the application dies during upload:

App killed
   ↓
Room still says UPLOADING
   ↓
Recovery logic
   ↓
Validate/retry
   ↓
Complete or mark failed

The database should never claim an item is backed up before the remote operation has been successfully confirmed.

50. UX Principle: No Surprises

Telepic must avoid destructive or expensive actions without user understanding.

Examples:

Do not silently delete cloud media.

Do not automatically download huge cloud files.

Do not upload unexpected folders.

Do not use mobile data unexpectedly if the user disabled it.

Clearly show backup state.

Clearly distinguish local and cloud media.

51. Initial Navigation Model

Recommended structure:

┌─────────────────────────────────────────┐
│                Telepic                  │
├─────────────────────────────────────────┤
│                                         │
│              Current Screen             │
│                                         │
│                                         │
├─────────────────────────────────────────┤
│ Photos │ Cloud │ Albums │ Map │ Settings│
└─────────────────────────────────────────┘

The navigation should adapt gracefully to smaller screens.

52. No Search Initially

Telepic will not implement a dedicated Search feature in the initial version.

Search can be added in a future roadmap phase.

53. Initial Release Scope

The initial development target is a functional debug build.

It must eventually support:

Onboarding

Telegram login

Telepic Backup channel

Local media scanning

Photos timeline

Cloud browsing

Backup queue

Recognition

Deduplication

Viewer

Albums

Map

Favorites

Archive

Trash

Restore/download

Settings

Background backup

Error/recovery handling

54. 12 Implementation Phases

Phase 1 — Foundation & Material You Design System

Create:

Android project

Kotlin configuration

Compose

Material 3

Telepic theme

Navigation foundation

GitHub Actions

Basic testing

Project architecture

Phase 2 — Onboarding & Permissions

Implement all six onboarding screens.

Implement:

Permission handling

Telegram setup entry

Backup preference

Onboarding persistence

Phase 3 — Local Media Library

Implement:

MediaStore scanning

Photo/video/GIF recognition

Timeline

Metadata

Thumbnail loading

Paging/incremental loading

Phase 4 — Telegram / TDLib

Implement:

TDLib integration

ARM native dependencies

Telegram authorization

Session management

Connection state

Phase 5 — Telegram Cloud

Implement:

Telepic Backup channel discovery

Channel creation/validation

Cloud manifest

Remote metadata

Cloud browsing

Preview/thumbnail handling

Phase 6 — Backup Engine

Implement:

Backup queue

WorkManager

Foreground long-running upload

Staging

Upload

Retry

Progress

Failure recovery

Phase 7 — Recognition & Deduplication

Implement:

Content hashing

Local identity

Remote manifest matching

Duplicate prevention

Reinstall recovery

Phase 8 — Photos UI

Implement polished:

Photos timeline

Date rail

Grid

Selection mode

Backup indicators

Favorites

Archive

Trash interactions

Phase 9 — Cloud, Albums & Viewer

Implement:

Cloud UI

Albums

Full-screen viewer

GIF

Video

Metadata

Download

Cloud actions

Phase 10 — Map, Restore & Media Management

Implement:

OpenStreetMap

EXIF GPS

Clustering

Restore

MediaStore writing

Verification

Media management

Phase 11 — Settings, Security & Recovery

Implement:

Settings

Backup controls

Account controls

Appearance

Storage

Security hardening

Recovery

Offline handling

Error states

Phase 12 — Full Integration & Hardening

Perform:

Full integration

Migration testing

Device testing

Performance testing

Backup/reinstall testing

Restore testing

Telegram failure testing

Network interruption testing

GitHub Actions optimization

Debug APK validation

55. Definition of Done

Telepic is considered ready for its first serious test milestone when:

App installs successfully.

GitHub Actions builds successfully.

Telegram authentication works.

Telepic Backup channel can be found/created.

Local photos appear.

Backup queue works.

Media can be uploaded.

Backup state is persisted.

Duplicate media is recognized.

Cloud media can be browsed.

Original downloads happen only when requested.

Viewer works.

Albums work.

Map works for GPS-tagged media.

Restore works.

Favorites/archive/trash work.

Settings work.

App survives restart.

Backup resumes after interruption.

Database migrations work.

No critical secrets are committed.

Critical tests pass.

56. Future Roadmap

Possible future features:

Search

AI-assisted organization

Face/person grouping

Places grouping

Advanced memories

Shared albums

Multiple Telegram backup destinations

Additional cloud providers

End-to-end encryption improvements

App lock

Advanced storage analytics

Desktop/web companion

Production release signing

Automated release pipeline

These are not required for the initial implementation.

57. Product Success Criteria

Telepic succeeds if a user can install the app and confidently understand:

Where their local photos are.

What has been backed up.

What exists in the cloud.

How to restore something.

Where Telegram is involved.

How to control backup behavior.

The application should make backup feel automatic without making the underlying system mysterious.

58. Final Product Principle

Telepic should feel like:

Google Photos-style usability



Telegram-powered personal cloud



Material You design



Reliable automatic backup



Local-first performance



Clear user control

The final result should be a polished, standalone Android photo application rather than a simple Telegram file uploader.