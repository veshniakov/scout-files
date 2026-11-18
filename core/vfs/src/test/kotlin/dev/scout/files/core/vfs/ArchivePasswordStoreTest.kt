package dev.scout.files.core.vfs

import dev.scout.files.core.model.ArchiveUri
import dev.scout.files.core.model.VfsUri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivePasswordStoreTest {

    @Test
    fun testStoreAndRetrievePassword() {
        val store = ArchivePasswordStore()
        val fileUri = VfsUri("file:///storage/emulated/0/Download/secret.zip")
        val archiveUri = ArchiveUri.build(fileUri, "inner/file.txt")

        assertFalse(store.hasPassword(fileUri))
        assertNull(store.getPassword(fileUri))

        store.setPassword(fileUri, "MyPassword123")
        assertTrue(store.hasPassword(fileUri))
        assertTrue(store.hasPassword(archiveUri)) // Should match normalized source URI
        assertEquals("MyPassword123", store.getPasswordString(archiveUri))
        assertArrayEquals("MyPassword123".toCharArray(), store.getPassword(fileUri))

        store.removePassword(archiveUri)
        assertFalse(store.hasPassword(fileUri))
        assertNull(store.getPassword(fileUri))

        store.setPassword(archiveUri, "NewPass")
        assertTrue(store.hasPassword(fileUri))
        store.clear()
        assertFalse(store.hasPassword(fileUri))
    }
}
