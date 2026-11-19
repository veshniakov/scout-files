package dev.scout.files.provider.sftp

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

object BouncyCastleInitializer {
    private var initialized = false

    @Synchronized
    fun init() {
        if (!initialized) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            initialized = true
        }
    }
}
