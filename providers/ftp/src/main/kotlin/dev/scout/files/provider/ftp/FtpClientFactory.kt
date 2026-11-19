package dev.scout.files.provider.ftp

import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import javax.inject.Inject
import javax.inject.Singleton

interface FtpClientFactory {
    fun createFtpClient(): FTPClient
    fun createFtpsClient(isImplicit: Boolean): FTPSClient
}

@Singleton
class DefaultFtpClientFactory @Inject constructor() : FtpClientFactory {
    override fun createFtpClient(): FTPClient = FTPClient()

    override fun createFtpsClient(isImplicit: Boolean): FTPSClient = FTPSClient("TLS", isImplicit)
}
