package dev.scout.files.provider.gdrive

data class DriveFileItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
    val modifiedTimeMillis: Long = 0L,
    val parents: List<String> = emptyList(),
    val trashed: Boolean = false
) {
    val isFolder: Boolean
        get() = mimeType == "application/vnd.google-apps.folder"

    val isGoogleDoc: Boolean
        get() = mimeType.startsWith("application/vnd.google-apps.") && !isFolder
}

data class DriveFileListResponse(
    val files: List<DriveFileItem>,
    val nextPageToken: String? = null
)

data class TokenResponse(
    val accessToken: String,
    val expiresIn: Long? = null,
    val tokenType: String? = null
)

data class DriveErrorResponse(
    val code: Int,
    val message: String,
    val reason: String? = null
)
