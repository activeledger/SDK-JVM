package io.activeledger.sdk.json

/**
 * A JSON model and serialiser that reproduces JavaScript's `JSON.stringify`
 * byte for byte.
 *
 * This exists instead of Gson or Jackson because Activeledger signs the exact
 * bytes of `JSON.stringify($tx)` encoded UTF-8 - no hash prefix, no length
 * prefix, no domain separator, and crucially no canonical key ordering. A
 * signature over bytes that differ by one escape is simply invalid, and the
 * ledger reports it as 1220 "Signature Incorrect", which says nothing about
 * serialisation.
 *
 * The four things a general-purpose library gets wrong here - HTML escaping,
 * non-ASCII escaping, key sorting and number formatting - all produce
 * identical output to this one on an ASCII-only integer payload. That is why
 * they are worth owning rather than configuring.
 */
sealed interface JsonValue

/**
 * A JSON object preserving insertion order.
 *
 * Backed by LinkedHashMap deliberately: the ledger does not canonicalise key
 * order, so a signer must reproduce the order the caller built. Anything that
 * sorts - or any plain HashMap - produces a valid-looking document with an
 * invalid signature.
 */
class JsonObject : JsonValue {
    private val entries = LinkedHashMap<String, JsonValue>()

    val keys: Set<String> get() = entries.keys

    fun put(key: String, value: JsonValue): JsonObject = apply { entries[key] = value }
    fun put(key: String, value: String): JsonObject = put(key, JsonString(value))
    fun put(key: String, value: Int): JsonObject = put(key, JsonNumber(value.toDouble()))
    fun put(key: String, value: Long): JsonObject = put(key, JsonNumber(value.toDouble()))
    fun put(key: String, value: Double): JsonObject = put(key, JsonNumber(value))
    fun put(key: String, value: Boolean): JsonObject = put(key, JsonBool(value))
    fun putNull(key: String): JsonObject = put(key, JsonNull)

    operator fun get(key: String): JsonValue? = entries[key]

    fun getObject(key: String): JsonObject =
        entries[key] as? JsonObject ?: throw IllegalArgumentException("$key is not an object")

    fun getString(key: String): String =
        (entries[key] as? JsonString)?.value
            ?: throw IllegalArgumentException("$key is not a string")

    internal fun forEach(action: (String, JsonValue) -> Unit) = entries.forEach(action)
    internal fun isEmpty(): Boolean = entries.isEmpty()
}

class JsonArray : JsonValue {
    private val items = mutableListOf<JsonValue>()

    fun add(value: JsonValue): JsonArray = apply { items.add(value) }
    fun add(value: String): JsonArray = add(JsonString(value))
    fun add(value: Int): JsonArray = add(JsonNumber(value.toDouble()))
    fun add(value: Double): JsonArray = add(JsonNumber(value))
    fun add(value: Boolean): JsonArray = add(JsonBool(value))
    fun addNull(): JsonArray = add(JsonNull)

    internal fun items(): List<JsonValue> = items
}

data class JsonString(val value: String) : JsonValue
data class JsonNumber(val value: Double) : JsonValue
data class JsonBool(val value: Boolean) : JsonValue
data object JsonNull : JsonValue

object CanonicalJson {

    fun stringify(value: JsonValue): String {
        val out = StringBuilder()
        write(value, out)
        return out.toString()
    }

    /** The bytes that actually get signed. */
    fun bytes(value: JsonValue): ByteArray = stringify(value).toByteArray(Charsets.UTF_8)

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonObject -> {
                out.append('{')
                var first = true
                value.forEach { key, child ->
                    if (!first) out.append(',')
                    first = false
                    writeString(key, out)
                    out.append(':')
                    write(child, out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                value.items().forEachIndexed { index, child ->
                    if (index > 0) out.append(',')
                    write(child, out)
                }
                out.append(']')
            }
            is JsonString -> writeString(value.value, out)
            is JsonNumber -> writeNumber(value.value, out)
            is JsonBool -> out.append(if (value.value) "true" else "false")
            is JsonNull -> out.append("null")
        }
    }

    /**
     * JavaScript number formatting.
     *
     * `JSON.stringify(1.0)` is `1`, not `1.0`, because JavaScript has one
     * numeric type and prints the shortest representation that round-trips.
     * A JVM library printing `1.0` produces a different byte string and an
     * invalid signature.
     */
    private fun writeNumber(value: Double, out: StringBuilder) {
        // JSON.stringify emits null for these. Emitting null would silently
        // sign something the caller never wrote, so refuse instead.
        require(!value.isNaN()) { "NaN cannot be serialised - JSON.stringify emits null, which would sign bytes you did not intend" }
        require(!value.isInfinite()) { "Infinity cannot be serialised - JSON.stringify emits null, which would sign bytes you did not intend" }

        if (value == Math.floor(value) && Math.abs(value) < 1e21) {
            // Whole and inside the range JavaScript prints without an
            // exponent. toLong() also normalises -0.0 to 0, matching
            // JSON.stringify(-0) === "0".
            out.append(value.toLong().toString())
        } else {
            // Kotlin's Double.toString gives the shortest round-tripping
            // form, which is the same rule JavaScript uses.
            out.append(value.toString())
        }
    }

    /**
     * String escaping, matching JSON.stringify exactly.
     *
     * Escapes the two characters JSON requires plus the control characters
     * below 0x20, using the short forms where JavaScript has them. Everything
     * else - all non-ASCII - passes through and is emitted as raw UTF-8.
     * Escaping it as \\uXXXX is what makes Python's default output
     * incompatible, and HTML-escaping < > & is what makes Go's default output
     * incompatible.
     */
    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '' -> out.append("\\f")
                else ->
                    if (char < ' ') {
                        out.append("\\u").append(String.format("%04x", char.code))
                    } else {
                        out.append(char)
                    }
            }
        }
        out.append('"')
    }
}
