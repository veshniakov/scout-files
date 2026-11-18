# Scout (dev.scout.files)

<p align="center">
  <strong>Современный, производительный и безопасный файловый менеджер для Android с поддержкой локальных хранилищ, NAS и облачных протоколов.</strong>
</p>

---

## 1. Паспорт проекта (Project Identity)

| Параметр | Значение |
|---|---|
| **Имя приложения (Launcher)** | `Scout` |
| **Заголовок Google Play (RU)** | `Scout: Проводник и облака` (25/30 символов) |
| **Заголовок Google Play (EN)** | `Scout: File Manager & NAS` (25/30 символов) |
| **Идентификатор (applicationId)** | `dev.scout.files` |
| **Минимальная версия Android (minSdk)** | `26` (Android 8.0 Oreo) |
| **Целевая версия Android (targetSdk / compileSdk)** | `35` (Android 15) |
| **Архитектурный стек** | Kotlin 2.1+, Jetpack Compose (Material 3), Coroutines/Flow, MVI, Hilt (`@IntoSet` Multibindings), Room (KSP), DataStore |

---

## 2. Архитектура и структура модулей

Scout построен на базе чистой модульной архитектуры со строгим разделением ответственности:

```text
Scout/
├── app/                        # Точка входа приложения (:app), DI граф, MainActivity
├── core/
│   ├── model/                  # Чистый Kotlin/JVM модуль доменных моделей (VfsUri, VfsNode, FileTag, StorageVolume, VfsException)
│   ├── vfs/                    # Android Library: ядро виртуальной файловой системы (VfsRouter, FileSystemProvider, RandomAccessContent)
│   ├── database/               # Room + KSP: закладки избранного (BookmarkEntity) и цветовые теги Finder (FileTagCrossRefEntity)
│   └── designsystem/           # Дизайн-система (Material 3, Dynamic Color, ScoutPathBar)
├── providers/                  # Провайдеры протоколов (реализуют FileSystemProvider и подключаются через @Binds @IntoSet)
│   ├── local/                  # Локальная файловая система (file://) и Storage Access Framework (content://, SAF)
│   ├── sftp/                   # SSH File Transfer Protocol (в разработке)
│   ├── smb/                    # SMB / Samba сетевые шары (в разработке)
│   ├── webdav/                 # Nextcloud, ownCloud, WebDAV (в разработке)
│   └── ...
├── viewers/                    # Встроенные просмотрщики файлов
│   └── ...
└── feature/                    # Экранные фичи
    └── explorer/               # Главный экран проводника: MVI, вкладки, двухпанельный режим, сайдбар Finder/Nautilus, Quick Look
```

### Принципы изоляции модулей
1. **`:core:model`** — не зависит от Android SDK, чистый JVM модуль.
2. **`:core:vfs`** — зависит только от `:core:model`, Coroutines и Hilt. Предоставляет `VfsRouter` с кросс-протокольным потоковым копированием. Не содержит конкретных реализаций ФС.
3. **`:core:database`** — модуль персистентности на базе Room и KSP (`MetadataRepository`).
4. **`:providers:*`** — изолированные провайдеры файловых систем, регистрирующиеся в графе зависимостей через мультибиндинг `@Binds @IntoSet FileSystemProvider`.
5. **`:feature:explorer`** — Compose UI экран проводника с однонаправленным потоком данных (MVI), поддержкой вкладок, двухпанельного режима, сайдбара и быстрого просмотра Quick Look.
6. **Все файловые и сетевые операции** изолированы на пуле `Dispatchers.IO` с обязательной проверкой отмены (`ensureActive()`).

---

## 3. Поддерживаемые протоколы VFS

- [x] **`file://`** — локальная файловая система (POSIX / Linux / Android)
- [x] **`content://`** — Android Storage Access Framework (SAF / DocumentFile)
- [ ] **`smb://`** — Samba / Windows Network Share
- [ ] **`sftp://`** — SSH File Transfer Protocol
- [ ] **`ftp://` / `ftps://`** — File Transfer Protocol
- [ ] **`webdav://`** — WebDAV (Nextcloud / ownCloud / Yandex Disk)
- [ ] **`gdrive://`** — Google Drive REST API
- [ ] **`usb://`** — Прямой доступ к USB OTG носителям через Android USB Host API
- [ ] **`archive://`** — Просмотр zip / tar / 7z как виртуальных каталогов

---

## 4. Дорожная карта (Roadmap)

- [x] **Этап 1: Старт и ядро VFS (v0.1.0-alpha)**
  - [x] Подготовка сборочного окружения на Ubuntu (JDK 17, Android SDK 35, Gradle 8.11.1).
  - [x] Создание многомодульной структуры (`:app`, `:core:model`, `:core:vfs`, `:core:designsystem`).
  - [x] Реализация доменного слоя (`VfsUri`, `VfsNode`, `StorageVolume`, `FileTag`, `VfsException`).
  - [x] Реализация ядра VFS (`VfsRouter`, `FileSystemProvider`, `RandomAccessContent`, межпротокольное потоковое копирование).
  - [x] Компонент `ScoutPathBar` с переключением между хлебными крошками и строкой URI.
  - [x] Модульное тестирование маршрутизации и межпротокольной передачи.
- [x] **Этап 2: Локальные провайдеры, база данных и проводник (v0.2.0-alpha)**
  - [x] Выделение модуля `:providers:local`: `LocalFileSystemProvider` (`file://`), `SafFileSystemProvider` (`content://`), `LocalStorageVolumeManager`, `StoragePermissionHelper`.
  - [x] Модуль `:core:database`: Room + KSP, `BookmarkDao`, `FileTagDao`, `MetadataRepository`.
  - [x] Модуль `:feature:explorer`: MVI архитектура, вкладки (Tabs), двухпанельный режим (Dual-Pane), боковая панель Finder/Nautilus, список и сетка, Quick Look и цветовые теги.
  - [x] Интеграция в `:app` и полное двуязычное покрытие (RU / EN).
- [ ] **Этап 3: Сетевые протоколы (NAS & Clouds)**
  - [ ] Интеграция SMB, SFTP, WebDAV, Google Drive.
- [ ] **Этап 4: Встроенные просмотрщики (Viewers)**
  - [ ] Code Editor, Markdown, PDF, Media Player с поддержкой seekable random access.

---

## 5. Сборка и тестирование

### Предварительные требования
* Ubuntu 22.04+ (или совместимый Linux дистрибутив)
* OpenJDK 17 (`JAVA_HOME` указывает на JDK 17)
* Android SDK 35 (build-tools 35.0.0, platform-tools, platforms;android-35)

### Команды сборки
```bash
# Запуск всех unit-тестов
./gradlew testDebugUnitTest

# Сборка Debug APK
./gradlew assembleDebug

# Полная валидация
./gradlew testDebugUnitTest assembleDebug
```
Собранный файл находится по пути:
`app/build/outputs/apk/debug/app-debug.apk`
