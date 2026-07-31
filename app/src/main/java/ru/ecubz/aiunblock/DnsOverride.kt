package ru.ecubz.aiunblock

import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

object DnsOverride {
    private const val HEADER_SIZE = 12
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val CLASS_IN = 1

    fun response(
        query: ByteArray,
        fixedAddressFor: (String) -> String?,
    ): ByteArray? {
        if (query.size < HEADER_SIZE || readUnsignedShort(query, 4) != 1) return null
        if ((query[2].toInt() and 0x78) != 0) return null

        var offset = HEADER_SIZE
        val labels = mutableListOf<String>()
        while (offset < query.size) {
            val length = query[offset++].toInt() and 0xff
            if (length == 0) break
            if (length > 63 || offset + length > query.size) return null
            labels += query.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
            offset += length
        }
        if (labels.isEmpty() || offset + 4 > query.size) return null

        val queryType = readUnsignedShort(query, offset)
        val queryClass = readUnsignedShort(query, offset + 2)
        val questionEnd = offset + 4
        if (queryClass != CLASS_IN || queryType !in setOf(TYPE_A, TYPE_AAAA)) return null

        val host = labels.joinToString(".").lowercase(Locale.ROOT)
        val address = fixedAddressFor(host)
            ?.let { rawAddress ->
                runCatching { InetAddress.getByName(rawAddress) }.getOrNull() as? Inet4Address
            }
            ?: return null

        val answerSize = if (queryType == TYPE_A) 16 else 0
        return ByteArray(questionEnd + answerSize).also { response ->
            query.copyInto(response, endIndex = questionEnd)
            response[2] = (0x80 or (query[2].toInt() and 0x01)).toByte()
            response[3] = 0x80.toByte()
            response[6] = 0
            response[7] = if (queryType == TYPE_A) 1 else 0
            response[8] = 0
            response[9] = 0
            response[10] = 0
            response[11] = 0

            if (queryType == TYPE_A) {
                var answerOffset = questionEnd
                response[answerOffset++] = 0xc0.toByte()
                response[answerOffset++] = 0x0c
                response[answerOffset++] = 0
                response[answerOffset++] = TYPE_A.toByte()
                response[answerOffset++] = 0
                response[answerOffset++] = CLASS_IN.toByte()
                response[answerOffset++] = 0
                response[answerOffset++] = 0
                response[answerOffset++] = 0
                response[answerOffset++] = 60
                response[answerOffset++] = 0
                response[answerOffset++] = 4
                address.address.copyInto(response, answerOffset)
            }
        }
    }

    private fun readUnsignedShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or
            (data[offset + 1].toInt() and 0xff)
}
