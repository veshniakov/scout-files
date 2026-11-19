package dev.scout.files.provider.ftp

import dev.scout.files.core.database.repository.MetadataRepository
import dev.scout.files.core.security.CredentialVault
import dev.scout.files.core.vfs.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FtpFileSystemProvider @Inject constructor(
    metadataRepository: MetadataRepository,
    credentialVault: CredentialVault,
    clientFactory: FtpClientFactory,
    @IoDispatcher ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : BaseFtpFileSystemProvider(metadataRepository, credentialVault, clientFactory, ioDispatcher) {

    override val scheme: String = "ftp"
}
