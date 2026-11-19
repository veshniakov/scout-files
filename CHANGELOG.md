# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.4.0-alpha] - 2026-11-19

### Added
- **`:core:model` Domain Extensions**:
  - `NetworkProtocol`: `SFTP`, `SMB`, `FTP`, `FTPS`, `WEBDAV`, `GDRIVE`, `S3` with scheme mappings and default ports.
  - `AuthType`: `PASSWORD`, `SSH_KEY`, `OAUTH2`, `ACCESS_KEY`, `GUEST`.
  - `WebDavPreset`: `CUSTOM`, `NEXTCLOUD`, `OWNCLOUD`, `YANDEX_DISK` (`https://webdav.yandex.ru`) with path resolution and port definitions.
  - `ServerConnection`: Pure Kotlin model representing remote connection profile (id, name, protocol, host, port, username, authType, shareOrBucket, domainOrRegion, rootPath, useTls, requireBiometric, lastConnectedMillis).
  - `ServerCredentials`: Model representing sensitive authentication secrets (password, privateKeyPem, privateKeyPassphrase, oauthAccessToken, oauthRefreshToken, oauthClientId, s3SecretKey).
- **`:core:security` & Biometric Protection**:
  - `CredentialVault`: Hardware-backed credential storage interface (`storeCredentials`, `getCredentials`, `deleteCredentials`, `clearAll`).
  - `KeystoreCredentialVault`: Production implementation using AndroidKeyStore hardware-backed master key and `AES/GCM/NoPadding` encryption, encoding credentials to JSON before AES-GCM encryption with 12-byte IV prepending.
  - `InMemoryCredentialVault`: Hermetic in-memory vault for isolated JVM unit tests.
  - `BiometricAuthChecker`: Biometric prompt gateway built on `androidx.biometric:1.1.0` and `BiometricPrompt`, checking device biometric hardware enrollment and prompting with user-friendly credentials fallbacks.
- **`:core:database` Room Database Expansion**:
  - `ServerConnectionEntity`: Room entity mapping `ServerConnection` with primary key, protocol, authentication type, and connection parameters.
  - `ServerConnectionDao`: Data access object providing reactive `Flow<List<ServerConnectionEntity>>`, CRUD operations (`insert`, `update`, `delete`, `getById`).
  - Bumped `ScoutDatabase` to version 2 with server connection persistence support.
  - `MetadataRepository` expansion: Added `getServerConnections()`, `getServerConnection(id)`, `saveServerConnection(connection)`, `deleteServerConnection(id)`, and `updateServerConnectionLastConnected(id)`.
- **`:providers:sftp` (SSH File Transfer Protocol)**:
  - Integration with `com.hierynomus:sshj:0.38.0` and `org.bouncycastle:bcprov-jdk18on:1.78.1` / `bcpkix-jdk18on`.
  - `SftpSessionPool`: Reusable thread-safe SSH/SFTP session manager supporting password authentication and SSH private key authentication (Ed25519, RSA, ECDSA with passphrases) with auto-reconnection and stale session cleanup.
  - `SftpFileSystemProvider`: VFS provider for `sftp://<connectionId>/<remote_path>` implementing directory listing, metadata resolution, file stream reading and writing, directory creation, deletion, renaming, and `openRandomAccess` over `RemoteFile.read` positioned offsets.
  - Registered via `@Binds @IntoSet FileSystemProvider`.
- **`:providers:smb` (Samba / Windows Share)**:
  - Integration with `com.hierynomus:smbj:0.13.0`.
  - `SmbSessionPool`: SMBv2/SMBv3 connection pool supporting guest logins, username/password, and Windows domain authentication with multi-share multiplexing.
  - `SmbFileSystemProvider`: VFS provider for `smb://<connectionId>/<share>/<remote_path>` implementing directory browsing, file streaming, directory creation, deletion, renaming, and `openRandomAccess` over `com.hierynomus.smbj.share.File.read` positioned offsets.
  - Registered via `@Binds @IntoSet FileSystemProvider`.
- **`:providers:ftp` (FTP & FTPS)**:
  - Integration with `commons-net:commons-net:3.11.1`.
  - `FtpFileSystemProvider` & `FtpsFileSystemProvider`: VFS providers for `ftp://` and `ftps://` with passive mode (`enterLocalPassiveMode()`), binary transfer mode, UTF-8 control channel encoding, and implicit/explicit TLS.
  - `FtpRandomAccessContent`: Random-access streaming using FTP `REST` (`setRestartOffset`) command to support seekable reading and streaming.
  - Registered via `@Binds @IntoSet FileSystemProvider`.
