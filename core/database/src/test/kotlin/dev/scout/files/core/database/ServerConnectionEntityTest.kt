package dev.scout.files.core.database

import dev.scout.files.core.database.entity.ServerConnectionEntity
import dev.scout.files.core.database.entity.toDomain
import dev.scout.files.core.database.entity.toEntity
import dev.scout.files.core.model.AuthType
import dev.scout.files.core.model.NetworkProtocol
import dev.scout.files.core.model.ServerConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConnectionEntityTest {

    @Test
    fun testBidirectionalMapping() {
        val domain = ServerConnection(
            id = "test-id-1",
            name = "Test Connection",
            protocol = NetworkProtocol.SFTP,
            host = "10.0.0.1",
            port = 2222,
            username = "admin",
            authType = AuthType.SSH_KEY,
            shareOrBucket = "bucket1",
            domainOrRegion = "us-west-2",
            rootPath = "/home/admin",
            useTls = true,
            requireBiometric = true,
            lastConnectedMillis = 123456789L
        )

        val entity = domain.toEntity()
        assertEquals("test-id-1", entity.id)
        assertEquals("Test Connection", entity.name)
        assertEquals("SFTP", entity.protocol)
        assertEquals("10.0.0.1", entity.host)
        assertEquals(2222, entity.port)
        assertEquals("admin", entity.username)
        assertEquals("SSH_KEY", entity.authType)
        assertEquals("bucket1", entity.shareOrBucket)
        assertEquals("us-west-2", entity.domainOrRegion)
        assertEquals("/home/admin", entity.rootPath)
        assertTrue(entity.useTls)
        assertTrue(entity.requireBiometric)
        assertEquals(123456789L, entity.lastConnectedMillis)

        val mappedBack = entity.toDomain()
        assertEquals(domain, mappedBack)
    }

    @Test
    fun testMappingWithNullsAndFallbacks() {
        val entity = ServerConnectionEntity(
            id = "test-id-2",
            name = "Fallback Connection",
            protocol = "INVALID_PROTOCOL",
            host = "localhost",
            port = 80,
            username = "",
            authType = "UNKNOWN_AUTH",
            shareOrBucket = null,
            domainOrRegion = null,
            rootPath = "/",
            useTls = false,
            requireBiometric = false,
            lastConnectedMillis = null
        )

        val domain = entity.toDomain()
        assertEquals(NetworkProtocol.SFTP, domain.protocol) // Fallback default
        assertEquals(AuthType.PASSWORD, domain.authType) // Fallback default
        assertNull(domain.shareOrBucket)
        assertNull(domain.domainOrRegion)
        assertNull(domain.lastConnectedMillis)
        assertFalse(domain.useTls)
        assertFalse(domain.requireBiometric)
    }
}
