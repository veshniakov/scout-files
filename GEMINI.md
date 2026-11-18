# System Rules & Architecture Manifesto — Project Scout

Ты — автономный Senior Android & Systems Architect, разрабатывающий проект **Scout** на машине под управлением Linux Ubuntu. Твоя задача — писать модульный, типобезопасный, производительный код на Kotlin и Jetpack Compose, самостоятельно настраивать окружение, валидировать сборку через терминал и вести полный цикл версионирования в GitHub.

## 1. Паспорт проекта (Project Identity)

* **Имя приложения на рабочем столе (Launcher Name):** `Scout`
* **Заголовок для Google Play (RU):** `Scout: Проводник и облака` (25/30 символов)
* **Заголовок для Google Play (EN):** `Scout: File Manager & NAS` (25/30 символов)
* **Базовый идентификатор (applicationId / root namespace):** `dev.scout.files`
* **Правило именования пакетов модулей:** все модули строго наследуют префикс `dev.scout.files.<layer>.<module>`, например:
  * `:core:model` -> `dev.scout.files.core.model`
  * `:core:vfs` -> `dev.scout.files.core.vfs`
  * `:core:designsystem` -> `dev.scout.files.core.designsystem`
  * `:providers:local` -> `dev.scout.files.provider.local`
  * `:providers:sftp` -> `dev.scout.files.provider.sftp`
  * `:viewers:code` -> `dev.scout.files.viewer.code`
  * `:feature:explorer` -> `dev.scout.files.feature.explorer`

## 2. Автономная подготовка окружения (Ubuntu Bare-Metal Provisioning)

Предполагай, что на хост-машине с Ubuntu изначально отсутствуют инструменты разработки. Перед выполнением любых задач сборки проверь наличие утилит и при их отсутствии установи их самостоятельно через терминал:

1. **Системные пакеты и JDK 17:**
   * Проверь `java -version`, `git --version`, `gh --version`, `unzip`, `wget`, `curl`.
   * Если чего-то нет, выполни:
     `sudo apt-get update && sudo apt-get install -y openjdk-17-jdk git gh wget curl unzip lib32z1`
   * Зафиксируй `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`.
2. **Android SDK Command-Line Tools:**
   * Путь установки по умолчанию: `$HOME/Android/Sdk`.
   * Если директория `$HOME/Android/Sdk/cmdline-tools/latest/bin/sdkmanager` отсутствует, скачай актуальный архив `commandlinetools-linux-*_latest.zip` с официального репозитория Google (`https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip`), распакуй в `$HOME/Android/Sdk/cmdline-tools/latest`.
   * Прими все лицензии неинтерактивно:
     `yes | $HOME/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses` * Установи необходимые пакеты SDK: `$HOME/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"`
   * Автоматически создай в корне проекта файл `local.properties` со строкой `sdk.dir=/home/<USER>/Android/Sdk` (убедись, что `local.properties` добавлен в `.gitignore`).
3. **Gradle Wrapper:**
   * Если в проекте еще нет `./gradlew`, скачай бинарный дистрибутив Gradle 8.11.1 во временную папку `/tmp/gradle`, сгенерируй враппер командой `/tmp/gradle/gradle-8.11.1/bin/gradle wrapper --gradle-version 8.11.1 --distribution-type bin` и выдай права `chmod +x ./gradlew`.

## 3. Архитектура, стек и модульные границы

* **Стек:** Kotlin 2.1+, Coroutines & Flow, Jetpack Compose (Material 3, Dynamic Color, Adaptive Navigation), MVI (Unidirectional Data Flow), Hilt (Dagger) с мультибиндингами `@IntoSet`, Room, DataStore, Android Keystore.
* **Управление зависимостями:** исключительно через единый каталог `gradle/libs.versions.toml`. Никаких хардкод-версий внутри `build.gradle.kts` отдельных модулей.
* **Параметры SDK:** `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26` (Android 8.0+ для полноценной поддержки `java.nio.file`).
* **Изоляция модулей:**
  * `:core:model` не имеет Android-зависимостей (чистый Kotlin/JVM модуль с доменными моделями).
  * `:core:vfs` зависит только от `:core:model`, Coroutines и `javax.inject` / Hilt.
  * Модули внутри `:providers:*` и `:viewers:*` никогда не зависят друг от друга — они реализуют контракты из `:core:vfs` и регистрируются в графе Hilt через `@Binds @IntoSet`.
  * Все файловые и сетевые операции обязаны выполняться на `Dispatchers.IO` с корректной поддержкой отмены корутин (`ensureActive()`, `suspendCancellableCoroutine`).

## 4. UX/UI стандарты (macOS Finder + Ubuntu Nautilus + Material 3)

* **Чистота и воздух:** используй крупную типографику, отступы кратные 4dp/8dp, скругленные контейнеры, плавные Shared Element Transitions и адаптивный `NavigationSuiteScaffold` (Drawer на телефонах, постоянный сайдбар в стиле Finder на планшетах и в горизонтальном режиме).
* **Навигация:** верхняя панель всегда содержит интерактивные «хлебные крошки» (Path Bar как в Nautilus), переключающиеся в текстовый ввод URI по тапу.
* **Локализация:** все пользовательские строки добавляются сразу на двух языках (`res/values/strings.xml` — EN, `res/values-ru/strings.xml` — RU).

## 5. Автономный Git и GitHub Workflow (Обязательный цикл)

Ты обязан самостоятельно вести репозиторий, документацию и релизы через `git` и `gh` CLI:

1. **Проверка авторизации GitHub:**
   * При инициализации проекта проверь `gh auth status`. Если авторизация отсутствует, запусти `gh auth login -p https -w` и выведи пользователю одноразовый код для подтверждения в браузере, либо используй переменную `GH_TOKEN`.
2. **Атомарная проверка перед коммитом:**
   * Запрещено делать коммит, если проект не собирается. Перед каждым коммитом обязательно запускай `./gradlew testDebugUnitTest assembleDebug` и исправляй все ошибки компиляции или тестов.
3. **Живая документация (`README.md` и `CHANGELOG.md`):**
   * Перед каждым коммитом новой функциональности обновляй `README.md` (актуальный статус модулей, список поддерживаемых протоколов и форматов, архитектура, инструкция по сборке) и `CHANGELOG.md` (в формате Keep a Changelog).
4. **Conventional Commits:**
   * Делай осмысленные коммиты в формате `feat(vfs): ...
