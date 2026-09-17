package io.activeledger.sdk.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Signatures are over JSON.stringify($tx) encoded UTF-8 - no hash prefix, no
// length prefix, no domain separator, no key sorting. Every case here is one
// a general-purpose JSON library gets wrong by default, and every one of them
// passes on an ASCII-only integer payload. That is exactly why a port can
// ship broken and only fail on a customer's data.
class CanonicalJsonTest {

    @Test
    fun `preserves insertion order and does not sort`() {
        val o = JsonObject().put("zebra", "1").put("alpha", "2").put("m", "3")
        assertEquals("""{"zebra":"1","alpha":"2","m":"3"}""", CanonicalJson.stringify(o))
    }

    // Go's encoding/json escapes these by default; a carelessly configured
    // Gson or Jackson will too.
    @Test
    fun `does not HTML-escape`() {
        val o = JsonObject().put("expr", "a < b && c > d")
        assertEquals("""{"expr":"a < b && c > d"}""", CanonicalJson.stringify(o))
    }

    // The bug that makes Python's json.dumps incompatible.
    @Test
    fun `emits non-ascii raw, never as escapes`() {
        val multibyte = "café 日本語"
        val o = JsonObject().put("greeting", multibyte)
        assertEquals("{\"greeting\":\"$multibyte\"}", CanonicalJson.stringify(o))
    }

    @Test
    fun `formats numbers the way JavaScript does`() {
        val o = JsonObject().put("whole", 1.0).put("third", 0.1).put("int", 42)
        assertEquals("""{"whole":1,"third":0.1,"int":42}""", CanonicalJson.stringify(o))
    }

    @Test
    fun `escapes only what JSON requires`() {
        val o = JsonObject().put("s", "a\"b\\c")
        assertEquals("""{"s":"a\"b\\c"}""", CanonicalJson.stringify(o))
    }

    @Test
    fun `uses the short control escapes where JavaScript does`() {
        assertEquals("""{"s":"\n\t\r"}""", CanonicalJson.stringify(JsonObject().put("s", "\n\t\r")))
    }

    // Kotlin processes \uXXXX even inside a raw string, so a raw
    // literal would hold the control character itself rather than the six
    // characters being asserted on. Built from an escaped backslash.
    @Test
    fun `escapes other control characters as uXXXX`() {
        val expected = "{\"s\":\"" + "\\u0001" + "\"}"
        val actual = CanonicalJson.stringify(JsonObject().put("s", "\u0001"))
        assertEquals(expected, actual)
    }

    @Test
    fun `handles nesting, arrays, null and booleans`() {
        val o = JsonObject()
            .put("a", JsonArray().add(1).add("two").add(true).addNull())
            .put("b", JsonObject().put("c", false))
        assertEquals("""{"a":[1,"two",true,null],"b":{"c":false}}""", CanonicalJson.stringify(o))
    }

    @Test
    fun `empty object and array`() {
        assertEquals("{}", CanonicalJson.stringify(JsonObject()))
        assertEquals("[]", CanonicalJson.stringify(JsonArray()))
    }

    // The bytes that get signed are the UTF-8 of this string, so the encoding
    // step is part of the contract. An accented e is two bytes, so
    // {"e":"X"} is 9 ASCII characters plus one extra byte.
    @Test
    fun `utf8 bytes match the expected length for multibyte input`() {
        val s = CanonicalJson.stringify(JsonObject().put("e", "é"))
        assertEquals(10, s.toByteArray(Charsets.UTF_8).size)
    }

    // JSON.stringify emits null for these, which would silently sign bytes
    // the caller never intended. Refusing is the honest behaviour.
    @Test
    fun `rejects NaN and infinities`() {
        assertFailsWith<IllegalArgumentException> {
            CanonicalJson.stringify(JsonObject().put("x", Double.NaN))
        }
        assertFailsWith<IllegalArgumentException> {
            CanonicalJson.stringify(JsonObject().put("x", Double.POSITIVE_INFINITY))
        }
    }

    @Test
    fun `negative and fractional numbers`() {
        val o = JsonObject().put("neg", -2.5).put("zero", 0).put("negZeroInt", -0.0)
        assertEquals("""{"neg":-2.5,"zero":0,"negZeroInt":0}""", CanonicalJson.stringify(o))
    }

    @Test
    fun `large whole doubles do not gain exponent notation`() {
        val o = JsonObject().put("big", 1234567890.0)
        assertEquals("""{"big":1234567890}""", CanonicalJson.stringify(o))
    }

    @Test
    fun `keys are escaped the same way as values`() {
        val o = JsonObject().put("a\"b", "v")
        assertEquals("""{"a\"b":"v"}""", CanonicalJson.stringify(o))
    }
}
