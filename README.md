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
├── app/                        # Точка входа приложения (:app), DI граф, MainActivity (FragmentActivity)
├── core/
│   ├── model/                  # Чистый Kotlin/JVM модуль доменных моделей (VfsUri, ArchiveUri, VfsNode, FileTag, StorageVolume, ServerConnection, ServerCredentials, NetworkProtocol, AuthType, WebDavPreset, VfsException)
│   ├── vfs/                    # Android Library: ядро виртуальной файловой системы (VfsRouter, FileSystemProvider, RandomAccessContent, ArchivePasswordStore)
│   ├── operations/             # Фоновые файловые операции (:core:operations): Foreground Service, очередь задач, скорость/прогресс, конфликты
│   ├── database/               # Room + KSP (v2): закладки (BookmarkEntity), теги Finder (FileTagCrossRefEntity), сетевые подключения (ServerConnectionEntity, ServerConnectionDao)
│   ├── security/               # Аппаратный CredentialVault (AES/GCM в AndroidKeyStore), InMemoryCredentialVault, BiometricAuthChecker (androidx.biometric)
│   └── designsystem/           # Дизайн-система (Material 3, Dynamic Color, ScoutPathBar)
├── providers/                  # Провайдеры протоколов (реализуют FileSystemProvider и подключаются через @Binds @IntoSet)
│   ├── local/                  # Локальная файловая система (file://) и Storage Access Framework (content://, SAF)
│   ├── archive/                # Виртуальные архивы (archive://): .zip, .rar, .7z, .tar.*, пароли AES-256
│   ├── sftp/                   # SSH File Transfer Protocol (sftp://): SSHJ, Ed25519, RSA, SftpSessionPool, random-access
│   ├── smb/                    # Samba / Windows Share (smb://): SMBJ, SMBv2/v3, гостевой вход, SmbSessionPool, random-access
│   ├── ftp/                    # FTP / FTPS (ftp://, ftps://): Commons Net, пассивный бинарный режим, UTF-8, restart offset
│   ├── webdav/                 # WebDAV & AWS S3 (webdav://, s3://): OkHttp, PROPFIND XML, пресеты Nextcloud/ownCloud/Yandex.Disk, AWS SigV4 signer, Range headers
│   ├── gdrive/                 # Google Drive API v3 (gdrive://): OAuth2 автообновление, кэш путей fileId, экспорт Docs/Sheets/Slides
│   └── ...
├── viewers/                    # Встроенные просмотрщики файлов
│   └── ...
└── feature/                    # Экранные фичи
    ├── connections/            # Мастер настройки серверов (:feature:connections): ServerConnectionSheet, пресеты, тест связи
    └── explorer/               # Главный экран проводника: MVI, вкладки, двухпанельный режим, сайдбар Finder/Nautilus, раздел «Сеть и облака», биометрия, Quick Look, архивы и очередь операций
