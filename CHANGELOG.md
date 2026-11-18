# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0-alpha] - 2026-11-18

### Added
- **`:providers:local`**: Extracted and expanded local file system provider module:
  - `LocalFileSystemProvider`: POSIX/Linux file system implementation under `file://` scheme supporting hidden dotfiles, POSIX permissions, robust MIME resolution, safe directory creation, renaming, and recursive deletion.
  - `SafFileSystemProvider`: Storage Access Framework implementation under `content://` scheme over Android `DocumentFile` and `ContentResolver`, with `ParcelFileDescriptor` seekable random access.
  - `LocalStorageVolumeManager`: Query service discovering physical and emulated storage volumes (internal memory, SD cards) with byte-accurate `StatFs` metrics and standard directories (Downloads, Documents, Pictures, Music, Movies, DCIM).
  - `StoragePermissionHelper`: Helper checking all-files access permissions (`MANAGE_EXTERNAL_STORAGE` on Android 11+ and legacy storage permissions on API 26–29) and launching system settings intent.
  - `LocalProviderModule`: Hilt multibinding registering both local providers via `@Binds @IntoSet`.
- **`:core:database`**: Persistence layer powered by Room and KSP:
  - `BookmarkEntity` & `BookmarkDao`: Sidebar favorite folder bookmarks with initial pre-population of default system directories.
  - `FileTagCrossRefEntity` & `FileTagDao`: Association of 7 macOS Finder color tags (Red, Orange, Yellow, Green, Blue, Purple, Gray) to `VfsUri` with filtering capabilities.
  - `MetadataRepository`: Singleton repository exposing reactive `Flow` queries for bookmarks and file tags.
- **`:feature:explorer`**: Full-featured file explorer module built with MVI architecture:
  - `ExplorerViewModel` & Contract: State management for tabs, dual-pane layout, sorting, search, filtering, and contextual selection.
  - **Tabs & Dual-Pane**: Multi-tab browsing with independent backstacks and side-by-side dual-pane workspace for concurrent folder exploration.
  - **macOS Finder & Nautilus Navigation Sidebar**: Devices section with live capacity indicators (`LinearProgressIndicator`), Favorites section, and Finder color tags filter.
  - **View Modes**: List and adaptive Grid views with rich file metadata cards.
  - **Sorting & Filtering**: Sorting by name, size, modification date, and extension type (ascending/descending), with hidden dotfiles visibility toggle and real-time search.
  - **Quick Look**: macOS-style modal sheet displaying file metadata, on-demand MD5 and SHA-256 checksum calculation, and monospace text preview (first 8 KB).
  - **Contextual Selection Bar**: Multi-selection actions for tag assignment, folder creation, renaming, deletion, and cross-pane copy/move.
  - **Storage Permission Banner**: Warning card prompting for `MANAGE_EXTERNAL_STORAGE` access.
- **`:app` Integration & Localization**:
  - `MainActivity` host integrating `ExplorerScreen`.
  - Comprehensive English (`values/strings.xml`) and Russian (`values-ru/strings.xml`) string resources.
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
