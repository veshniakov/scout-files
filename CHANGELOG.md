# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0-alpha] - 2026-11-18

### Added
- **`:core:operations`**: Background file operations queue and execution engine:
  - `FileOperationManager`: Thread-safe `@Singleton` dispatcher managing lifecycle of `COPY`, `MOVE`, `DELETE`, `COMPRESS`, and `EXTRACT` operations on `Dispatchers.IO`.
  - Live progress metrics: Streamed byte-level counting, file counters, and 500ms smoothed transfer speed (KB/s and MB/s).
  - Suspend barrier for Pause / Resume and cooperative cancellation.
  - Interactive Conflict Resolution: Detection of collisions via `vfsRouter.exists(targetUri)`, emitting `FileConflictPrompt` with `OVERWRITE`, `SKIP`, and auto-renaming `KEEP_BOTH` (e.g. `file (1).ext`) strategies and «Apply to all» persistence.
  - Transparent Archive Compression: Support for generating `.zip` (with optional AES-256 encryption via Zip4j) and `.tar.gz` (via Commons Compress) archives directly to VFS target URIs.
  - Archive Extraction: Unpacking from VFS archive URIs to target destination directories with nested directory creation.
  - `FileOperationForegroundService`: Android Foreground Service with `dataSync` type, dynamic notifications with progress bar, speed display, and pause/resume/cancel pending actions.
- **`:providers:archive`**: Virtual File System provider for archives under `archive://` scheme:
  - Supports transparent browsing of `.zip`, `.rar` (RAR4/RAR5), `.7z`, `.tar`, `.tar.gz` (`.tgz`), `.tar.bz2`, and `.tar.xz`.
  - `VirtualArchiveTree`: In-memory directory tree abstraction providing hierarchical `listDirectory` and `getMetadata` without full extraction to disk.
  - `ArchivePasswordStore`: Thread-safe `@Singleton` in-memory session cache for archive passwords in `:core:vfs`.
  - Password Protection: Dynamic authentication challenge throwing `VfsException.AuthenticationRequired` for encrypted archives or entries, seamlessly unlocked via `ArchivePasswordStore`.
  - Random-access streaming via `RandomAccessContent` for fast seeking and previews.
  - Registered in Hilt graph via `@Binds @IntoSet FileSystemProvider`.
- **`:feature:explorer` Integration**:
  - Transparent Archive Entry: Clicking recognized archive files opens them as virtual directories via `archive://` URI.
  - Breadcrumbs Navigation: Enhanced `ScoutPathBar` with specialized archive segment rendering allowing navigation within internal folders, archive root, and jumping back to host directory.
  - `OperationsQueueSheet`: Modal bottom sheet displaying active tasks, progress bars, real-time speed, status chips, and pause/cancel controls.
  - Modal Dialogs:
    - `CompressDialog`: Create zip or tar.gz archive with optional AES-256 password protection.
    - `ArchivePasswordDialog`: Password prompt with visibility toggle and wrong password feedback.
    - `FileConflictDialog`: Overwrite, Skip, and Keep Both resolution with «Apply to all remaining» option.
  - Top bar operations badge indicating count of active background operations.
  - Contextual selection bar actions for compressing selected items and extracting archives.
  - Full bilingual localization (English in `values/strings.xml`, Russian in `values-ru/strings.xml`).
- **Unit Testing**:
  - `FileOperationManagerTest`: Tests for streaming copy, conflict handling (`OVERWRITE`, `SKIP`, `KEEP_BOTH`), encrypted zip creation, pause, and cancellation.
  - `ArchiveFileSystemProviderTest`: Tests for directory listing, entry reading, password-protected AES-256 ZIP authentication and recovery via `ArchivePasswordStore`, and tar.gz streaming.
  - `ExplorerViewModelTest`: Tests for archive navigation, operation queuing, and conflict/password state flows.

## [0.2.0-alpha] - 2026-11-18

### Added
- **`:providers:local`**: Extracted and expanded local file system provider module:
  - `LocalFileSystemProvider`: POSIX/Linux file system implementation under `file://` scheme supporting hidden dotfiles, full POSIX permissions via `java.nio.file.Files.getPosixFilePermissions` with fallback, robust MIME resolution, safe directory creation, cross-filesystem renaming, and recursive deletion.
  - `SafFileSystemProvider`: Storage Access Framework implementation under `content://` scheme over Android `DocumentFile` and `ContentResolver`, with `ParcelFileDescriptor` seekable random access and safe renaming.
  - `LocalStorageVolumeManager`: Query service discovering physical and emulated storage volumes (internal memory, SD cards on API 26+) with byte-accurate `StatFs` metrics and localized standard directories (Downloads, Documents, Pictures, Music, Movies, DCIM).
  - `StoragePermissionHelper`: Helper checking all-files access permissions (`MANAGE_EXTERNAL_STORAGE` on Android 11+ and legacy storage permissions on API 26–29) and launching system settings intent with OEM fallback.
  - `LocalProviderModule`: Hilt multibinding registering both local providers via `@Binds @IntoSet`.
