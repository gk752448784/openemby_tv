package com.xxxx.emby_tv.data.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

// Search results and download links live only in memory; ASSRT links expire.
data class AssrtSearchResult(val id: String, val name: String, val language: String, val format: String)
data class AssrtFile(val name: String, val url: String)
data class LocalSubtitle(val path: String, val name: String, val codec: String)

object AssrtSubtitles {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val LOCAL_INDEX = -2

    private fun response(json: String): JsonObject {
        val root = JsonParser.parseString(json).asJsonObject
        val status = root.get("status")?.asInt ?: error("ASSRT: invalid response")
        check(status == 0) { "ASSRT: error $status" }
        return root.getAsJsonObject("sub") ?: error("ASSRT: missing subtitle data")
    }

    private fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    fun parseSearch(json: String): List<AssrtSearchResult> =
        response(json).getAsJsonArray("subs")?.mapNotNull { value ->
            val item = value.asJsonObject
            val id = item.text("id")
            if (id.isBlank()) null else AssrtSearchResult(id,
                item.text("native_name").ifBlank { item.text("videoname") },
                item.getAsJsonObject("lang")?.text("desc").orEmpty(), item.text("subtype"))
        } ?: emptyList()

    fun parseFiles(json: String): List<AssrtFile> =
        response(json).getAsJsonArray("subs")?.flatMap { value ->
            val item = value.asJsonObject
            val files = item.getAsJsonArray("filelist")?.map { it.asJsonObject }
            val candidates = if (files.isNullOrEmpty()) listOf(AssrtFile(item.text("filename"), item.text("url")))
                else files.map { AssrtFile(it.text("f"), it.text("url")) }
            candidates.filter { codec(it.name) != null && (it.url.startsWith("https://") || it.url.startsWith("http://")) }
        } ?: emptyList()

    fun codec(name: String): String? = name.substringAfterLast('.', "").lowercase()
        .takeIf { it in setOf("srt", "ass", "ssa", "vtt") }

    fun normalize(bytes: ByteArray, codec: String): ByteArray {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "ASSRT: subtitle too large or empty" }
        val text = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { bytes.toString(charset("GB18030")) }.removePrefix("\uFEFF")
        val valid = when (codec) {
            "srt", "vtt" -> text.contains("-->")
            "ass", "ssa" -> text.contains("[Events]", true) && text.contains("Dialogue:", true)
            else -> false
        }
        require(valid && !text.trimStart().startsWith("<")) { "ASSRT: invalid subtitle file" }
        return text.toByteArray(Charsets.UTF_8)
    }
}
