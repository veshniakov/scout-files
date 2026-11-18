package dev.scout.files.provider.local.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.scout.files.core.vfs.FileSystemProvider
import dev.scout.files.provider.local.LocalFileSystemProvider
import dev.scout.files.provider.local.SafFileSystemProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalProviderModule {

    @Binds
    @IntoSet
    abstract fun bindLocalFileSystemProvider(
        provider: LocalFileSystemProvider
    ): FileSystemProvider

    @Binds
    @IntoSet
    abstract fun bindSafFileSystemProvider(
        provider: SafFileSystemProvider
    ): FileSystemProvider
}
