package dev.scout.files.core.vfs.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.scout.files.core.vfs.FileSystemProvider
import dev.scout.files.core.vfs.provider.LocalFileSystemProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class VfsModule {

    @Binds
    @IntoSet
    abstract fun bindLocalFileSystemProvider(
        provider: LocalFileSystemProvider
    ): FileSystemProvider
}
