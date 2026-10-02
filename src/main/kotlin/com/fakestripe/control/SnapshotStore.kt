package com.fakestripe.control

import com.fakestripe.store.Snapshot
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Named snapshots (protocol section 8): `<data dir>/snapshots/<name>.json`, written atomically,
 * surviving restarts and resets. Each file is `{"format", "name", "created_at", "clock",
 * "revision", "episode_id", "world"}` where `world` is the persisted world document.
 */
class SnapshotStore(private val dir: Path) {
    data class Meta(
        val name: String,
        val createdAt: String,
        val clock: String,
        val revision: Long,
        val episodeId: String?,
        val format: String,
        val bytes: Long,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("name", name)
            put("created_at", createdAt)
            put("clock", clock)
            put("revision", revision)
            put("episode_id", episodeId)
            put("format", format)
            put("bytes", bytes)
        }
    }

    /** Thrown for a snapshot file that is not a world this simulator can load. */
    class Incompatible(message: String) : RuntimeException(message)

    private fun file(name: String): Path {
        if (!ControlProtocol.NAME_PATTERN.matches(name)) {
            throw ControlException.invalid("Snapshot names must match [A-Za-z0-9._-]{1,64}.", "name")
        }
        return dir.resolve("$name.json")
    }

    fun exists(name: String): Boolean = Files.exists(file(name))

    @Synchronized
    fun save(name: String, world: JsonElement, createdAtMs: Long, clockMs: Long, revision: Long, episodeId: String?): Meta {
        val doc = buildJsonObject {
            put("format", Snapshot.FORMAT)
            put("name", name)
            put("created_at", ControlProtocol.iso(createdAtMs))
            put("clock", ControlProtocol.iso(clockMs))
            put("revision", revision)
            put("episode_id", episodeId)
            put("world", world)
        }
        Snapshot.writeAtomically(file(name), json.encodeToString(JsonElement.serializer(), doc))
        return meta(name) ?: error("snapshot $name vanished after writing")
    }

    @Synchronized
    fun list(): List<Meta> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }
                .map { it.fileName.toString().removeSuffix(".json") }
                .filter { ControlProtocol.NAME_PATTERN.matches(it) }
                .sorted()
                .collect(Collectors.toList())
        }.mapNotNull { meta(it) }
    }

    @Synchronized
    fun meta(name: String): Meta? {
        val path = file(name)
        if (!Files.exists(path)) return null
        val bytes = Files.size(path)
        val doc = runCatching { json.parseToJsonElement(String(Files.readAllBytes(path), StandardCharsets.UTF_8)) as? JsonObject }
            .getOrNull()
        return Meta(
            name = name,
            createdAt = doc?.text("created_at") ?: "",
            clock = doc?.text("clock") ?: "",
            revision = (doc?.get("revision") as? JsonPrimitive)?.longOrNull ?: 0,
            episodeId = doc?.text("episode_id"),
            format = doc?.text("format") ?: "unknown",
            bytes = bytes,
        )
    }

    @Synchronized
    fun delete(name: String): Boolean = Files.deleteIfExists(file(name))

    /** The world document of snapshot [name], or [Incompatible] when it cannot be one of ours. */
    @Synchronized
    fun world(name: String): JsonElement {
        val path = file(name)
        if (!Files.exists(path)) throw ControlException.notFound("No snapshot named '$name'.")
        val doc = runCatching { json.parseToJsonElement(String(Files.readAllBytes(path), StandardCharsets.UTF_8)) }
            .getOrNull() as? JsonObject ?: throw Incompatible("Snapshot '$name' is not a JSON document.")
        val format = doc.text("format")
        if (format != Snapshot.FORMAT) {
            throw Incompatible("Snapshot '$name' has format '${format ?: "none"}'; this simulator reads '${Snapshot.FORMAT}'.")
        }
        return doc["world"] as? JsonObject ?: throw Incompatible("Snapshot '$name' holds no world.")
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        private val json = Json { encodeDefaults = true }
    }
}
