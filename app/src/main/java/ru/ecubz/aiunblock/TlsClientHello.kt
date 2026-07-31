package ru.ecubz.aiunblock

object TlsClientHello {
    sealed interface Result {
        data object NeedMore : Result
        data object NotTls : Result
        data class Parsed(val serverName: String?) : Result
    }

    private const val TLS_HANDSHAKE = 22
    private const val CLIENT_HELLO = 1
    private const val SERVER_NAME_EXTENSION = 0

    fun parse(bytes: ByteArray): Result {
        if (bytes.size < 5) return Result.NeedMore
        if (u8(bytes, 0) != TLS_HANDSHAKE) return Result.NotTls

        val handshake = ArrayList<Byte>(bytes.size)
        var recordOffset = 0
        while (recordOffset + 5 <= bytes.size) {
            val recordType = u8(bytes, recordOffset)
            if (recordType != TLS_HANDSHAKE) {
                return if (handshake.isEmpty()) Result.NotTls else Result.NeedMore
            }

            val recordLength = u16(bytes, recordOffset + 3)
            val recordEnd = recordOffset + 5 + recordLength
            if (recordEnd > bytes.size) return Result.NeedMore

            for (index in recordOffset + 5 until recordEnd) {
                handshake.add(bytes[index])
            }

            if (handshake.size >= 4) {
                val helloLength =
                    ((handshake[1].toInt() and 0xff) shl 16) or
                        ((handshake[2].toInt() and 0xff) shl 8) or
                        (handshake[3].toInt() and 0xff)
                if (handshake.size >= helloLength + 4) {
                    return parseHandshake(handshake.toByteArray(), helloLength + 4)
                }
            }
            recordOffset = recordEnd
        }
        return Result.NeedMore
    }

    private fun parseHandshake(handshake: ByteArray, limit: Int): Result {
        if (handshake.isEmpty() || u8(handshake, 0) != CLIENT_HELLO) {
            return Result.NotTls
        }

        var offset = 4
        if (offset + 2 + 32 > limit) return Result.NotTls
        offset += 2 + 32

        if (offset + 1 > limit) return Result.NotTls
        val sessionIdLength = u8(handshake, offset)
        offset += 1 + sessionIdLength

        if (offset + 2 > limit) return Result.NotTls
        val cipherSuitesLength = u16(handshake, offset)
        offset += 2 + cipherSuitesLength

        if (offset + 1 > limit) return Result.NotTls
        val compressionLength = u8(handshake, offset)
        offset += 1 + compressionLength

        if (offset == limit) return Result.Parsed(null)
        if (offset + 2 > limit) return Result.NotTls
        val extensionsLength = u16(handshake, offset)
        offset += 2
        val extensionsEnd = offset + extensionsLength
        if (extensionsEnd > limit) return Result.NotTls

        while (offset + 4 <= extensionsEnd) {
            val type = u16(handshake, offset)
            val length = u16(handshake, offset + 2)
            offset += 4
            val extensionEnd = offset + length
            if (extensionEnd > extensionsEnd) return Result.NotTls

            if (type == SERVER_NAME_EXTENSION) {
                return Result.Parsed(parseServerName(handshake, offset, extensionEnd))
            }
            offset = extensionEnd
        }
        return Result.Parsed(null)
    }

    private fun parseServerName(bytes: ByteArray, start: Int, end: Int): String? {
        if (start + 2 > end) return null
        var offset = start + 2
        while (offset + 3 <= end) {
            val nameType = u8(bytes, offset)
            val nameLength = u16(bytes, offset + 1)
            offset += 3
            if (offset + nameLength > end) return null
            if (nameType == 0) {
                return bytes.copyOfRange(offset, offset + nameLength)
                    .toString(Charsets.US_ASCII)
                    .takeIf { it.isNotBlank() }
            }
            offset += nameLength
        }
        return null
    }

    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)
}
