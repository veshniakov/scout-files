package dev.scout.files.provider.archive.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.scout.files.core.vfs.FileSystemProvider
import dev.scout.files.provider.archive.ArchiveFileSystemProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class ArchiveModule {

    @Binds
    @IntoSet
    abstract fun bindArchiveFileSystemProvider(
        provider: ArchiveFileSystemProvider
    ): FileSystemProvider
}
