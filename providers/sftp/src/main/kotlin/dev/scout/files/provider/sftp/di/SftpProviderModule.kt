package dev.scout.files.provider.sftp.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.scout.files.core.vfs.FileSystemProvider
import dev.scout.files.provider.sftp.SftpFileSystemProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class SftpProviderModule {

    @Binds
    @IntoSet
    abstract fun bindSftpFileSystemProvider(
        provider: SftpFileSystemProvider
    ): FileSystemProvider
}
