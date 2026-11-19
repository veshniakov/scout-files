package dev.scout.files.core.security.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.scout.files.core.security.CredentialVault
import dev.scout.files.core.security.KeystoreCredentialVault
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindCredentialVault(vault: KeystoreCredentialVault): CredentialVault
}
