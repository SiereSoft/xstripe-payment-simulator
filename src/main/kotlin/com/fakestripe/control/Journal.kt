package com.fakestripe.control

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** One journal entry (protocol section 6). Bodies are kept redacted and appear only on request. */
@Serializable
data class JournalEntry(
    val seq: Long = 0,
    @SerialName("episode_id") val episodeId: String? = null,
    val at: String,
    val wall: String,
    val kind: String,
    val operation: String? = null,
    val method: String? = null,
    val path: String? = null,
    val status: Int? = null,
    val outcome: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("correlation_id") val correlationId: String? = null,
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    val refs: Map<String, String> = emptyMap(),
    val fault: String? = null,
    val detail: JsonElement? = null,
    @SerialName("request_body") val requestBody: JsonElement? = null,
    @SerialName("response_body") val responseBody: JsonElement? = null,
) {
    fun toJson(bodies: Boolean): JsonObject = buildJsonObject {
        put("seq", seq)
        put("episode_id", episodeId)
        put("at", at)
        put("wall", wall)
        put("kind", kind)
        put("operation", operation)
        put("method", method)
        put("path", path)
        put("status", status)
        put("outcome", outcome)
        put("request_id", requestId)
        put("correlation_id", correlationId)
        put("idempotency_key", idempotencyKey)
        put("refs", buildJsonObject { refs.forEach { (k, v) -> put(k, v) } })
        put("fault", fault)
        put("detail", detail ?: JsonNull)
        if (bodies) {
            put("request_body", requestBody ?: JsonNull)
            put("response_body", responseBody ?: JsonNull)
        }
    }

    companion object {
        const val REQUEST = "request"
        const val CALLBACK = "callback"
        const val STATE = "state"
        const val FAULT = "fault"
        const val CLOCK = "clock"
        const val CONTROL = "control"
        val KINDS = setOf(REQUEST, CALLBACK, STATE, FAULT, CLOCK, CONTROL)
    }
}

/**
 * The event journal: what crossed the simulator's boundaries, numbered by a sequence that never
 * restarts (not on reset, not on restore, not on restart).
 *
 * Entries live in memory (the last [capacity]) and in an append-only JSON-lines file beside the
 * world's state file, which is compacted to the retained window when it grows past twice that.
 * The journal is deliberately not part of snapshots; reset empties it without renumbering.
 */
class Journal(private val file: Path?, private val capacity: Int = DEFAULT_CAPACITY) {
    private val log = LoggerFactory.getLogger(Journal::class.java)
    private val entries = ArrayDeque<JournalEntry>()
    private var linesInFile = 0

    /** The last sequence number handed out (0 before the first entry). */
    @get:Synchronized
    var lastSeq: Long = 0
        private set

    init {
        if (file != null && Files.exists(file)) load(file)
    }

    @Synchronized
    fun append(entry: JournalEntry): JournalEntry {
        val numbered = entry.copy(seq = lastSeq + 1)
        lastSeq = numbered.seq
        entries.addLast(numbered)
        while (entries.size > capacity) entries.removeFirst()
        write(numbered)
        return numbered
    }

    /** Empty the journal (reset); numbering continues from [lastSeq]. */
    @Synchronized
    fun clear() {
        entries.clear()
        rewrite()
    }

    /** Never let the sequence fall behind a persisted high-water mark. */
    @Synchronized
    fun ensureSeqAtLeast(seq: Long) {
        if (seq > lastSeq) lastSeq = seq
    }

    @Synchronized
    fun oldest(): Long = entries.firstOrNull()?.seq ?: (lastSeq + 1)

    data class Page(val entries: List<JournalEntry>, val next: Long, val oldest: Long)

    /**
     * Entries with `seq > after` (exclusive), optionally of one [kind], at most [limit]. `next` is
     * the value to pass as `after` for the following page: the `seq` of the last entry returned,
     * or [after] itself when nothing was returned. Clients page until a page comes back empty.
     */
    @Synchronized
    fun page(after: Long, limit: Int, kind: String?): Page {
        val out = ArrayList<JournalEntry>(minOf(limit, entries.size))
        for (entry in entries) {
            if (out.size >= limit) break
            if (entry.seq <= after) continue
            if (kind == null || entry.kind == kind) out += entry
        }
        return Page(out, out.lastOrNull()?.seq ?: after, oldest())
    }

    private fun write(entry: JournalEntry) {
        if (file == null) return
        try {
            file.parent?.let { Files.createDirectories(it) }
            Files.write(
                file,
                (json.encodeToString(JournalEntry.serializer(), entry) + "\n").toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE,
            )
            linesInFile += 1
            if (linesInFile > capacity * 2) rewrite()
        } catch (e: Exception) {
            log.warn("Failed to append to the journal {}: {}", file, e.message)
        }
    }

