package dev.scout.files.provider.webdav

import org.w3c.dom.Element
import java.io.InputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

data class S3Object(
    val key: String,
    val size: Long,
    val lastModifiedMillis: Long
)

data class S3ListResult(
    val bucketName: String,
    val prefix: String,
    val isTruncated: Boolean,
    val nextContinuationToken: String?,
    val commonPrefixes: List<String>,
    val contents: List<S3Object>
)

object S3XmlParser {

    fun parseListBucketResult(inputStream: InputStream): S3ListResult {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(inputStream)
        val root = document.documentElement

        val bucketName = getElementText(root, "Name") ?: ""
        val prefix = getElementText(root, "Prefix") ?: ""
        val isTruncated = getElementText(root, "IsTruncated")?.toBoolean() ?: false
        val nextContinuationToken = getElementText(root, "NextContinuationToken")

        val commonPrefixes = mutableListOf<String>()
        val commonPrefixNodes = root.getElementsByTagNameNS("*", "CommonPrefixes")
        for (i in 0 until commonPrefixNodes.length) {
            val elem = commonPrefixNodes.item(i) as? Element ?: continue
            val p = getElementText(elem, "Prefix")
            if (p != null) {
                commonPrefixes.add(p)
            }
        }

        val contents = mutableListOf<S3Object>()
        val contentNodes = root.getElementsByTagNameNS("*", "Contents")
        for (i in 0 until contentNodes.length) {
            val elem = contentNodes.item(i) as? Element ?: continue
            val key = getElementText(elem, "Key") ?: continue
            val size = getElementText(elem, "Size")?.toLongOrNull() ?: 0L
            val lastModifiedStr = getElementText(elem, "LastModified") ?: ""
            val lastModifiedMillis = try {
                Instant.parse(lastModifiedStr).toEpochMilli()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }

            contents.add(S3Object(key = key, size = size, lastModifiedMillis = lastModifiedMillis))
        }

        return S3ListResult(
            bucketName = bucketName,
            prefix = prefix,
            isTruncated = isTruncated,
            nextContinuationToken = nextContinuationToken,
            commonPrefixes = commonPrefixes,
            contents = contents
        )
    }

    private fun getElementText(parent: Element, localName: String): String? {
        val list = parent.getElementsByTagNameNS("*", localName)
        return if (list.length > 0) list.item(0)?.textContent?.trim() else null
    }
}