- **`:providers:webdav` (WebDAV & AWS S3 / MinIO)**:
  - Integration with `com.squareup.okhttp3:okhttp:4.12.0`.
  - `WebDavFileSystemProvider`: WebDAV client under `webdav://` scheme supporting `PROPFIND` multistatus XML parsing, `PUT`, `MKCOL`, `DELETE`, `MOVE`, and `GET` streaming with HTTP `Range: bytes=start-end` random-access reading.
  - WebDAV Presets: Built-in URL auto-construction for Nextcloud (`/remote.php/dav/files/<user>/`), ownCloud, and Yandex.Disk (`https://webdav.yandex.ru`).
  - `S3FileSystemProvider`: Amazon S3 and MinIO provider under `s3://` scheme with pure Kotlin AWS Signature V4 signing (`AwsSigV4Signer`), `ListObjectsV2` XML parsing, `GetObject` streaming with `Range` header support, `PutObject`, and `DeleteObject`. Compatible with MinIO, AWS S3, Cloudflare R2, and Backblaze B2.
  - Registered via `@Binds @IntoSet FileSystemProvider`.
- **`:providers:gdrive` (Google Drive REST API v3)**:
  - `GoogleDriveFileSystemProvider`: VFS provider for `gdrive://<connectionId>/<remote_path>` over Google Drive REST API v3.
  - Token Lifecycle: Automatic OAuth2 access token refresh on HTTP 401 using `oauthRefreshToken` via `https://oauth2.googleapis.com/token`, updating refreshed tokens in `CredentialVault`.
  - Path Resolution Cache: In-memory cache mapping hierarchical file paths to Google Drive `fileId`s for high-performance navigation and metadata lookups.
  - Operations: Folder creation, file uploading, deletion, renaming via `PATCH`, and random-access reading (`alt=media` with `Range` header).
  - Document Export: Automatic on-the-fly export of Google Workspace documents (Docs -> PDF, Sheets -> XLSX, Slides -> PDF/DOCX) when opening read stream.
  - Registered via `@Binds @IntoSet FileSystemProvider`.
- **`:feature:connections` Management Wizard**:
  - `ServerConnectionSheet`: Modal bottom sheet wizard for creating and editing server connection profiles.
  - Dynamic Form UI: Tailored configuration fields based on selected protocol (SFTP keys/passphrase vs password, SMB share/domain/guest, S3 endpoint/bucket/region/keys, WebDAV presets, Google Drive).
  - Test Connection: Integrated connectivity verification button querying `VfsRouter.listDirectory` before saving with live feedback.
  - Biometric Gating Toggle: Switch to require biometric authentication when opening the connection.
- **`:feature:explorer` Network & Cloud Integration**:
  - Sidebar «Network & Cloud» section displaying saved server connections with protocol badges (SFTP, SMB, FTP, WebDAV, S3, GDrive), add connection button, and edit/delete actions.
  - Biometric Prompt on Open: Authenticates user via `BiometricAuthChecker` before navigating into connections marked with `requireBiometric = true`.
  - Seamless `ScoutPathBar` support for all remote schemes, preserving path hierarchy and navigation history.
  - Cross-provider file operations: Seamless copy/move between remote providers, virtual archives, and local storage via `FileOperationManager`.
- **Bilingual Localization**:
  - Comprehensive English (`res/values/strings.xml`) and Russian (`res/values-ru/strings.xml`) strings for all new protocols, fields, connection sheets, test connection states, and biometric authentication prompts.
- **Hermetic Test Suite**:
  - 165 unit tests across 22 test suites covering all remote providers, AWS SigV4 signer, Google Drive JSON parser, CredentialVault encryption, Room DAO, and ViewModel state transitions.

