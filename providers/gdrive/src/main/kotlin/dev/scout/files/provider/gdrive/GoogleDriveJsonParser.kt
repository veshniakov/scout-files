package dev.scout.files.provider.gdrive

import java.time.Instant

object GoogleDriveJsonParser {

    sealed class JsonElement {
        data class JsonObject(val fields: Map<String, JsonElement>) : JsonElement() {
            fun getString(key: String): String? = when (val el = fields[key]) {
                is JsonString -> el.value
                is JsonNumber -> el.value
                else -> null
            }

            fun getLong(key: String): Long? = when (val el = fields[key]) {
                is JsonNumber -> el.value.toLongOrNull()
                is JsonString -> el.value.toLongOrNull()
                else -> null
            }

            fun getBoolean(key: String): Boolean? = when (val el = fields[key]) {
                is JsonBoolean -> el.value
                is JsonString -> el.value.toBooleanStrictOrNull()
                else -> null
            }

            fun getObject(key: String): JsonObject? = fields[key] as? JsonObject

            fun getArray(key: String): JsonArray? = fields[key] as? JsonArray
        }

        data class JsonArray(val elements: List<JsonElement>) : JsonElement()
        data class JsonString(val value: String) : JsonElement()
        data class JsonNumber(val value: String) : JsonElement()
        data class JsonBoolean(val value: Boolean) : JsonElement()
        object JsonNull : JsonElement()
    }

    private class Parser(private val src: String) {
        private var idx = 0

        private fun skipWhitespace() {
            while (idx < src.length && src[idx].isWhitespace()) {
                idx++
            }
        }

        private fun peek(): Char? = if (idx < src.length) src[idx] else null

        fun parseElement(): JsonElement {
            skipWhitespace()
            return when (val ch = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                null -> JsonElement.JsonNull
                else -> {
                    if (ch == '-' || ch.isDigit()) {
                        parseNumber()
                    } else {
                        throw IllegalArgumentException("Unexpected character at $idx: $ch in $src")
                    }
                }
            }
        }

