package dev.scout.files.core.operations.model

import dev.scout.files.core.model.VfsUri
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

enum class FileOperationType {
    COPY,
    MOVE,
    DELETE,
    COMPRESS,
    EXTRACT
}

enum class OperationStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    WAITING_CONFLICT,
    WAITING_PASSWORD,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class ConflictAction {
    OVERWRITE,
    SKIP,
    KEEP_BOTH
}

data class ConflictDecision(
    val action: ConflictAction,
    val applyToAll: Boolean = false
)

data class FileConflictPrompt(
    val taskId: String,
    val sourceUri: VfsUri,
    val targetUri: VfsUri,
    val isDirectory: Boolean,
    val response: CompletableDeferred<ConflictDecision>
)

data class ArchivePasswordPrompt(
    val taskId: String,
    val archiveUri: VfsUri,
    val isWrongPassword: Boolean = false,
    val response: CompletableDeferred<String?>
)

data class FileOperationTask(
    val id: String = UUID.randomUUID().toString(),
    val type: FileOperationType,
    val sources: List<VfsUri>,
    val destinationDir: VfsUri? = null,
    val archiveName: String? = null,
    val archivePassword: String? = null,
    val status: OperationStatus = OperationStatus.QUEUED,
    val processedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val processedFiles: Int = 0,
    val totalFiles: Int = 0,
    val currentFileName: String? = null,
    val speedBytesPerSec: Long = 0L,
    val errorMessage: String? = null
) {
    val isTerminal: Boolean
        get() = status == OperationStatus.COMPLETED ||
            status == OperationStatus.FAILED ||
            status == OperationStatus.CANCELLED

    val progress: Float
        get() = when {
            totalBytes > 0 -> (processedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            totalFiles > 0 -> (processedFiles.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}