- **`:core:database`**: Persistence layer powered by Room and KSP:
  - `BookmarkEntity` & `BookmarkDao`: Sidebar favorite folder bookmarks with initial pre-population of default system directories.
  - `FileTagCrossRefEntity` & `FileTagDao`: Association of 7 macOS Finder color tags (Red, Orange, Yellow, Green, Blue, Purple, Gray) to `VfsUri` with filtering capabilities.
  - `MetadataRepository`: Singleton repository exposing reactive `Flow` queries for bookmarks and file tags.
- **`:feature:explorer`**: Full-featured file explorer module built with MVI architecture:
  - `ExplorerViewModel` & Contract: State management for tabs, dual-pane layout, sorting, search, filtering, and contextual selection.
  - **Tabs & Dual-Pane**: Multi-tab browsing with independent backstacks and side-by-side dual-pane workspace for concurrent folder exploration.
  - **macOS Finder & Nautilus Navigation Sidebar**: Responsive sidebar displaying Devices with live capacity indicators (`LinearProgressIndicator`), Favorites section with localized default pinned folders, and Finder color tags filter. Persistent side-by-side on wide screens / landscape (>=600dp) and `ModalNavigationDrawer` on portrait smartphones.
  - **View Modes**: List and adaptive Grid views with rich file metadata cards.
  - **Sorting & Filtering**: Sorting by name, size, modification date, and extension type (ascending/descending), with hidden dotfiles visibility toggle and real-time search.
  - **Quick Look**: macOS-style modal sheet displaying file metadata, on-demand MD5 and SHA-256 checksum calculation, and selective monospace text preview (first 8 KB) for text, code, and markdown files with binary guard.
  - **Contextual Selection Bar**: Multi-selection actions for tag assignment with localized color names, folder creation, renaming, deletion, and cross-pane copy/move.
  - **Storage Permission Banner**: Warning card prompting for `MANAGE_EXTERNAL_STORAGE` access.
- **`:app` Integration & Localization**:
  - `MainActivity` host integrating `ExplorerScreen`.
  - Comprehensive English (`values/strings.xml`) and Russian (`values-ru/strings.xml`) string resources across all modules.
- **Unit Testing**:
  - `LocalFileSystemProviderTest`: Tests for directory listing, dotfile filtering, directory creation, renaming, and recursive deletion.
  - `MetadataRepositoryTest`: Tests for bookmark persistence, default seeding, and Finder color tag assignment.
  - `ExplorerViewModelTest`: Tests for navigation, backstack history, multi-tab management, sorting, hidden file filtering, dual-pane mode, and Quick Look text preview.

## [0.1.0-alpha] - 2026-11-18

### Added
- **Multi-module Scaffold**: Initial project bootstrap with strict modular isolation using Gradle 8.11.1, Kotlin 2.1.0, and Android Gradle Plugin 8.7.3.
- **`:core:model`**: Pure Kotlin/JVM domain module containing:
  - `VfsUri`: Robust URI parser supporting hierarchical paths, authorities, query parameters, child resolution, and parent navigation.
  - `VfsNode`: Rich metadata model for files and folders (permissions, mime type, size, modified timestamp, hidden flags).
  - `StorageVolume` & `FileTag`: Domain abstractions for physical/virtual volumes and macOS Finder-style color tags.
  - `VfsException`: Typed error hierarchy covering NotFound, PermissionDenied, AuthenticationRequired, NetworkTimeout, ReadOnlyFileSystem, and AlreadyExists.
- **`:core:vfs`**: Virtual File System engine:
  - `FileSystemProvider`: Asynchronous contract for all storage providers.
  - `RandomAccessContent`: High-performance seekable content interface for streaming media and large archives.
  - `VfsRouter`: Central thread-safe dispatcher leveraging Hilt Multibindings (`@IntoSet`) with built-in cross-protocol streaming transfer and directory recursion.
  - `LocalFileSystemProvider`: POSIX and Android local file system provider registered under `file://` scheme.
  - Coroutine dispatching on `Dispatchers.IO` with cooperative cancellation support.
- **`:core:designsystem`**:
  - `ScoutTheme`: Material 3 theme with dynamic color support (Android 12+) and dark mode palettes.
  - `ScoutPathBar`: Interactive hybrid path navigation bar inspired by Ubuntu Nautilus and macOS Finder, supporting breadcrumb navigation and inline raw URI editing.
- **`:app`**:
  - Main application shell configured with Hilt (`@HiltAndroidApp`).
  - Dual localization (`values/strings.xml` and `values-ru/strings.xml`) conforming to project identity.
  - VFS diagnostics screen verifying active router state and registered URI schemes.
- **Unit Testing**:
  - Unit tests for `VfsUri` path and authority parsing.
  - Unit tests for `VfsRouter` URI routing, unknown scheme exception handling, and cross-protocol streaming copy.
