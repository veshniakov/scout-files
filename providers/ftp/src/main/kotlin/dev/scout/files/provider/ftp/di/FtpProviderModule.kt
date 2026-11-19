package dev.scout.files.provider.ftp.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.scout.files.core.vfs.FileSystemProvider
import dev.scout.files.provider.ftp.DefaultFtpClientFactory
import dev.scout.files.provider.ftp.FtpClientFactory
import dev.scout.files.provider.ftp.FtpFileSystemProvider
import dev.scout.files.provider.ftp.FtpsFileSystemProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FtpProviderModule {

    @Binds
    @Singleton
    abstract fun bindFtpClientFactory(
        factory: DefaultFtpClientFactory
    ): FtpClientFactory

    @Binds
    @IntoSet
    abstract fun bindFtpFileSystemProvider(
        provider: FtpFileSystemProvider
    ): FileSystemProvider

    @Binds
    @IntoSet
    abstract fun bindFtpsFileSystemProvider(
        provider: FtpsFileSystemProvider
    ): FileSystemProvider
}
