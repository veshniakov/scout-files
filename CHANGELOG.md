# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
