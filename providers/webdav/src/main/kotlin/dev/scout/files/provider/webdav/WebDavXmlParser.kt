package dev.scout.files.provider.webdav

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.net.URLDecoder
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

data class WebDavResource(
    val href: String,
    val displayName: String?,
    val isDirectory: Boolean,
    val contentLength: Long,
    val lastModifiedMillis: Long
)

object WebDavXmlParser {

    fun parseMultistatus(inputStream: InputStream): List<WebDavResource> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(inputStream)
        val responseNodes = document.getElementsByTagNameNS("*", "response")

        val resources = mutableListOf<WebDavResource>()

        for (i in 0 until responseNodes.length) {
            val responseElem = responseNodes.item(i) as? Element ?: continue
            val resource = parseResponseElement(responseElem)
            if (resource != null) {
                resources.add(resource)
            }
        }

        return resources
    }

    private fun parseResponseElement(response: Element): WebDavResource? {
        val hrefElem = getFirstElementByTagName(response, "href") ?: return null
        val rawHref = hrefElem.textContent.trim()
        val href = try {
            URLDecoder.decode(rawHref, "UTF-8")
        } catch (_: Exception) {
            rawHref
        }

        var displayName: String? = null
        var isDirectory = false
        var contentLength = 0L
        var lastModifiedMillis = System.currentTimeMillis()

        val propElems = response.getElementsByTagNameNS("*", "prop")
        for (p in 0 until propElems.length) {
            val prop = propElems.item(p) as? Element ?: continue

            val dn = getFirstElementByTagName(prop, "displayname")
            if (dn != null && dn.textContent.isNotBlank()) {
                displayName = dn.textContent.trim()
            }

            val rt = getFirstElementByTagName(prop, "resourcetype")
            if (rt != null) {
                val collection = getFirstElementByTagName(rt, "collection")
                if (collection != null) {
                    isDirectory = true
                }
            }

            val cl = getFirstElementByTagName(prop, "getcontentlength")
            if (cl != null) {
                contentLength = cl.textContent.trim().toLongOrNull() ?: 0L
            }

            val lm = getFirstElementByTagName(prop, "getlastmodified")
            if (lm != null) {
                lastModifiedMillis = parseWebDavDate(lm.textContent.trim())
            }
        }

        // If displayName is null, deduce from href
        val derivedName = displayName ?: href.trimEnd('/').substringAfterLast('/')

        return WebDavResource(
            href = href,
            displayName = derivedName,
            isDirectory = isDirectory,
            contentLength = contentLength,
            lastModifiedMillis = lastModifiedMillis
        )
    }

    private fun getFirstElementByTagName(parent: Element, localName: String): Element? {
        val list = parent.getElementsByTagNameNS("*", localName)
        return if (list.length > 0) list.item(0) as? Element else null
    }

    private fun parseWebDavDate(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis()
        return try {
            Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(dateStr)).toEpochMilli()
        } catch (_: Exception) {
            try {
                Instant.parse(dateStr).toEpochMilli()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        }
    }
}
