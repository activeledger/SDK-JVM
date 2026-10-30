package io.activeledger.sdk.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Edge cases of the hex codec and the public key shape check. */
class Secp256k1EdgeTest {

    @Test
    fun `fromHex decodes lower and upper case digits to the same bytes`() {
        assertContentEquals(byteArrayOf(0x0a, 0xbc.toByte(), 0xff.toByte()), Secp256k1.fromHex("0x0abcff", "public"))
        assertContentEquals(byteArrayOf(0x0a, 0xbc.toByte(), 0xff.toByte()), Secp256k1.fromHex("0x0ABCFF", "public"))
    }

    @Test
    fun `fromHex of the bare prefix is an empty array`() {
        assertEquals(0, Secp256k1.fromHex("0x", "public").size)
    }

    @Test
    fun `the prefix is case sensitive and must be at the start`() {
        assertFailsWith<IllegalArgumentException> { Secp256k1.fromHex("0Xaa", "public") }
        assertFailsWith<IllegalArgumentException> { Secp256k1.fromHex("aa0x", "public") }
        assertFailsWith<IllegalArgumentException> { Secp256k1.fromHex(" 0xaa", "public") }
        assertFailsWith<IllegalArgumentException> { Secp256k1.fromHex("", "public") }
    }

    @Test
    fun `an odd number of digits is rejected and counted`() {
        val error = assertFailsWith<IllegalArgumentException> { Secp256k1.fromHex("0xabc", "private") }
        assertTrue(error.message!!.contains("odd"), error.message!!)
        assertTrue(error.message!!.contains("(3)"), error.message!!)
    }

    @Test
    fun `error messages name which key was being decoded`() {
        val noPrefix = assertFailsWith<IllegalArgumentException> { Secp256k1.fromHex("aa", "private") }
        assertTrue(noPrefix.message!!.contains("secp256k1 private key"), noPrefix.message!!)
        val badHex = assertFailsWith<IllegalArgumentException> { Secp256k1.fromHex("0xzz", "public") }
        assertTrue(badHex.message!!.contains("secp256k1 public key"), badHex.message!!)
    }

    @Test
    fun `non-hex characters are rejected with the cause attached`() {
        val error = assertFailsWith<IllegalArgumentException> { Secp256k1.fromHex("0xaag0", "public") }
        assertTrue(error.cause is NumberFormatException)
    }

    @Test
    fun `toHex then fromHex round trips every byte value`() {
        val all = ByteArray(256) { it.toByte() }
        val hex = Secp256k1.toHex(all)
        assertTrue(hex.startsWith("0x"))
        assertEquals(2 + 512, hex.length)
        assertEquals(hex, hex.lowercase())
        assertContentEquals(all, Secp256k1.fromHex(hex, "public"))
    }

    @Test
    fun `toHex zero pads single digit bytes`() {
        assertEquals("0x000f10", Secp256k1.toHex(byteArrayOf(0, 0x0f, 0x10)))
        assertEquals("0x", Secp256k1.toHex(ByteArray(0)))
    }

    @Test
    fun `checkPublic accepts both compressed prefixes and the uncompressed one`() {
        Secp256k1.checkPublic(ByteArray(33).also { it[0] = 0x02 })
        Secp256k1.checkPublic(ByteArray(33).also { it[0] = 0x03 })
        Secp256k1.checkPublic(ByteArray(65).also { it[0] = 0x04 })
    }

    @Test
    fun `checkPublic rejects the uncompressed prefix on a compressed length and the reverse`() {
        assertFailsWith<IllegalArgumentException> { Secp256k1.checkPublic(ByteArray(33).also { it[0] = 0x04 }) }
        assertFailsWith<IllegalArgumentException> { Secp256k1.checkPublic(ByteArray(65).also { it[0] = 0x02 }) }
        assertFailsWith<IllegalArgumentException> { Secp256k1.checkPublic(ByteArray(65).also { it[0] = 0x03 }) }
    }

    @Test
    fun `checkPublic rejects an unknown prefix and reports it as hex`() {
        val error = assertFailsWith<IllegalArgumentException> { Secp256k1.checkPublic(ByteArray(33).also { it[0] = 0x05 }) }
        assertTrue(error.message!!.contains("0x05"), error.message!!)
        // 0x00 is not a valid prefix at either length.
        assertFailsWith<IllegalArgumentException> { Secp256k1.checkPublic(ByteArray(33)) }
        assertFailsWith<IllegalArgumentException> { Secp256k1.checkPublic(ByteArray(65)) }
    }

    @Test
    fun `checkPublic treats the prefix byte as unsigned`() {
        // 0xff would be negative as a signed byte; it must still be refused, not wrapped.
        val error = assertFailsWith<IllegalArgumentException> { Secp256k1.checkPublic(ByteArray(33).also { it[0] = 0xff.toByte() }) }
        assertTrue(error.message!!.contains("0xff"), error.message!!)
    }

    @Test
    fun `checkPublic rejects lengths either side of the valid ones`() {
        for (size in listOf(0, 1, 32, 34, 64, 66, 100)) {
            val error = assertFailsWith<IllegalArgumentException>("size $size") {
                Secp256k1.checkPublic(ByteArray(size).also { if (size > 0) it[0] = 0x02 })
            }
            assertTrue(error.message!!.contains("$size bytes"), error.message!!)
        }
    }

    // fromPublic checks shape, not curve membership; verification is where a
    // point that is not on the curve is caught, and it must say false.
    @Test
    fun `a well shaped point that is not on the curve verifies nothing`() {
        val offCurve = "0x04" + "00".repeat(31) + "01" + "00".repeat(31) + "01"
        val key = KeyPair.fromPublic(KeyType.SECP256K1, offCurve)
        val real = KeyPair.generate(KeyType.SECP256K1)
        val message = byteArrayOf(1, 2, 3)
        assertFalse(key.verify(message, real.sign(message)))
    }

    @Test
    fun `fromKeys refuses a private key of the wrong length`() {
        val real = KeyPair.generate(KeyType.SECP256K1)
        val error = assertFailsWith<IllegalArgumentException> {
            KeyPair.fromKeys(KeyType.SECP256K1, real.exportPublic(), "0x" + "11".repeat(31))
        }
        assertTrue(error.message!!.contains("32"), error.message!!)
    }
}
