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

    val expand: Set<String> get() = list("expand").toSet()

    companion object {
        fun from(parameters: Parameters): StripeParams {
            val list = ArrayList<Pair<String, String>>()
            for (name in parameters.names()) {
                for (value in parameters.getAll(name).orEmpty()) list.add(name to value)
            }
            return StripeParams(list)
        }
    }
}
