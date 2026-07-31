package ru.ecubz.aiunblock

import android.net.Network
import android.net.VpnService
import android.util.Log
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class LocalSocksRelay(
    private val vpnService: VpnService,
    private val activeNetwork: () -> Network?,
    private val gatewayAddress: (GatewayGroup) -> InetAddress,
    private val geoHideRules: GeoHideRoutingRules,
) : Closeable {
    companion object {
        private const val TAG = "AiUnblockRelay"
        private const val SOCKS_VERSION = 5
        private const val USERNAME_PASSWORD_AUTH = 2
        private const val COMMAND_CONNECT = 1
        private const val COMMAND_UDP_ASSOCIATE = 3
        private const val ADDRESS_IPV4 = 1
        private const val ADDRESS_DOMAIN = 3
        private const val ADDRESS_IPV6 = 4
        private const val MAX_CLIENT_HELLO = 64 * 1024
        private const val TLS_SNI_TIMEOUT_MS = 2_500
        private const val CONNECT_TIMEOUT_MS = 8_000
    }

    private val threadId = AtomicInteger()
    private val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "aiunblock-relay-${threadId.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private val sessions = Collections.synchronizedSet(mutableSetOf<Closeable>())
    private val serverSocket = ServerSocket()
    val username = "aiunblock"
    val password: String = ByteArray(24)
        .also(SecureRandom()::nextBytes)
        .joinToString(separator = "") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    val port: Int
        get() = serverSocket.localPort

    fun start() {
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64)
        sessions += serverSocket
        executor.execute(::acceptLoop)
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            try {
                val client = serverSocket.accept()
                client.tcpNoDelay = true
                sessions += client
                executor.execute { handleClient(client) }
            } catch (_: SocketException) {
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()
            negotiate(input, output)
            val request = readRequest(input)

            when (request.command) {
                COMMAND_CONNECT -> handleConnect(client, input, output, request.destination)
                COMMAND_UDP_ASSOCIATE -> handleUdpAssociate(client, input, output)
                else -> writeReply(output, 7, InetSocketAddress("0.0.0.0", 0))
            }
        } catch (error: Exception) {
            logFailure("SOCKS session failed", error)
            closeQuietly(client)
        } finally {
            sessions -= client
        }
    }

    private fun negotiate(input: InputStream, output: OutputStream) {
        if (readByte(input) != SOCKS_VERSION) throw EOFException("Unsupported SOCKS version")
        val methodCount = readByte(input)
        val methods = readExactly(input, methodCount)
        if (methods.none { it.toInt() and 0xff == USERNAME_PASSWORD_AUTH }) {
            output.write(byteArrayOf(5, 0xff.toByte()))
            throw EOFException("No supported authentication method")
        }
        output.write(byteArrayOf(5, USERNAME_PASSWORD_AUTH.toByte()))
        output.flush()

        if (readByte(input) != 1) throw EOFException("Bad authentication version")
        val receivedUsername = readExactly(input, readByte(input))
        val receivedPassword = readExactly(input, readByte(input))
        val valid =
            MessageDigest.isEqual(receivedUsername, username.toByteArray(Charsets.UTF_8)) &&
                MessageDigest.isEqual(receivedPassword, password.toByteArray(Charsets.UTF_8))
        output.write(byteArrayOf(1, if (valid) 0 else 1))
        output.flush()
        if (!valid) throw EOFException("Authentication failed")
    }

    private data class Request(val command: Int, val destination: InetSocketAddress)

    private fun readRequest(input: InputStream): Request {
        if (readByte(input) != SOCKS_VERSION) throw EOFException("Bad request version")
        val command = readByte(input)
        readByte(input)
        val address = readAddress(input, readByte(input))
        val port = (readByte(input) shl 8) or readByte(input)
        return Request(command, InetSocketAddress(address, port))
    }

    private fun handleConnect(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        original: InetSocketAddress,
    ) {
        if (original.port != 443) {
            val upstream = connectProtected(original)
            sessions += upstream
            writeReply(output, 0, upstream.localSocketAddress as InetSocketAddress)
            relayBothWays(client, upstream, null)
            return
        }

        writeReply(output, 0, InetSocketAddress("0.0.0.0", 0))
        client.soTimeout = TLS_SNI_TIMEOUT_MS
        val firstPayload = readClientHello(input)
        client.soTimeout = 0

        val parsed = TlsClientHello.parse(firstPayload)
        val serverName = (parsed as? TlsClientHello.Result.Parsed)?.serverName
        val gatewayGroup = RoutingRules.gatewayFor(serverName)
        val geoHideTarget = if (gatewayGroup == null) {
            geoHideRules.targetFor(serverName)
        } else {
            null
        }
        val target = when {
            gatewayGroup != null ->
                InetSocketAddress(gatewayAddress(gatewayGroup), original.port)

            geoHideTarget is GeoHideTarget.FixedAddress ->
                InetSocketAddress(InetAddress.getByName(geoHideTarget.address), original.port)

            geoHideTarget is GeoHideTarget.AutomaticGateway ->
                InetSocketAddress(gatewayAddress(GatewayGroup.GEOHIDE), original.port)

            else -> directTarget(original, serverName)
        }
        val routeName = gatewayGroup?.name ?: when (geoHideTarget) {
            is GeoHideTarget.FixedAddress -> "GEOHIDE_FIXED"
            GeoHideTarget.AutomaticGateway -> GatewayGroup.GEOHIDE.name
            null -> "DIRECT"
        }
        debug(
                "TCP/443 sni=${serverName ?: "<none>"} " +
                "original=${original.address.hostAddress}:${original.port} " +
                "route=$routeName " +
                "target=${target.address.hostAddress}:${target.port}",
        )

        val upstream = connectProtected(target)
        sessions += upstream
        relayBothWays(client, upstream, firstPayload)
    }

    private fun directTarget(
        original: InetSocketAddress,
        serverName: String?,
    ): InetSocketAddress {
        if (original.address !is Inet6Address || serverName == null) return original

        val ipv4 = try {
            activeNetwork()
                ?.getAllByName(serverName)
                ?.firstOrNull { it is Inet4Address }
        } catch (_: Exception) {
            null
        }
        return ipv4?.let { InetSocketAddress(it, original.port) } ?: original
    }

    private fun readClientHello(input: InputStream): ByteArray {
        val buffer = ByteArray(MAX_CLIENT_HELLO)
        var used = 0
        while (used < buffer.size) {
            val read = try {
                input.read(buffer, used, buffer.size - used)
            } catch (_: SocketTimeoutException) {
                break
            }
            if (read <= 0) break
            used += read
            when (TlsClientHello.parse(buffer.copyOf(used))) {
                TlsClientHello.Result.NeedMore -> Unit
                else -> break
            }
        }
        return buffer.copyOf(used)
    }

    private fun relayBothWays(client: Socket, upstream: Socket, firstPayload: ByteArray?) {
        if (firstPayload != null && firstPayload.isNotEmpty()) {
            upstream.getOutputStream().write(firstPayload)
            upstream.getOutputStream().flush()
        }

        executor.execute {
            try {
                copy(upstream.getInputStream(), client.getOutputStream())
            } catch (error: Exception) {
                logFailure("Upstream-to-client relay failed", error)
            } finally {
                closeQuietly(client)
                closeQuietly(upstream)
                sessions -= upstream
            }
        }

        try {
            copy(client.getInputStream(), upstream.getOutputStream())
        } catch (error: Exception) {
            logFailure("Client-to-upstream relay failed", error)
        } finally {
            closeQuietly(client)
            closeQuietly(upstream)
            sessions -= upstream
        }
    }

    private fun handleUdpAssociate(client: Socket, input: InputStream, output: OutputStream) {
        val relay = DatagramSocket(null)
        relay.reuseAddress = true
        relay.bind(InetSocketAddress(0))
        if (!vpnService.protect(relay)) {
            relay.close()
            writeReply(output, 1, InetSocketAddress("0.0.0.0", 0))
            return
        }
        activeNetwork()?.bindSocket(relay)
        relay.soTimeout = 1_000
        sessions += relay

        writeReply(
            output,
            0,
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), relay.localPort),
        )

        executor.execute {
            try {
                while (input.read() >= 0) Unit
            } catch (_: Exception) {
                Unit
            } finally {
                closeQuietly(relay)
            }
        }

        val buffer = ByteArray(65_535)
        var clientAddress: SocketAddress? = null
        try {
            while (!relay.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    relay.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }

                if (clientAddress == null || packet.socketAddress == clientAddress) {
                    clientAddress = packet.socketAddress
                    val request = parseUdpRequest(packet.data, packet.offset, packet.length) ?: continue
                    if (request.destination.port == 443) continue
                    if (request.destination.port == 53) {
                        val dnsResponse = DnsOverride.response(
                            query = request.payload,
                            fixedAddressFor = geoHideRules::fixedAddressFor,
                        )
                        if (dnsResponse != null) {
                            val wrappedResponse = encodeUdpResponse(
                                request.destination.address,
                                request.destination.port,
                                dnsResponse,
                            )
                            relay.send(
                                DatagramPacket(
                                    wrappedResponse,
                                    wrappedResponse.size,
                                    clientAddress,
                                ),
                            )
                            continue
                        }
                    }
                    val outbound = DatagramPacket(
                        request.payload,
                        request.payload.size,
                        request.destination,
                    )
                    relay.send(outbound)
                } else {
                    val destination = clientAddress
                    val response = encodeUdpResponse(
                        packet.address,
                        packet.port,
                        packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                    )
                    relay.send(DatagramPacket(response, response.size, destination))
                }
            }
        } finally {
            closeQuietly(relay)
            sessions -= relay
            closeQuietly(client)
        }
    }

    private data class UdpRequest(
        val destination: InetSocketAddress,
        val payload: ByteArray,
    )

    private fun parseUdpRequest(data: ByteArray, start: Int, length: Int): UdpRequest? {
        val end = start + length
        if (length < 4 || data[start] != 0.toByte() || data[start + 1] != 0.toByte()) return null
        if (data[start + 2] != 0.toByte()) return null

        var offset = start + 3
        val type = data[offset++].toInt() and 0xff
        val address: InetAddress = when (type) {
            ADDRESS_IPV4 -> {
                if (offset + 4 > end) return null
                InetAddress.getByAddress(data.copyOfRange(offset, offset + 4)).also { offset += 4 }
            }
            ADDRESS_IPV6 -> {
                if (offset + 16 > end) return null
                InetAddress.getByAddress(data.copyOfRange(offset, offset + 16)).also { offset += 16 }
            }
            ADDRESS_DOMAIN -> {
                if (offset >= end) return null
                val domainLength = data[offset++].toInt() and 0xff
                if (offset + domainLength > end) return null
                val domain = data.copyOfRange(offset, offset + domainLength).toString(Charsets.US_ASCII)
                offset += domainLength
                InetAddress.getByName(domain)
            }
            else -> return null
        }
        if (offset + 2 > end) return null
        val port = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
        offset += 2
        return UdpRequest(
            destination = InetSocketAddress(address, port),
            payload = data.copyOfRange(offset, end),
        )
    }

    private fun encodeUdpResponse(address: InetAddress, port: Int, payload: ByteArray): ByteArray {
        val rawAddress = address.address
        val type = if (address is Inet4Address) ADDRESS_IPV4 else ADDRESS_IPV6
        return ByteArray(4 + rawAddress.size + 2 + payload.size).also { result ->
            result[3] = type.toByte()
            rawAddress.copyInto(result, 4)
            val portOffset = 4 + rawAddress.size
            result[portOffset] = (port ushr 8).toByte()
            result[portOffset + 1] = port.toByte()
            payload.copyInto(result, portOffset + 2)
        }
    }

    private fun connectProtected(destination: InetSocketAddress): Socket {
        val socket = Socket()
        val wildcard = if (destination.address is Inet6Address) {
            InetAddress.getByName("::")
        } else {
            InetAddress.getByName("0.0.0.0")
        }
        // java.net.Socket creates its OS file descriptor lazily. VpnService.protect()
        // returns false until that descriptor exists, so bind before protecting it.
        socket.bind(InetSocketAddress(wildcard, 0))
        if (!vpnService.protect(socket)) {
            socket.close()
            throw SocketException("Unable to protect upstream socket")
        }
        activeNetwork()?.bindSocket(socket)
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.connect(destination, CONNECT_TIMEOUT_MS)
        return socket
    }

    private fun readAddress(input: InputStream, type: Int): InetAddress =
        when (type) {
            ADDRESS_IPV4 -> InetAddress.getByAddress(readExactly(input, 4))
            ADDRESS_IPV6 -> InetAddress.getByAddress(readExactly(input, 16))
            ADDRESS_DOMAIN -> {
                val length = readByte(input)
                InetAddress.getByName(readExactly(input, length).toString(Charsets.US_ASCII))
            }
            else -> throw EOFException("Unsupported address type")
        }

    private fun writeReply(output: OutputStream, code: Int, bound: InetSocketAddress) {
        val address = bound.address ?: InetAddress.getByName("0.0.0.0")
        val rawAddress = address.address
        val type = if (address is Inet6Address) ADDRESS_IPV6 else ADDRESS_IPV4
        val reply = ByteArray(4 + rawAddress.size + 2)
        reply[0] = SOCKS_VERSION.toByte()
        reply[1] = code.toByte()
        reply[2] = 0
        reply[3] = type.toByte()
        rawAddress.copyInto(reply, 4)
        val portOffset = 4 + rawAddress.size
        reply[portOffset] = (bound.port ushr 8).toByte()
        reply[portOffset + 1] = bound.port.toByte()
        output.write(reply)
        output.flush()
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray =
        ByteArray(count).also { result ->
            var offset = 0
            while (offset < count) {
                val read = input.read(result, offset, count - offset)
                if (read < 0) throw EOFException()
                offset += read
            }
        }

    private fun readByte(input: InputStream): Int =
        input.read().takeIf { it >= 0 } ?: throw EOFException()

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: Exception) {
            Unit
        }
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private fun logFailure(message: String, error: Exception) {
        if (BuildConfig.DEBUG) Log.w(TAG, "$message: ${error.javaClass.simpleName}: ${error.message}")
    }

    override fun close() {
        synchronized(sessions) {
            sessions.toList().forEach(::closeQuietly)
            sessions.clear()
        }
        executor.shutdownNow()
    }
}
