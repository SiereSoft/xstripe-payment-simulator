package com.fakestripe.store

import com.fakestripe.model.Charge
import com.fakestripe.model.Customer
import com.fakestripe.model.PaymentIntent
import com.fakestripe.model.PaymentMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * On-disk snapshot of the in-memory store. This is what makes the "in-memory +
 * snapshot" persistence choice survive a container restart: after every mutation
 * we serialize the world to JSON, and on startup we load it back.
 *
 * [idCount] records how many IDs the generator has produced, so ID generation
 * resumes at the same point in the deterministic stream after a reload.
 */
@Serializable
private data class StoreSnapshot(
    val seed: Long,
    val idCount: Int,
    val customers: List<Customer>,
    val paymentMethods: List<PaymentMethod>,
    val paymentIntents: List<PaymentIntent>,
    val charges: List<Charge>,
)

object Snapshot {
    private val log = LoggerFactory.getLogger(Snapshot::class.java)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun save(store: DataStore, path: Path) {
        try {
            val snap = StoreSnapshot(
                seed = store.seed,
                idCount = store.ids.count,
                customers = store.customers.values.toList(),
                paymentMethods = store.paymentMethods.values.toList(),
                paymentIntents = store.paymentIntents.values.toList(),
                charges = store.charges.values.toList(),
            )
            path.parent?.let { Files.createDirectories(it) }
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.write(tmp, json.encodeToString(StoreSnapshot.serializer(), snap).toByteArray())
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            log.warn("Failed to write snapshot to {}: {}", path, e.message)
        }
    }

    fun load(path: Path): DataStore? {
        if (!Files.exists(path)) return null
        return try {
            val snap = json.decodeFromString(StoreSnapshot.serializer(), String(Files.readAllBytes(path)))
            DataStore(snap.seed, idCount = snap.idCount).apply {
                snap.customers.forEach { customers[it.id] = it }
                snap.paymentMethods.forEach { paymentMethods[it.id] = it }
                snap.paymentIntents.forEach { paymentIntents[it.id] = it }
                snap.charges.forEach { charges[it.id] = it }
            }
        } catch (e: Exception) {
            log.warn("Failed to load snapshot from {}: {}. Starting fresh.", path, e.message)
            null
        }
    }
}