```

### Принципы изоляции модулей
1. **`:core:model`** — не зависит от Android SDK, чистый JVM модуль с неизменяемыми доменными моделями.
2. **`:core:vfs`** — зависит только от `:core:model`, Coroutines и Hilt. Предоставляет `VfsRouter` с кросс-протокольным потоковым копированием. Не содержит конкретных реализаций сетевых или файловых протоколов.
3. **`:core:database`** — модуль персистентности на базе Room и KSP (`MetadataRepository`, версия схемы 2).
4. **`:core:security`** — модуль аппаратной защиты учетных данных на базе AndroidKeyStore (`KeystoreCredentialVault`, AES/GCM) и биометрического контроля доступа `BiometricAuthChecker` (`androidx.biometric:1.1.0`).
5. **`:providers:*`** — полностью изолированные независимые провайдеры файловых систем, регистрирующиеся в общем графе зависимостей через мультибиндинг `@Binds @IntoSet FileSystemProvider`. Провайдеры не зависят друг от друга.
6. **`:feature:connections`** — модальный мастер настройки удаленных подключений с валидацией, динамическими полями и live-тестированием через `VfsRouter`.
7. **`:feature:explorer`** — Compose UI экран проводника с однонаправленным потоком данных (MVI), поддержкой вкладок, двухпанельного режима, боковой панели со встроенным разделом сетевых подключений и быстрым просмотром Quick Look.
8. **Все файловые и сетевые операции** изолированы на пуле `Dispatchers.IO` с обязательной проверкой отмены (`ensureActive()`, `suspendCancellableCoroutine`).

---

## 3. Поддерживаемые протоколы VFS

- [x] **`file://`** — локальная файловая система (POSIX / Linux / Android)
- [x] **`content://`** — Android Storage Access Framework (SAF / DocumentFile)
- [x] **`archive://`** — Виртуальные архивы: ZIP, RAR (RAR4/RAR5), 7z, TAR, TAR.GZ, TAR.BZ2, TAR.XZ с поддержкой паролей
- [x] **`smb://`** — Samba / Windows Network Share (SMBv2 / SMBv3, гостевой вход, доменная авторизация, seekable streaming)
- [x] **`sftp://`** — SSH File Transfer Protocol (SSHJ, авторизация по паролю и ключам Ed25519/RSA с пассфразой, seekable streaming)
- [x] **`ftp://` / `ftps://`** — File Transfer Protocol (пассивный бинарный режим, UTF-8, TLS/FTPS, потоковое смещение restart offset)
- [x] **`webdav://`** — WebDAV (XML PROPFIND, Range-стриминг, готовые пресеты Nextcloud, ownCloud, Yandex.Disk)
- [x] **`s3://`** — Amazon S3 / MinIO / Cloudflare R2 / Backblaze B2 (чистый Kotlin AWS SigV4 signer, ListObjectsV2, Range-стриминг)
- [x] **`gdrive://`** — Google Drive REST API v3 (OAuth2 автообновление токенов при 401, прозрачный кэш разрешения путей, конвертация Google Docs/Sheets/Slides в PDF/XLSX/DOCX)
- [ ] **`usb://`** — Прямой доступ к USB OTG носителям через Android USB Host API

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
- [x] **Этап 3: Фоновые операции и виртуальные архивы (v0.3.0-alpha)**
  - [x] Модуль `:core:operations`: Диспетчер `FileOperationManager`, Foreground Service (`dataSync`), отслеживание скорости передачи и байт-прогресса, пауза, возобновление и отмена.
  - [x] Интерактивное разрешение конфликтов файлов (`OVERWRITE`, `SKIP`, `KEEP_BOTH`) с опцией «Применить ко всем».
  - [x] Модуль `:providers:archive`: Полноценная виртуальная файловая система `archive://` для ZIP, RAR, 7z, TAR, TAR.GZ, TAR.BZ2, TAR.XZ без полной распаковки на диск.
  - [x] Поддержка защищенных паролем архивов (AES-256 ZIP, RAR, 7z) и сессионный потокобезопасный `ArchivePasswordStore`.
  - [x] Создание архивов `.zip` (с опциональным шифрованием AES-256) и `.tar.gz`.
  - [x] Интеграция в `:feature:explorer`: прозрачный переход в архив как в папку, модальные диалоги ввода пароля и разрешения конфликтов, `OperationsQueueSheet` и действие сжатия/распаковки.
- [x] **Этап 4: Сетевые и облачные протоколы, защита учетных записей и менеджер подключений (v0.4.0-alpha)**
  - [x] Модуль `:core:security`: Защищенное хранилище `KeystoreCredentialVault` с аппаратным шифрованием AES/GCM в AndroidKeyStore, герметичный `InMemoryCredentialVault`, биометрическая аутентификация `BiometricAuthChecker` на базе `androidx.biometric`.
  - [x] Модуль `:core:database`: Расширение Room БД до версии 2, таблица `server_connections`, `ServerConnectionDao` и реактивный `MetadataRepository` для CRUD-операций с профилями серверов.
  - [x] Модуль `:providers:sftp`: Полноценный SFTP-клиент на базе SSHJ и BouncyCastle, пул сессий `SftpSessionPool` с автовращением и повторным подключением, авторизация по паролю и SSH-ключам (Ed25519, RSA с пассфразой), произвольный доступ `openRandomAccess`.
  - [x] Модуль `:providers:smb`: SMBv2/SMBv3 клиент на базе SMBJ, пул сессий `SmbSessionPool`, гостевой вход, авторизация по домену и паролю, чтение со смещением в `openRandomAccess`.
  - [x] Модуль `:providers:ftp`: Поддержка FTP и FTPS на базе Commons Net в пассивном бинарном режиме с поддержкой UTF-8 и потоковым позиционированием `setRestartOffset`.
  - [x] Модуль `:providers:webdav`: Реализация WebDAV (`webdav://`) на OkHttp с XML PROPFIND парсингом, пресетами Yandex.Disk, Nextcloud, ownCloud, и реализация AWS S3 (`s3://`) с чистым Kotlin AWS SigV4 подписыванием для MinIO, AWS S3, Cloudflare R2, Backblaze B2.
  - [x] Модуль `:providers:gdrive`: Интеграция с Google Drive REST API v3, автоматическое обновление OAuth токенов при 401, прозрачный кэш разрешения путей в fileId, экспорт документов Google Docs/Sheets/Slides в PDF/XLSX/DOCX.
  - [x] Модуль `:feature:connections`: Мастер подключения `ServerConnectionSheet` с динамическими полями под протоколы, автозаполнением пресетов, переключателем биометрии и кнопкой проверки соединения «Test Connection».
  - [x] Интеграция в `:feature:explorer`: Раздел «Сеть и облака» (Network & Cloud) в сайдбаре с бейджами протоколов, диалог создания/редактирования/удаления, биометрическая защита при входе, поддержка URI в `ScoutPathBar`, фоновые операции между облаками и локальными дисками.
  - [x] Полная двуязычная локализация (EN / RU) и 165+ модульных тестов с чистой сборкой.
- [ ] **Этап 5: Встроенные просмотрщики (Viewers)**
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
