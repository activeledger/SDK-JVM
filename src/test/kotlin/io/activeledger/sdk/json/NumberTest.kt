package io.activeledger.sdk.json

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Canonical number formatting, against the published vectors.
 *
 * What gets signed is JSON.stringify($tx) and the ledger verifies against a
 * re-stringified $tx, so JavaScript's number formatting is the specification.
 * A number written differently produces a signature the ledger rejects as
 * 1220, with nothing in the message about numbers.
 *
 * These exist because the `float` case in pq-vectors.json - 1, 0.1, -2.5, 0 -
 * sits entirely inside the range where every language already agrees.
 */
class NumberTest {

    private val vectors: List<Triple<String, Double, String>> by lazy {
        val json = javaClass.getResourceAsStream("/number-vectors.json")!!.bufferedReader().readText()
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("vectors").map {
            val o = it.asJsonObject
            Triple(o["name"].asString, o["value"].asDouble, o["expected"].asString)
        }
    }

    @Test
    fun `the vector file is not empty`() {
        assertTrue(vectors.isNotEmpty(), "the tests below would pass by not running")
    }

    @Test
    fun `jsNumber matches the reference`() {
        vectors.forEach { (name, value, expected) ->
            assertEquals(expected, CanonicalJson.jsNumber(value), name)
        }
    }

    /** The formatter being right is not enough if the encoder does not call it. */
    @Test
    fun `the encoder uses it`() {
        vectors.forEach { (name, value, expected) ->
            assertEquals(
                """{"n":$expected}""",
                CanonicalJson.stringify(JsonObject().put("n", value)),
                name,
            )
        }
    }

    @Test
    fun `negative zero loses its sign`() {
        assertEquals("0", CanonicalJson.jsNumber(-0.0))
    }

    /** Double.toString gives 1.0E21; JavaScript writes 1e+21. */
    @Test
    fun `exponent form matches javascript`() {
        assertEquals("1e+21", CanonicalJson.jsNumber(1e21))
        assertEquals("1e-7", CanonicalJson.jsNumber(1e-7))
        assertEquals("-1.5e-9", CanonicalJson.jsNumber(-1.5e-9))
    }

    /** Both sides of both boundaries - where implementations part company. */
    @Test
    fun `the plain exponent boundaries`() {
        assertEquals("100000000000000000000", CanonicalJson.jsNumber(1e20))
        assertEquals("1e+21", CanonicalJson.jsNumber(1e21))
        assertEquals("0.000001", CanonicalJson.jsNumber(1e-6))
        assertEquals("1e-7", CanonicalJson.jsNumber(1e-7))
    }

    /** toLong() SATURATES, so 1e19 used to print as Long.MAX_VALUE. */
    @Test
    fun `large whole values no longer saturate to Long MAX_VALUE`() {
        assertEquals("10000000000000000000", CanonicalJson.jsNumber(1e19))
        assertFalse(CanonicalJson.jsNumber(1e20) == "9223372036854775807")
    }

    /**
     * Double.toString is NOT shortest before JDK 19, whatever its reputation.
     * This SDK targets 11, 17 and 21, so it cannot be relied on.
     */
    @Test
    fun `shortest form is found rather than taken from Double toString`() {
        assertEquals("9.999999999999999E22", (1.0E23).toString())
        assertEquals("1e+23", CanonicalJson.jsNumber(1.0E23))
    }

    @Test
    fun `integers beyond two to the 53 take double precision`() {
        // JavaScript has no integer type, so the ledger parses this into a
        // double whatever is sent.
        assertEquals(
            """{"n":9007199254740992}""",
            CanonicalJson.stringify(JsonObject().put("n", 9007199254740993L)),
        )
    }
}
