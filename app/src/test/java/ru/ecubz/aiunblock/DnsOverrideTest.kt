package ru.ecubz.aiunblock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsOverrideTest {
    @Test
    fun returnsMappedIpv4AddressForAQuery() {
        val response = DnsOverride.response(
            query = query("game.clashroyaleapp.com", type = 1),
            fixedAddressFor = { host ->
                if (host == "game.clashroyaleapp.com") "62.133.62.97" else null
            },
        )!!

        assertEquals(1, response[7].toInt())
        assertArrayEquals(
            byteArrayOf(62, 133.toByte(), 62, 97),
            response.copyOfRange(response.size - 4, response.size),
        )
    }

    @Test
    fun returnsNoDataForMappedIpv6Query() {
        val response = DnsOverride.response(
            query = query("game.clashroyaleapp.com", type = 28),
            fixedAddressFor = { "62.133.62.97" },
        )!!

        assertEquals(0, response[7].toInt())
    }

    @Test
    fun ignoresUnmappedDomain() {
        assertNull(DnsOverride.response(query("example.com", type = 1)) { null })
    }

    private fun query(host: String, type: Int): ByteArray {
        val encodedName = host.split('.').flatMap { label ->
            listOf(label.length.toByte()) + label.toByteArray(Charsets.US_ASCII).toList()
        } + byteArrayOf(0).toList()
        return byteArrayOf(
            0x12,
            0x34,
            0x01,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
        ) + encodedName.toByteArray() + byteArrayOf(
            (type ushr 8).toByte(),
            type.toByte(),
            0x00,
            0x01,
        )
    }
}