        private fun parseObject(): JsonElement.JsonObject {
            idx++ // consume '{'
            val map = mutableMapOf<String, JsonElement>()
            skipWhitespace()
            if (peek() == '}') {
                idx++
                return JsonElement.JsonObject(map)
            }
            while (idx < src.length) {
                skipWhitespace()
                if (peek() != '"') throw IllegalArgumentException("Expected string key at $idx in $src")
                val key = parseString().value
                skipWhitespace()
                if (peek() != ':') throw IllegalArgumentException("Expected ':' at $idx in $src")
                idx++ // consume ':'
                val value = parseElement()
                map[key] = value
                skipWhitespace()
                when (val next = peek()) {
                    ',' -> idx++
                    '}' -> {
                        idx++
                        return JsonElement.JsonObject(map)
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $idx, got $next in $src")
                }
            }
            throw IllegalArgumentException("Unterminated object in $src")
        }

        private fun parseArray(): JsonElement.JsonArray {
            idx++ // consume '['
            val list = mutableListOf<JsonElement>()
            skipWhitespace()
            if (peek() == ']') {
                idx++
                return JsonElement.JsonArray(list)
            }
            while (idx < src.length) {
                list.add(parseElement())
                skipWhitespace()
                when (val next = peek()) {
                    ',' -> idx++
                    ']' -> {
                        idx++
                        return JsonElement.JsonArray(list)
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $idx, got $next in $src")
                }
            }
            throw IllegalArgumentException("Unterminated array in $src")
        }

        private fun parseString(): JsonElement.JsonString {
            idx++ // consume '"'
            val sb = StringBuilder()
            while (idx < src.length) {
                val c = src[idx++]
                if (c == '"') {
                    return JsonElement.JsonString(sb.toString())
                }
                if (c == '\\') {
                    if (idx >= src.length) throw IllegalArgumentException("Invalid escape at end of $src")
                    when (val esc = src[idx++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (idx + 4 > src.length) throw IllegalArgumentException("Invalid unicode escape in $src")
                            val hex = src.substring(idx, idx + 4)
                            idx += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string in $src")
        }

        private fun parseNumber(): JsonElement.JsonNumber {
            val start = idx
            if (peek() == '-') idx++
            while (idx < src.length && src[idx].isDigit()) idx++
            if (idx < src.length && src[idx] == '.') {
                idx++
                while (idx < src.length && src[idx].isDigit()) idx++
            }
            if (idx < src.length && (src[idx] == 'e' || src[idx] == 'E')) {
                idx++
                if (idx < src.length && (src[idx] == '+' || src[idx] == '-')) idx++
                while (idx < src.length && src[idx].isDigit()) idx++
            }
            return JsonElement.JsonNumber(src.substring(start, idx))
        }

        private fun parseBoolean(): JsonElement.JsonBoolean {
            return if (src.startsWith("true", idx)) {
                idx += 4
                JsonElement.JsonBoolean(true)
            } else if (src.startsWith("false", idx)) {
                idx += 5
                JsonElement.JsonBoolean(false)
            } else {
                throw IllegalArgumentException("Expected boolean at $idx in $src")
            }
        }

        private fun parseNull(): JsonElement.JsonNull {
            if (src.startsWith("null", idx)) {
                idx += 4
                return JsonElement.JsonNull
            }
            throw IllegalArgumentException("Expected null at $idx in $src")
        }
    }

    fun parse(json: String): JsonElement {
        return Parser(json.trim()).parseElement()
    }

    fun parseFileList(json: String): List<DriveFileItem> {
        if (json.isBlank()) return emptyList()
        val element = runCatching { parse(json) }.getOrNull() ?: return emptyList()
        val filesArray = when (element) {
            is JsonElement.JsonObject -> element.getArray("files")
            is JsonElement.JsonArray -> element
            else -> null
        } ?: return emptyList()

        return filesArray.elements.mapNotNull { el ->
            (el as? JsonElement.JsonObject)?.let { mapToFileItem(it) }
        }
    }

    fun parseFileItem(json: String): DriveFileItem {
        val element = parse(json)
        val obj = element as? JsonElement.JsonObject
            ?: throw IllegalArgumentException("Expected JSON object, got: $json")
        return mapToFileItem(obj)
    }

    private fun mapToFileItem(obj: JsonElement.JsonObject): DriveFileItem {
        val id = obj.getString("id") ?: ""
        val name = obj.getString("name") ?: ""
        val mimeType = obj.getString("mimeType") ?: ""
        val size = obj.getLong("size") ?: 0L
        val modifiedTimeStr = obj.getString("modifiedTime")
        val modifiedTimeMillis = modifiedTimeStr?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        } ?: 0L
        val parents = obj.getArray("parents")?.elements?.mapNotNull { (it as? JsonElement.JsonString)?.value }
            ?: emptyList()
        val trashed = obj.getBoolean("trashed") ?: false
        return DriveFileItem(
            id = id,
            name = name,
            mimeType = mimeType,
            sizeBytes = size,
            modifiedTimeMillis = modifiedTimeMillis,
            parents = parents,
            trashed = trashed
        )
    }

    fun parseAccessToken(json: String): String? {
        val element = runCatching { parse(json) }.getOrNull() as? JsonElement.JsonObject
            ?: return null
        return element.getString("access_token")
    }

    fun parseErrorResponse(json: String): DriveErrorResponse? {
        val root = runCatching { parse(json) }.getOrNull() as? JsonElement.JsonObject
            ?: return null

        val errorObj = root.getObject("error")
        if (errorObj != null) {
            val code = errorObj.getLong("code")?.toInt() ?: 0
            val message = errorObj.getString("message") ?: ""
            val firstError = errorObj.getArray("errors")?.elements?.firstOrNull() as? JsonElement.JsonObject
            val reason = firstError?.getString("reason")
            return DriveErrorResponse(code = code, message = message, reason = reason)
        }

        val errorStr = root.getString("error")
        if (errorStr != null) {
            val desc = root.getString("error_description") ?: errorStr
            return DriveErrorResponse(code = 400, message = desc, reason = errorStr)
        }

        return null
    }

    fun createFolderJson(name: String, parentId: String): String {
        return """{"name":"${escapeJson(name)}","mimeType":"application/vnd.google-apps.folder","parents":["${escapeJson(parentId)}"]}"""
    }

    fun createFileMetadataJson(name: String, parentId: String): String {
        return """{"name":"${escapeJson(name)}","parents":["${escapeJson(parentId)}"]}"""
    }

    fun createRenameJson(newName: String): String {
        return """{"name":"${escapeJson(newName)}"}"""
    }

    fun escapeDriveQuery(value: String): String {
        return value.replace("\\", "\\\\").replace("'", "\\'")
    }

    fun escapeJson(value: String): String {
        val sb = StringBuilder()
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }
}
