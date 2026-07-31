package ru.ecubz.aiunblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayOutputStream

class TlsClientHelloTest {
    @Test
    fun parsesServerName() {
        val hello = clientHello("chatgpt.com")
        assertEquals(
            TlsClientHello.Result.Parsed("chatgpt.com"),
            TlsClientHello.parse(hello),
        )
    }

    @Test
    fun waitsForIncompleteRecord() {
        val hello = clientHello("gemini.google.com")
        assertSame(
            TlsClientHello.Result.NeedMore,
            TlsClientHello.parse(hello.copyOf(hello.size - 3)),
        )
    }

    @Test
    fun rejectsPlainHttp() {
        assertSame(
            TlsClientHello.Result.NotTls,
            TlsClientHello.parse("GET / HTTP/1.1\r\n".toByteArray()),
        )
    }

    private fun clientHello(host: String): ByteArray {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val serverNameList = ByteArrayOutputStream().apply {
            writeU16(1 + 2 + hostBytes.size)
            write(0)
            writeU16(hostBytes.size)
            write(hostBytes)
        }.toByteArray()
        val extensions = ByteArrayOutputStream().apply {
            writeU16(0)
            writeU16(serverNameList.size)
            write(serverNameList)
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x03, 0x03))
            write(ByteArray(32))
            write(0)
            writeU16(2)
            write(byteArrayOf(0x13, 0x01))
            write(1)
            write(0)
            writeU16(extensions.size)
            write(extensions)
        }.toByteArray()
        val handshake = ByteArrayOutputStream().apply {
            write(1)
            writeU24(body.size)
            write(body)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(22)
            write(byteArrayOf(0x03, 0x01))
            writeU16(handshake.size)
            write(handshake)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.writeU24(value: Int) {
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }
}

