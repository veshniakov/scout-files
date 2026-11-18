package dev.scout.files.core.operations

import android.content.Context
import dev.scout.files.core.model.ArchiveUri
import dev.scout.files.core.model.VfsException
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.operations.model.ArchivePasswordPrompt
import dev.scout.files.core.operations.model.ConflictAction
import dev.scout.files.core.operations.model.ConflictDecision
import dev.scout.files.core.operations.model.FileConflictPrompt
import dev.scout.files.core.operations.model.FileOperationTask
import dev.scout.files.core.operations.model.FileOperationType
import dev.scout.files.core.operations.model.OperationStatus
import dev.scout.files.core.vfs.ArchivePasswordStore
import dev.scout.files.core.vfs.VfsRouter
import dev.scout.files.core.vfs.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Singleton dispatcher managing background file operations queue, conflicts and archive passwords.
 */
@Singleton
class FileOperationManager @Inject constructor(
    private val vfsRouter: VfsRouter,
    private val archivePasswordStore: ArchivePasswordStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationContext private val context: Context? = null
) {
    // Secondary constructor for testing without Android Context
    constructor(
        vfsRouter: VfsRouter,
        archivePasswordStore: ArchivePasswordStore,
        ioDispatcher: CoroutineDispatcher
    ) : this(vfsRouter, archivePasswordStore, ioDispatcher, null)

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _tasks = MutableStateFlow<List<FileOperationTask>>(emptyList())
    val tasks: StateFlow<List<FileOperationTask>> = _tasks.asStateFlow()

    private val _activeConflict = MutableStateFlow<FileConflictPrompt?>(null)
    val activeConflict: StateFlow<FileConflictPrompt?> = _activeConflict.asStateFlow()

    private val _activePasswordPrompt = MutableStateFlow<ArchivePasswordPrompt?>(null)
    val activePasswordPrompt: StateFlow<ArchivePasswordPrompt?> = _activePasswordPrompt.asStateFlow()

    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val pausedTaskIds = ConcurrentHashMap.newKeySet<String>()

    fun enqueueCopy(sources: List<VfsUri>, destinationDir: VfsUri): String {
        return enqueue(
            FileOperationTask(
                type = FileOperationType.COPY,
                sources = sources,
                destinationDir = destinationDir,
                totalFiles = sources.size
            )
        )
    }

    fun enqueueMove(sources: List<VfsUri>, destinationDir: VfsUri): String {
        return enqueue(
            FileOperationTask(
                type = FileOperationType.MOVE,
                sources = sources,
                destinationDir = destinationDir,
                totalFiles = sources.size
            )
        )
    }

    fun enqueueDelete(sources: List<VfsUri>): String {
        return enqueue(
            FileOperationTask(
                type = FileOperationType.DELETE,
                sources = sources,
                totalFiles = sources.size
            )
        )
    }

    fun enqueueCompress(
        sources: List<VfsUri>,
        destinationDir: VfsUri,
        archiveName: String,
        password: String? = null
    ): String {
        return enqueue(
            FileOperationTask(
                type = FileOperationType.COMPRESS,
                sources = sources,
                destinationDir = destinationDir,
                archiveName = archiveName,
                archivePassword = password,
                totalFiles = sources.size
            )
        )
    }

    fun enqueueExtract(archiveUri: VfsUri, destinationDir: VfsUri): String {
        return enqueue(
            FileOperationTask(
                type = FileOperationType.EXTRACT,
                sources = listOf(archiveUri),
                destinationDir = destinationDir,
                totalFiles = 1
            )
        )
    }

    private fun enqueue(task: FileOperationTask): String {
        _tasks.update { it + task }
        triggerServiceIfNeeded()
        scheduleNext()
        return task.id
    }

    private fun triggerServiceIfNeeded() {
        context?.let { ctx ->
            runCatching {
                FileOperationForegroundService.start(ctx)
            }
        }
    }

    fun pause(taskId: String) {
        pausedTaskIds.add(taskId)
        updateTask(taskId) { it.copy(status = OperationStatus.PAUSED, speedBytesPerSec = 0L) }
    }

    fun resume(taskId: String) {
        pausedTaskIds.remove(taskId)
        updateTask(taskId) { it.copy(status = OperationStatus.RUNNING) }
    }

    fun cancel(taskId: String) {
        pausedTaskIds.remove(taskId)
        val job = runningJobs[taskId]
        job?.cancel()
        updateTask(taskId) { it.copy(status = OperationStatus.CANCELLED, speedBytesPerSec = 0L) }
    }

    fun resolveConflict(decision: ConflictDecision) {
        val prompt = _activeConflict.value ?: return
        prompt.response.complete(decision)
        _activeConflict.value = null
    }

    fun submitPassword(password: String?) {
        val prompt = _activePasswordPrompt.value ?: return
        prompt.response.complete(password)
        _activePasswordPrompt.value = null
    }

    fun clearTerminalTasks() {
        _tasks.update { list -> list.filterNot { it.isTerminal } }
    }

    @Synchronized
    private fun scheduleNext() {
        val currentRunning = _tasks.value.firstOrNull { it.status == OperationStatus.RUNNING }
        if (currentRunning != null) return

        val nextTask = _tasks.value.firstOrNull { it.status == OperationStatus.QUEUED } ?: return
        val taskId = nextTask.id

        val job = scope.launch {
            try {
                if (pausedTaskIds.contains(taskId)) {
                    checkPauseBarrier(taskId)
                }
                updateTask(taskId) { current ->
                    if (current.status == OperationStatus.PAUSED) current else current.copy(status = OperationStatus.RUNNING)
                }
                executeTask(taskId)
                updateTask(taskId) { it.copy(status = OperationStatus.COMPLETED, speedBytesPerSec = 0L) }
            } catch (e: CancellationException) {
                updateTask(taskId) { it.copy(status = OperationStatus.CANCELLED, speedBytesPerSec = 0L) }
            } catch (e: Exception) {
                updateTask(taskId) {
                    it.copy(
                        status = OperationStatus.FAILED,
                        errorMessage = e.message ?: e.javaClass.simpleName,
                        speedBytesPerSec = 0L
                    )
                }
            } finally {
                runningJobs.remove(taskId)
                pausedTaskIds.remove(taskId)
                scheduleNext()
            }
        }
        runningJobs[taskId] = job
    }

    private suspend fun executeTask(taskId: String) {
        val task = getTask(taskId) ?: return
        when (task.type) {
            FileOperationType.COPY -> executeCopyOrMove(taskId, isMove = false)
            FileOperationType.MOVE -> executeCopyOrMove(taskId, isMove = true)
            FileOperationType.DELETE -> executeDelete(taskId)
            FileOperationType.COMPRESS -> executeCompress(taskId)
            FileOperationType.EXTRACT -> executeExtract(taskId)
        }
    }

    private suspend fun executeCopyOrMove(taskId: String, isMove: Boolean) {
        val task = getTask(taskId) ?: return
        val destDir = task.destinationDir ?: return

        var savedConflictAction: ConflictAction? = null

        // Calculate total size if possible
        var totalBytesCalculated = 0L
        for (source in task.sources) {
            totalBytesCalculated += calculateTotalBytes(source)
        }
        updateTask(taskId) { it.copy(totalBytes = totalBytesCalculated) }

        var processedFilesCount = 0

        for (source in task.sources) {
            coroutineContext.ensureActive()
            checkPauseBarrier(taskId)

            val sourceMeta = runCatching { vfsRouter.getMetadata(source) }.getOrNull()
            val isDir = sourceMeta?.isDirectory ?: false
            val initialTarget = destDir.resolve(source.name)

            val (effectiveTarget, shouldSkip) = resolveTargetWithConflict(
                taskId = taskId,
                sourceUri = source,
                targetUri = initialTarget,
                isDirectory = isDir,
                savedConflict = savedConflictAction
            ) { decision ->
                if (decision.applyToAll) {
                    savedConflictAction = decision.action
                }
            }

            if (shouldSkip) {
                processedFilesCount++
                updateTask(taskId) { it.copy(processedFiles = processedFilesCount) }
                continue
            }

            copyOrMoveNode(
                taskId = taskId,
                sourceUri = source,
                targetUri = effectiveTarget,
                isMove = isMove,
                savedConflictRef = { savedConflictAction },
                onApplyToAll = { savedConflictAction = it }
            )

            processedFilesCount++
            updateTask(taskId) { it.copy(processedFiles = processedFilesCount) }
        }
    }

    private suspend fun copyOrMoveNode(
        taskId: String,
        sourceUri: VfsUri,
        targetUri: VfsUri,
        isMove: Boolean,
        savedConflictRef: () -> ConflictAction?,
        onApplyToAll: (ConflictAction) -> Unit
    ) {
        coroutineContext.ensureActive()
        checkPauseBarrier(taskId)

        val meta = vfsRouter.getMetadata(sourceUri)
        if (meta.isDirectory) {
            if (!vfsRouter.exists(targetUri)) {
                vfsRouter.createDirectory(targetUri)
            }
            val children = vfsRouter.listDirectory(sourceUri)
            for (child in children) {
                coroutineContext.ensureActive()
                checkPauseBarrier(taskId)

                val childTarget = targetUri.resolve(child.name)
                val (effectiveChildTarget, shouldSkip) = resolveTargetWithConflict(
                    taskId = taskId,
                    sourceUri = child.uri,
                    targetUri = childTarget,
                    isDirectory = child.isDirectory,
                    savedConflict = savedConflictRef()
                ) { decision ->
                    if (decision.applyToAll) {
                        onApplyToAll(decision.action)
                    }
                }

                if (shouldSkip) continue

                copyOrMoveNode(
                    taskId = taskId,
                    sourceUri = child.uri,
                    targetUri = effectiveChildTarget,
                    isMove = isMove,
                    savedConflictRef = savedConflictRef,
                    onApplyToAll = onApplyToAll
                )
            }
            if (isMove) {
                vfsRouter.delete(sourceUri, recursive = true)
            }
        } else {
            updateTask(taskId) { it.copy(currentFileName = sourceUri.name) }
            copyStreamWithSpeed(taskId, sourceUri, targetUri)
            if (isMove) {
                vfsRouter.delete(sourceUri, recursive = false)
            }
        }
    }

    private suspend fun executeDelete(taskId: String) {
        val task = getTask(taskId) ?: return
        var processed = 0
        for (source in task.sources) {
            coroutineContext.ensureActive()
            checkPauseBarrier(taskId)
            updateTask(taskId) { it.copy(currentFileName = source.name) }
            vfsRouter.delete(source, recursive = true)
            processed++
            updateTask(taskId) { it.copy(processedFiles = processed) }
        }
    }

    private suspend fun executeCompress(taskId: String) {
        val task = getTask(taskId) ?: return
        val destDir = task.destinationDir ?: return
        val archiveName = task.archiveName ?: "archive.zip"
        val targetArchiveUri = destDir.resolve(archiveName)

        val isTarGz = archiveName.endsWith(".tar.gz", ignoreCase = true) || archiveName.endsWith(".tgz", ignoreCase = true)

        var totalBytesCalculated = 0L
        for (source in task.sources) {
            totalBytesCalculated += calculateTotalBytes(source)
        }
        updateTask(taskId) { it.copy(totalBytes = totalBytesCalculated, currentFileName = archiveName) }

        if (isTarGz) {
            compressToTarGz(taskId, task.sources, targetArchiveUri)
        } else {
            compressToZip(taskId, task.sources, targetArchiveUri, task.archivePassword)
        }
    }

    private suspend fun compressToZip(
        taskId: String,
        sources: List<VfsUri>,
        targetArchiveUri: VfsUri,
        password: String?
    ) {
        val outputStream = vfsRouter.openOutputStream(targetArchiveUri, append = false)
        val zipParams = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            if (!password.isNullOrEmpty()) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }

        val zipOut = if (!password.isNullOrEmpty()) {
            ZipOutputStream(outputStream, password.toCharArray())
        } else {
            ZipOutputStream(outputStream)
        }

        zipOut.use { zos ->
            var lastUpdate = System.currentTimeMillis()
            var lastBytes = 0L

            suspend fun addVfsNode(sourceUri: VfsUri, entryPath: String) {
                coroutineContext.ensureActive()
                checkPauseBarrier(taskId)

                val meta = vfsRouter.getMetadata(sourceUri)
                if (meta.isDirectory) {
                    val dirEntryPath = if (entryPath.endsWith("/")) entryPath else "$entryPath/"
                    val params = ZipParameters(zipParams).apply { fileNameInZip = dirEntryPath }
                    zos.putNextEntry(params)
                    zos.closeEntry()

                    val children = vfsRouter.listDirectory(sourceUri)
                    for (child in children) {
                        addVfsNode(child.uri, "$dirEntryPath${child.name}")
                    }
                } else {
                    updateTask(taskId) { it.copy(currentFileName = sourceUri.name) }
                    val params = ZipParameters(zipParams).apply { fileNameInZip = entryPath }
                    zos.putNextEntry(params)

                    vfsRouter.openInputStream(sourceUri).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            coroutineContext.ensureActive()
                            checkPauseBarrier(taskId)

                            zos.write(buffer, 0, read)

                            val currentTask = getTask(taskId) ?: break
                            val newProcessed = currentTask.processedBytes + read
                            val now = System.currentTimeMillis()
                            val dt = now - lastUpdate
                            val speed = if (dt >= 500) {
                                val sp = ((newProcessed - lastBytes) * 1000L) / dt
                                lastBytes = newProcessed
                                lastUpdate = now
                                sp
                            } else currentTask.speedBytesPerSec

                            updateTask(taskId) {
                                it.copy(processedBytes = newProcessed, speedBytesPerSec = speed)
                            }
                        }
                    }
                    zos.closeEntry()
                }
            }

            for (source in sources) {
                addVfsNode(source, source.name)
            }
        }
    }

    private suspend fun compressToTarGz(
        taskId: String,
        sources: List<VfsUri>,
        targetArchiveUri: VfsUri
    ) {
        val outputStream = vfsRouter.openOutputStream(targetArchiveUri, append = false)
        val tarOut = TarArchiveOutputStream(GzipCompressorOutputStream(BufferedOutputStream(outputStream)))

        tarOut.use { tos ->
            var lastUpdate = System.currentTimeMillis()
            var lastBytes = 0L

            suspend fun addVfsNode(sourceUri: VfsUri, entryPath: String) {
                coroutineContext.ensureActive()
                checkPauseBarrier(taskId)

                val meta = vfsRouter.getMetadata(sourceUri)
                if (meta.isDirectory) {
                    val dirEntryPath = if (entryPath.endsWith("/")) entryPath else "$entryPath/"
                    val tarEntry = TarArchiveEntry(dirEntryPath)
                    tos.putArchiveEntry(tarEntry)
                    tos.closeArchiveEntry()

                    val children = vfsRouter.listDirectory(sourceUri)
                    for (child in children) {
                        addVfsNode(child.uri, "$dirEntryPath${child.name}")
                    }
                } else {
                    updateTask(taskId) { it.copy(currentFileName = sourceUri.name) }
                    val tarEntry = TarArchiveEntry(entryPath).apply {
                        size = meta.sizeBytes
                        modTime = java.util.Date(meta.lastModifiedMillis)
                    }
                    tos.putArchiveEntry(tarEntry)

                    vfsRouter.openInputStream(sourceUri).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            coroutineContext.ensureActive()
                            checkPauseBarrier(taskId)

                            tos.write(buffer, 0, read)

                            val currentTask = getTask(taskId) ?: break
                            val newProcessed = currentTask.processedBytes + read
                            val now = System.currentTimeMillis()
                            val dt = now - lastUpdate
                            val speed = if (dt >= 500) {
                                val sp = ((newProcessed - lastBytes) * 1000L) / dt
                                lastBytes = newProcessed
                                lastUpdate = now
                                sp
                            } else currentTask.speedBytesPerSec

                            updateTask(taskId) {
                                it.copy(processedBytes = newProcessed, speedBytesPerSec = speed)
                            }
                        }
                    }
                    tos.closeArchiveEntry()
                }
            }

            for (source in sources) {
                addVfsNode(source, source.name)
            }
        }
    }

    private suspend fun executeExtract(taskId: String) {
        val task = getTask(taskId) ?: return
        val archiveUri = task.sources.firstOrNull() ?: return
        val destDir = task.destinationDir ?: return

        val rootArchiveUri = if (ArchiveUri.isArchiveUri(archiveUri)) {
            archiveUri
        } else {
            ArchiveUri.build(archiveUri, "/")
        }

        // Recursively extract all nodes from archive
        suspend fun extractNode(currentUri: VfsUri, currentDest: VfsUri) {
            coroutineContext.ensureActive()
            checkPauseBarrier(taskId)

            val children = fetchArchiveChildrenWithAuthRetry(taskId, currentUri)
            for (child in children) {
                coroutineContext.ensureActive()
                checkPauseBarrier(taskId)

                val targetChild = currentDest.resolve(child.name)
                if (child.isDirectory) {
                    if (!vfsRouter.exists(targetChild)) {
                        vfsRouter.createDirectory(targetChild)
                    }
                    extractNode(child.uri, targetChild)
                } else {
                    updateTask(taskId) { it.copy(currentFileName = child.name) }
                    copyStreamWithSpeed(taskId, child.uri, targetChild)
                }
            }
        }

        extractNode(rootArchiveUri, destDir)
    }

    private suspend fun fetchArchiveChildrenWithAuthRetry(
        taskId: String,
        uri: VfsUri
    ): List<VfsNode> {
        while (true) {
            try {
                return vfsRouter.listDirectory(uri)
            } catch (e: VfsException.AuthenticationRequired) {
                val password = requestPasswordPrompt(taskId, uri, isWrong = archivePasswordStore.hasPassword(uri))
                if (password != null) {
                    archivePasswordStore.setPassword(uri, password)
                } else {
                    throw CancellationException("Extraction cancelled: password not provided")
                }
            }
        }
    }

    private suspend fun requestPasswordPrompt(
        taskId: String,
        archiveUri: VfsUri,
        isWrong: Boolean
    ): String? {
        updateTask(taskId) { it.copy(status = OperationStatus.WAITING_PASSWORD) }
        val deferred = CompletableDeferred<String?>()
        val prompt = ArchivePasswordPrompt(
            taskId = taskId,
            archiveUri = archiveUri,
            isWrongPassword = isWrong,
            response = deferred
        )
        _activePasswordPrompt.value = prompt
        val result = deferred.await()
        _activePasswordPrompt.value = null
        updateTask(taskId) { it.copy(status = OperationStatus.RUNNING) }
        return result
    }

    private suspend fun copyStreamWithSpeed(
        taskId: String,
        sourceUri: VfsUri,
        targetUri: VfsUri
    ) {
        var inputStream: InputStream? = null
        while (true) {
            try {
                inputStream = vfsRouter.openInputStream(sourceUri)
                break
            } catch (e: VfsException.AuthenticationRequired) {
                val pass = requestPasswordPrompt(taskId, sourceUri, isWrong = archivePasswordStore.hasPassword(sourceUri))
                if (pass != null) {
                    archivePasswordStore.setPassword(sourceUri, pass)
                } else {
                    throw CancellationException("File transfer cancelled: password required")
                }
            }
        }

        inputStream!!.use { input ->
            vfsRouter.openOutputStream(targetUri, append = false).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                var lastUpdate = System.currentTimeMillis()
                var lastBytes = 0L

                while (input.read(buffer).also { read = it } != -1) {
                    coroutineContext.ensureActive()
                    checkPauseBarrier(taskId)

                    output.write(buffer, 0, read)

                    val currentTask = getTask(taskId) ?: break
                    val newProcessed = currentTask.processedBytes + read
                    val now = System.currentTimeMillis()
                    val dt = now - lastUpdate
                    val speed = if (dt >= 500) {
                        val sp = ((newProcessed - lastBytes) * 1000L) / dt
                        lastBytes = newProcessed
                        lastUpdate = now
                        sp
                    } else currentTask.speedBytesPerSec

                    updateTask(taskId) {
                        it.copy(processedBytes = newProcessed, speedBytesPerSec = speed)
                    }
                }
                output.flush()
            }
        }
    }

    private suspend fun resolveTargetWithConflict(
        taskId: String,
        sourceUri: VfsUri,
        targetUri: VfsUri,
        isDirectory: Boolean,
        savedConflict: ConflictAction?,
        onDecision: (ConflictDecision) -> Unit
    ): Pair<VfsUri, Boolean> {
        if (!vfsRouter.exists(targetUri)) {
            return Pair(targetUri, false)
        }

        val decision = if (savedConflict != null) {
            ConflictDecision(savedConflict, applyToAll = true)
        } else {
            updateTask(taskId) { it.copy(status = OperationStatus.WAITING_CONFLICT) }
            val deferred = CompletableDeferred<ConflictDecision>()
            val prompt = FileConflictPrompt(
                taskId = taskId,
                sourceUri = sourceUri,
                targetUri = targetUri,
                isDirectory = isDirectory,
                response = deferred
            )
            _activeConflict.value = prompt
            val dec = deferred.await()
            _activeConflict.value = null
            updateTask(taskId) { it.copy(status = OperationStatus.RUNNING) }
            onDecision(dec)
            dec
        }

        return when (decision.action) {
            ConflictAction.SKIP -> Pair(targetUri, true)
            ConflictAction.OVERWRITE -> Pair(targetUri, false)
            ConflictAction.KEEP_BOTH -> Pair(generateUniqueUri(targetUri, isDirectory), false)
        }
    }

    private suspend fun generateUniqueUri(targetUri: VfsUri, isDirectory: Boolean): VfsUri {
        val parent = targetUri.parent ?: return targetUri
        val fullName = targetUri.name
        val dotIndex = if (isDirectory) -1 else fullName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) fullName.substring(0, dotIndex) else fullName
        val ext = if (dotIndex > 0) fullName.substring(dotIndex) else ""

        var counter = 1
        while (true) {
            val candidateName = "$baseName ($counter)$ext"
            val candidateUri = parent.resolve(candidateName)
            if (!vfsRouter.exists(candidateUri)) {
                return candidateUri
            }
            counter++
        }
    }

    private suspend fun calculateTotalBytes(uri: VfsUri): Long {
        return runCatching {
            val meta = vfsRouter.getMetadata(uri)
            if (meta.isDirectory) {
                var sum = 0L
                for (child in vfsRouter.listDirectory(uri)) {
                    sum += calculateTotalBytes(child.uri)
                }
                sum
            } else {
                meta.sizeBytes
            }
        }.getOrDefault(0L)
    }

    private suspend fun checkPauseBarrier(taskId: String) {
        while (pausedTaskIds.contains(taskId)) {
            coroutineContext.ensureActive()
            delay(100)
        }
    }

    private fun getTask(taskId: String): FileOperationTask? {
        return _tasks.value.firstOrNull { it.id == taskId }
    }

    private fun updateTask(taskId: String, transform: (FileOperationTask) -> FileOperationTask) {
        _tasks.update { list ->
            list.map { if (it.id == taskId) transform(it) else it }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB
    }
}
