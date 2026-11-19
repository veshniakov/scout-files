package dev.scout.files.provider.smb.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.scout.files.core.vfs.FileSystemProvider
import dev.scout.files.provider.smb.SmbFileSystemProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class SmbProviderModule {

    @Binds
    @IntoSet
    abstract fun bindSmbFileSystemProvider(
        provider: SmbFileSystemProvider
    ): FileSystemProvider
}