    private fun rewrite() {
        if (file == null) return
        try {
            file.parent?.let { Files.createDirectories(it) }
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            val text = entries.joinToString("") { json.encodeToString(JournalEntry.serializer(), it) + "\n" }
            Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            linesInFile = entries.size
        } catch (e: Exception) {
            log.warn("Failed to rewrite the journal {}: {}", file, e.message)
        }
    }

    private fun load(file: Path) {
        try {
            Files.readAllLines(file, StandardCharsets.UTF_8).forEach { line ->
                if (line.isBlank()) return@forEach
                val entry = runCatching { json.decodeFromString(JournalEntry.serializer(), line) }.getOrNull()
                    ?: return@forEach
                entries.addLast(entry)
                if (entry.seq > lastSeq) lastSeq = entry.seq
                linesInFile += 1
                while (entries.size > capacity) entries.removeFirst()
            }
        } catch (e: Exception) {
            log.warn("Failed to read the journal {}: {}", file, e.message)
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 10_000
        const val MAX_BODY_BYTES = 16 * 1024
        private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

        /** `<dir>/state.json` -> `<dir>/state.journal.jsonl`, so two worlds never share one journal. */
        fun pathFor(statePath: Path): Path {
            val name = statePath.fileName.toString()
            val stem = if (name.endsWith(".json")) name.removeSuffix(".json") else name
            return statePath.resolveSibling("$stem.journal.jsonl")
        }
    }
}

/**
 * Redaction for journal bodies: every secret, token, card number and signature becomes
 * `"[redacted]"`, and a body larger than 16 KiB is cut to a string prefix.
 */
object Redaction {
    const val REDACTED = "[redacted]"
    private val camelBoundary = Regex("([a-z0-9])([A-Z])")
    private val panPattern = Regex("(?<![0-9A-Za-z])[0-9](?:[ -]?[0-9]){12,18}(?![0-9A-Za-z])")
    private val sensitiveExact = setOf(
        "secret", "password", "authorization", "api_key", "number", "cvc", "card_number", "signature",
        "stripe_signature", "token", "client_secret", "webhook_secret", "pan",
    )

    fun isSensitiveKey(key: String): Boolean {
        val normalized = key.replace(camelBoundary, "$1_$2").lowercase()
            .replace('[', '_').replace(']', '_').trim('_')
        val last = normalized.split('_', '.').filter { it.isNotEmpty() }.lastOrNull() ?: normalized
        return normalized in sensitiveExact || last in setOf("number", "cvc", "secret", "password", "token", "signature") ||
            normalized.endsWith("_secret") || normalized.endsWith("_password") || normalized.endsWith("_token") ||
            normalized.contains("card_number")
    }

    fun redact(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) -> if (isSensitiveKey(key)) JsonPrimitive(REDACTED) else redact(value) },
        )
        is JsonArray -> JsonArray(element.map { redact(it) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(redactText(element.content)) else element
        else -> element
    }

    fun redactText(text: String): String = panPattern.replace(text, REDACTED)

    /** Parse a captured body (JSON, form-encoded or text), redact it and bound its size. */
    fun body(text: String?, contentType: String?): JsonElement? {
        if (text.isNullOrEmpty()) return null
        val parsed: JsonElement = when {
            contentType?.contains("json", ignoreCase = true) == true || text.trimStart().startsWith("{") ->
                runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
            contentType?.contains("x-www-form-urlencoded", ignoreCase = true) == true -> formToJson(text)
            else -> JsonPrimitive(text)
        }
        val redacted = redact(parsed)
        val encoded = redacted.toString()
        if (encoded.toByteArray(StandardCharsets.UTF_8).size <= Journal.MAX_BODY_BYTES) return redacted
        val bytes = encoded.toByteArray(StandardCharsets.UTF_8)
        var cut = Journal.MAX_BODY_BYTES
        // Never split a UTF-8 sequence.
        while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
        return JsonPrimitive(String(bytes, 0, cut, StandardCharsets.UTF_8) + "...[truncated]")
    }

    private fun formToJson(text: String): JsonObject = buildJsonObject {
        text.split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val eq = pair.indexOf('=')
            val key = decode(if (eq < 0) pair else pair.substring(0, eq))
            val value = if (eq < 0) "" else decode(pair.substring(eq + 1))
            put(key, value)
        }
    }

    private fun decode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, Charsets.UTF_8.name()) }.getOrDefault(s)
}
