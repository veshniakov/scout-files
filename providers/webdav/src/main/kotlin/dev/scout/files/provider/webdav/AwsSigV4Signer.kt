package dev.scout.files.provider.webdav

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AwsSigV4Signer {

    private val DATE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).withZone(ZoneOffset.UTC)

    private val DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US).withZone(ZoneOffset.UTC)

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun urlEncode(value: String, path: Boolean = false): String {
        val encoded = URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
        return if (path) encoded.replace("%2F", "/") else encoded
    }

    fun sign(
        request: Request,
        accessKey: String,
        secretKey: String,
        region: String,
        service: String = "s3",
        now: Instant = Instant.now()
    ): Request {
        val dateTime = DATE_TIME_FORMATTER.format(now)
        val dateStamp = DATE_FORMATTER.format(now)

        // 1. Calculate payload hash
        val payloadHash = computePayloadHash(request.body)

        // 2. Build headers to sign
        val url = request.url
        val hostHeader = if ((url.scheme == "https" && url.port == 443) || (url.scheme == "http" && url.port == 80)) {
            url.host
        } else {
            "${url.host}:${url.port}"
        }

        val headersToSign = mutableMapOf<String, String>()
        headersToSign["host"] = hostHeader
        headersToSign["x-amz-date"] = dateTime
        headersToSign["x-amz-content-sha256"] = payloadHash

        for (i in 0 until request.headers.size) {
            val name = request.headers.name(i).lowercase(Locale.US)
            val value = request.headers.value(i).trim()
            if (name == "host" || name.startsWith("x-amz-") || name == "content-type" || name == "range") {
                headersToSign[name] = value
            }
        }

        val sortedHeaderNames = headersToSign.keys.sorted()
        val signedHeaders = sortedHeaderNames.joinToString(";")
        val canonicalHeaders = sortedHeaderNames.joinToString("") { name ->
            val value = headersToSign[name]!!.replace(Regex("\\s+"), " ")
            "$name:$value\n"
        }

        // 3. Canonical URI & Query
        val canonicalUri = buildCanonicalUri(url)
        val canonicalQuery = buildCanonicalQuery(url)

        // 4. Canonical Request
        val canonicalRequest = listOf(
            request.method,
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ).joinToString("\n")

        val hashedCanonicalRequest = sha256Hex(canonicalRequest)

        // 5. String to Sign
        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            dateTime,
            scope,
            hashedCanonicalRequest
        ).joinToString("\n")

        // 6. Signing Key
        val kSecret = ("AWS4$secretKey").toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        val kSigning = hmacSha256(kService, "aws4_request")

        // 7. Signature
        val signature = hex(hmacSha256(kSigning, stringToSign))

        // 8. Authorization Header
        val authorization = "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"

        // 9. Return signed Request
        val newHeaders = request.headers.newBuilder()
            .set("Host", hostHeader)
            .set("x-amz-date", dateTime)
            .set("x-amz-content-sha256", payloadHash)
            .set("Authorization", authorization)
            .build()

        return request.newBuilder()
            .headers(newHeaders)
            .build()
    }

    private fun computePayloadHash(body: RequestBody?): String {
        if (body == null) {
            return "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" // SHA-256 of empty string
        }
        val buffer = Buffer()
        body.writeTo(buffer)
        return sha256Hex(buffer.readByteArray())
    }

    private fun buildCanonicalUri(url: HttpUrl): String {
        val path = url.encodedPath
        return if (path.isEmpty()) "/" else path
    }

    private fun buildCanonicalQuery(url: HttpUrl): String {
        if (url.querySize == 0) return ""
        val params = mutableListOf<Pair<String, String>>()
        for (i in 0 until url.querySize) {
            val name = url.queryParameterName(i)
            val value = url.queryParameterValue(i) ?: ""
            params.add(urlEncode(name) to urlEncode(value))
        }
        params.sortWith(compareBy({ it.first }, { it.second }))
        return params.joinToString("&") { "${it.first}=${it.second}" }
    }
}
