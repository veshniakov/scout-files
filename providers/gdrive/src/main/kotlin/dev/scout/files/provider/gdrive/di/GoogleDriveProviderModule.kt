package dev.scout.files.provider.gdrive.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.scout.files.core.vfs.FileSystemProvider
import dev.scout.files.provider.gdrive.GoogleDriveFileSystemProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class GoogleDriveProviderModule {

    @Binds
    @IntoSet
    abstract fun bindGoogleDriveFileSystemProvider(
        provider: GoogleDriveFileSystemProvider
    ): FileSystemProvider
}
