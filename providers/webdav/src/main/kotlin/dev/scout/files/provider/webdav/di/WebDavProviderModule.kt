package dev.scout.files.provider.webdav.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.scout.files.core.vfs.FileSystemProvider
import dev.scout.files.provider.webdav.S3FileSystemProvider
import dev.scout.files.provider.webdav.WebDavFileSystemProvider
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WebDavProviderModule {

    @Binds
    @IntoSet
    abstract fun bindWebDavFileSystemProvider(
        provider: WebDavFileSystemProvider
    ): FileSystemProvider

    @Binds
    @IntoSet
    abstract fun bindS3FileSystemProvider(
        provider: S3FileSystemProvider
    ): FileSystemProvider

    companion object {
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()
    }
}
