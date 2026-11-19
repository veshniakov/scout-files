package dev.scout.files.core.model

enum class WebDavPreset(
    val defaultHost: String,
    val defaultPort: Int,
    val defaultRootPath: String,
    val useTls: Boolean,
    val isCustom: Boolean = false
) {
    CUSTOM(
        defaultHost = "",
        defaultPort = 443,
        defaultRootPath = "/",
        useTls = true,
        isCustom = true
    ),
    NEXTCLOUD(
        defaultHost = "",
        defaultPort = 443,
        defaultRootPath = "/remote.php/dav/files/{username}/",
        useTls = true
    ),
    OWNCLOUD(
        defaultHost = "",
        defaultPort = 443,
        defaultRootPath = "/remote.php/webdav/",
        useTls = true
    ),
    YANDEX_DISK(
        defaultHost = "webdav.yandex.ru",
        defaultPort = 443,
        defaultRootPath = "/",
        useTls = true
    )
}
