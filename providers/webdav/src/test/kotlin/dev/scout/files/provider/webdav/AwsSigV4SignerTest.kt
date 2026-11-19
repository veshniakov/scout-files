package dev.scout.files.provider.webdav

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AwsSigV4SignerTest {

    @Test
    fun sha256Hex_calculatesCorrectDigest() {
        val hash = AwsSigV4Signer.sha256Hex("Hello World")
        assertEquals("a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e", hash)
    }

    @Test
    fun sha256Hex_emptyString_matchesKnownConstant() {
        val hash = AwsSigV4Signer.sha256Hex("")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun sign_generatesExpectedHeadersAndFormat() {
        val fixedInstant = Instant.parse("2026-11-18T23:33:19Z")
        val request = Request.Builder()
            .url("https://s3.us-east-1.amazonaws.com/mybucket/photos/cat.jpg")
            .get()
            .build()

        val signed = AwsSigV4Signer.sign(
            request = request,
            accessKey = "AKIAEXAMPLEKEY",
            secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            region = "us-east-1",
            service = "s3",
            now = fixedInstant
        )

        assertEquals("20261118T233319Z", signed.header("x-amz-date"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", signed.header("x-amz-content-sha256"))
        assertEquals("s3.us-east-1.amazonaws.com", signed.header("Host"))

        val auth = signed.header("Authorization")
        assertNotNull(auth)
        assertTrue(auth!!.startsWith("AWS4-HMAC-SHA256 Credential=AKIAEXAMPLEKEY/20261118/us-east-1/s3/aws4_request"))
        assertTrue(auth.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
        assertTrue(auth.contains("Signature="))
    }

    @Test
    fun sign_withRequestBody_calculatesBodySha256() {
        val fixedInstant = Instant.parse("2026-11-18T23:33:19Z")
        val body = "Test payload data".toRequestBody("text/plain".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("https://s3.us-east-1.amazonaws.com/mybucket/test.txt")
            .put(body)
            .build()

        val signed = AwsSigV4Signer.sign(
            request = request,
            accessKey = "AKIAEXAMPLEKEY",
            secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            region = "us-east-1",
            service = "s3",
            now = fixedInstant
        )

        val expectedSha = AwsSigV4Signer.sha256Hex("Test payload data")
        assertEquals(expectedSha, signed.header("x-amz-content-sha256"))
    }
}
