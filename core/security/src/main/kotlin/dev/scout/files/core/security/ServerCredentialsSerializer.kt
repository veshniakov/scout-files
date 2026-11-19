package dev.scout.files.core.security

import dev.scout.files.core.model.ServerCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal object ServerCredentialsSerializer {
    private const val MAGIC_VERSION = 1

    fun serialize(credentials: ServerCredentials): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(MAGIC_VERSION)
            writeString(out, credentials.password)
            writeString(out, credentials.privateKeyPem)
            writeString(out, credentials.privateKeyPassphrase)
            writeString(out, credentials.oauthAccessToken)
            writeString(out, credentials.oauthRefreshToken)
            writeString(out, credentials.oauthClientId)
            writeString(out, credentials.s3SecretKey)
        }
        return baos.toByteArray()
    }

    fun deserialize(bytes: ByteArray): ServerCredentials {
        val bais = ByteArrayInputStream(bytes)
        DataInputStream(bais).use { inStream ->
            val version = inStream.readInt()
            if (version != MAGIC_VERSION) {
                throw IllegalArgumentException("Unsupported credentials version: $version")
            }
            return ServerCredentials(
                password = readString(inStream),
                privateKeyPem = readString(inStream),
                privateKeyPassphrase = readString(inStream),
                oauthAccessToken = readString(inStream),
                oauthRefreshToken = readString(inStream),
                oauthClientId = readString(inStream),
                s3SecretKey = readString(inStream)
            )
        }
    }

    private fun writeString(out: DataOutputStream, value: String?) {
        if (value == null) {
            out.writeInt(-1)
        } else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
    }

    private fun readString(inStream: DataInputStream): String? {
        val length = inStream.readInt()
        return if (length == -1) {
            null
        } else {
            val bytes = ByteArray(length)
            inStream.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }
}