### Changed
- Bumped `MainActivity` to extend `FragmentActivity` for `BiometricPrompt` support.
- Updated `libs.versions.toml` with `androidx.biometric:1.1.0`, `com.hierynomus:sshj:0.38.0`, `org.bouncycastle:bcprov-jdk18on:1.78.1`, `org.bouncycastle:bcpkix-jdk18on:1.78.1`, `com.hierynomus:smbj:0.13.0`, `commons-net:commons-net:3.11.1`, and `com.squareup.okhttp3:okhttp:4.12.0`.
- Configured packaging resource exclusions in `app/build.gradle.kts` to resolve duplicate BouncyCastle / SSHJ / SMBJ metadata entries.

### Fixed
- Path normalization in breadcrumbs and `ScoutPathBar` when navigating non-standard root paths in remote servers.

### Security
- Hardware-backed AndroidKeyStore master key encryption (`AES/GCM/NoPadding`) protecting server credentials at rest.
- Biometric authentication challenge before mounting or navigating protected network volumes.

## [0.3.0-alpha] - 2026-11-18

### Added
- **`:core:operations`**: Background file operations queue and execution engine:
  - `FileOperationManager`: Thread-safe `@Singleton` dispatcher managing lifecycle of `COPY`, `MOVE`, `DELETE`, `COMPRESS`, and `EXTRACT` operations on `Dispatchers.IO`.
  - Live progress metrics: Streamed byte-level counting, file counters, and 500ms smoothed transfer speed (KB/s and MB/s).
  - Suspend barrier for Pause / Resume and cooperative cancellation.
  - Interactive Conflict Resolution: Detection of collisions via `vfsRouter.exists(targetUri)` during copy, move, and extraction, emitting `FileConflictPrompt` with `OVERWRITE`, `SKIP`, and auto-renaming `KEEP_BOTH` (e.g. `file (1).ext`) strategies and «Apply to all» persistence.
  - Transparent Archive Compression: Support for generating `.zip` (with optional AES-256 encryption via Zip4j) and `.tar.gz` (via Commons Compress) archives directly to VFS target URIs.
  - Archive Extraction: Unpacking from VFS archive URIs to target destination directories with nested directory creation, conflict handling, live speed/progress estimation, and single file extraction support.
  - `FileOperationForegroundService`: Android Foreground Service with `dataSync` type, dynamic notifications with progress bar, speed display, pause/resume/cancel pending actions, and full bilingual localization.
- **`:providers:archive`**: Virtual File System provider for archives under `archive://` scheme:
  - Supports transparent browsing of `.zip`, `.rar` (RAR4/RAR5), `.7z`, `.tar`, `.tar.gz` (`.tgz`), `.tar.bz2`, and `.tar.xz`.
  - `VirtualArchiveTree`: In-memory directory tree abstraction providing hierarchical `listDirectory` and `getMetadata` without full extraction to disk, with Zip Slip path traversal defenses.
  - OOM-Safe Streaming: Direct streaming for tar, zip, 7z, and rar entries without whole-file in-memory buffering.
  - Resource Safety: Explicit file descriptor cleanup for SevenZ and Junrar archives on stream close.
  - Robust Path Handling: Correct handling of standard tar archives with `./` entry prefixes.
  - `ArchivePasswordStore`: Thread-safe `@Singleton` in-memory session cache for archive passwords in `:core:vfs`.
  - Password Protection: Dynamic authentication challenge throwing `VfsException.AuthenticationRequired` for encrypted archives or entries, seamlessly unlocked via `ArchivePasswordStore`.
  - Random-access streaming via `RandomAccessContent` for fast seeking and previews.
  - Registered in Hilt graph via `@Binds @IntoSet FileSystemProvider`.
- **`:feature:explorer` Integration**:
  - Transparent Archive Entry: Clicking recognized archive files opens them as virtual directories via `archive://` URI.
  - Quick Look Password Recovery: Entering an archive password automatically unlocks and retries the file preview.
  - Reliable Operations Queue: Auto-refreshes current panes on completion of every background task.
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
  - `FileOperationManagerTest`: Tests for streaming copy, extraction with conflict handling (`OVERWRITE`, `SKIP`, `KEEP_BOTH`), single file extraction from inside archives, encrypted zip creation, pause, and cancellation.
  - `ArchiveFileSystemProviderTest`: Tests for directory listing, entry reading, password-protected AES-256 ZIP authentication and recovery via `ArchivePasswordStore`, tar.gz streaming, dot-slash tar handling, and read-only restrictions.
  - `ExplorerViewModelTest`: Tests for archive navigation, operation queuing, Quick Look password challenge/retry, and conflict/password state flows.

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
