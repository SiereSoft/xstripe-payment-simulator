package com.fakestripe.util

import com.fakestripe.error.StripeException
import io.ktor.http.Parameters

/**
 * Stripe's API takes `application/x-www-form-urlencoded` request bodies — NOT JSON —
 * and encodes structure with bracket notation:
 *
 *   amount=1000&currency=usd&metadata[order_id]=A1&expand[]=customer&card[number]=4242...
 *
 * The official SDKs (stripe-python, stripe-java) send exactly this. To be a drop-in
 * fake, we must decode the same shape. Ktor already URL-decodes into a [Parameters]
 * multimap keeping the literal bracketed keys; this class interprets them.
 */
class StripeParams(private val entries: List<Pair<String, String>>) {

    fun has(key: String): Boolean = entries.any { it.first == key }

    /** The last value wins, mirroring how form bodies collapse duplicate scalar keys. */
    fun opt(key: String): String? = entries.lastOrNull { it.first == key }?.second

    fun require(key: String): String = opt(key) ?: throw StripeException.missingParam(key)

    fun long(key: String): Long? {
        val raw = opt(key) ?: return null
        return raw.toLongOrNull()
            ?: throw StripeException.invalidRequest(
                "Invalid integer: $raw", param = key, code = "parameter_invalid_integer",
            )
    }

    fun bool(key: String): Boolean? = opt(key)?.let { it == "true" || it == "1" }

    /** Values for `key[subkey]`, e.g. metadata[...] or invoice_settings[...]. */
    fun subMap(key: String): Map<String, String> {
        val re = Regex("^${Regex.escape(key)}\\[([^\\]]+)\\]$")
        val out = LinkedHashMap<String, String>()
        for ((k, v) in entries) {
            val m = re.matchEntire(k) ?: continue
            out[m.groupValues[1]] = v
        }
        return out
    }

    /** Values for `key[]` or `key[0]`, `key[1]`, ... preserving order. */
    fun list(key: String): List<String> {
        val re = Regex("^${Regex.escape(key)}\\[(\\d*)\\]$")
        val indexed = ArrayList<Pair<Int, String>>()
        var appendCounter = 1_000_000
        for ((k, v) in entries) {
            val m = re.matchEntire(k) ?: continue
            val idx = m.groupValues[1].toIntOrNull() ?: appendCounter++
            indexed.add(idx to v)
        }
        return indexed.sortedBy { it.first }.map { it.second }
    }

    /**
     * Values for a nested array of maps, e.g. `items[0][price]=..&items[0][quantity]=..`
     * -> a list (index-ordered) of {price:.., quantity:..} maps. Used by subscriptions.
     */
    fun indexedSubMaps(key: String): List<Map<String, String>> {
        val re = Regex("^${Regex.escape(key)}\\[(\\d+)\\]\\[([^\\]]+)\\]$")
        val byIndex = sortedMapOf<Int, MutableMap<String, String>>()
        for ((k, v) in entries) {
            val m = re.matchEntire(k) ?: continue
            val idx = m.groupValues[1].toInt()
            byIndex.getOrPut(idx) { LinkedHashMap() }[m.groupValues[2]] = v
        }
        return byIndex.values.toList()
    }

    val expand: Set<String> get() = list("expand").toSet()

    companion object {
        fun from(parameters: Parameters): StripeParams {
            val list = ArrayList<Pair<String, String>>()
            for (name in parameters.names()) {
                for (value in parameters.getAll(name).orEmpty()) list.add(name to value)
            }
            return StripeParams(list)
        }

        /**
         * Parse a raw urlencoded body string. Used when the body was read earlier
         * (e.g. to fingerprint an idempotent request) and can't be received again.
         */
        fun fromBody(body: String): StripeParams {
            if (body.isBlank()) return StripeParams(emptyList())
            val list = body.split("&").mapNotNull { pair ->
                if (pair.isEmpty()) return@mapNotNull null
                val eq = pair.indexOf('=')
                val (k, v) = if (eq < 0) pair to "" else pair.substring(0, eq) to pair.substring(eq + 1)
                decode(k) to decode(v)
            }
            return StripeParams(list)
        }

        private fun decode(s: String): String =
            java.net.URLDecoder.decode(s, Charsets.UTF_8.name())
    }
}
