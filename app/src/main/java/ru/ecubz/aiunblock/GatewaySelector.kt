package ru.ecubz.aiunblock

import android.content.Context
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private val PUBLIC_PROXIES = listOf(
    "62.133.62.97",
    "103.27.157.38",
    "103.27.157.100",
    "45.155.204.190",
    "37.230.192.51",
    "95.182.120.241",
    "95.216.204.218",
    "80.253.249.40",
    "185.246.223.127",
    "87.228.47.204",
)

private val PUBLIC_AI_PROXIES = listOf(
    "87.228.47.204",
    "185.246.223.127",
    "103.27.157.38",
    "103.27.157.100",
    "62.133.62.97",
    "45.155.204.190",
    "37.230.192.51",
    "95.182.120.241",
    "95.216.204.218",
    "80.253.249.40",
)

private val AUTH_DNS = listOf(
    "62.133.62.97",
    "87.228.47.204",
    "80.253.249.40",
    "103.27.157.38",
    "103.27.157.100",
    "95.216.204.218",
    "111.88.96.50",
    "111.88.96.51",
)

private data class GatewaySpec(
    val group: GatewayGroup,
    val preferenceKey: String,
    val defaultAddress: String,
    val checkDomains: List<String>,
    val candidates: List<String>,
)

private val GATEWAY_SPECS = listOf(
    GatewaySpec(
        group = GatewayGroup.GOOGLE_AI,
        preferenceKey = "gateway_google_ai",
        defaultAddress = "62.133.62.97",
        checkDomains = listOf(
            "gemini.google.com",
            "robinfrontend-pa.googleapis.com",
            "proactivebackend-pa.googleapis.com",
        ),
        candidates = PUBLIC_PROXIES,
    ),
    GatewaySpec(
        group = GatewayGroup.NOTEBOOK_LM,
        preferenceKey = "gateway_notebook_lm",
        defaultAddress = "62.133.62.97",
        checkDomains = listOf("notebooklm-pa.googleapis.com"),
        candidates = PUBLIC_PROXIES,
    ),
    GatewaySpec(
        group = GatewayGroup.CHATGPT,
        preferenceKey = "gateway_chatgpt",
        defaultAddress = "87.228.47.204",
        checkDomains = listOf("chatgpt.com"),
        candidates = PUBLIC_AI_PROXIES,
    ),
    GatewaySpec(
        group = GatewayGroup.CLAUDE,
        preferenceKey = "gateway_claude",
        defaultAddress = "87.228.47.204",
        checkDomains = listOf("claude.ai"),
        candidates = PUBLIC_AI_PROXIES,
    ),
    GatewaySpec(
        group = GatewayGroup.GEOHIDE,
        preferenceKey = "gateway_geohide",
        defaultAddress = "45.155.204.190",
        checkDomains = listOf("deepl.com"),
        candidates = PUBLIC_PROXIES,
    ),
)

class GatewaySelector(
    private val context: Context,
    private val vpnService: VpnService,
    private val activeNetwork: () -> Network?,
    private val onSelectionUpdated: (String) -> Unit,
) : Closeable {
    companion object {
        private const val TAG = "AiUnblockGateway"
        private const val CHECK_INTERVAL_MINUTES = 30L
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val DNS_TIMEOUT_MS = 3_000
        private const val PROBE_THREAD_COUNT = 16
    }

    private val closed = AtomicBoolean(false)
    private val schedulerThreadId = AtomicInteger()
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "aiunblock-gateway-${schedulerThreadId.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    private val preferences = context.getSharedPreferences("gateways", Context.MODE_PRIVATE)
    private val selected = AtomicReference(loadSavedGateways())
    private var periodicTask: ScheduledFuture<*>? = null

    fun start() {
        if (closed.get() || periodicTask != null) return
        periodicTask = scheduler.scheduleWithFixedDelay(
            ::refreshSafely,
            0,
            CHECK_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
        Log.i(TAG, "Gateway checks scheduled every $CHECK_INTERVAL_MINUTES minutes")
    }

    fun addressFor(group: GatewayGroup): InetAddress {
        val address = selected.get()[group]
            ?: GATEWAY_SPECS.first { it.group == group }.defaultAddress
        return InetAddress.getByName(address)
    }

    fun summary(): String = summary(selected.get())

    private fun loadSavedGateways(): Map<GatewayGroup, String> =
        GATEWAY_SPECS.associate { spec ->
            val saved = preferences.getString(spec.preferenceKey, null)
            val address = saved?.takeIf(spec.candidates::contains) ?: spec.defaultAddress
            spec.group to address
        }

    private fun refreshSafely() {
        if (closed.get()) return
        val startedAt = System.nanoTime()
        try {
            refresh()
        } catch (error: Exception) {
            Log.w(TAG, "Gateway refresh failed", error)
        } finally {
            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            Log.i(
                TAG,
                "Gateway refresh finished in ${durationMs}ms; next check in " +
                    "$CHECK_INTERVAL_MINUTES minutes",
            )
        }
    }

    private fun refresh() {
        val previous = selected.get()
        val probePool = Executors.newFixedThreadPool(PROBE_THREAD_COUNT) { task ->
            Thread(task, "aiunblock-probe").apply { isDaemon = true }
        }

        try {
            authorizeSmartDns(probePool)

            val currentCandidates = GATEWAY_SPECS.associate { spec ->
                spec.group to listOf(previous.getValue(spec.group))
            }
            val currentResults = probeMatrix(probePool, currentCandidates)
            val failedSpecs = GATEWAY_SPECS.filter { spec ->
                currentResults[spec.group]?.get(previous.getValue(spec.group)) != true
            }

            val alternatives = failedSpecs.associate { spec ->
                val current = previous.getValue(spec.group)
                spec.group to spec.candidates.filterNot { it == current }
            }
            val alternativeResults = probeMatrix(probePool, alternatives)
            val updated = previous.toMutableMap()

            for (spec in GATEWAY_SPECS) {
                val current = previous.getValue(spec.group)
                val currentPassed = currentResults[spec.group]?.get(current) == true
                val replacement = if (currentPassed) {
                    current
                } else {
                    spec.candidates.firstOrNull { candidate ->
                        alternativeResults[spec.group]?.get(candidate) == true
                    }
                }

                if (replacement == null) {
                    Log.w(TAG, "${spec.group}: no working gateway found; keeping $current")
                } else {
                    updated[spec.group] = replacement
                    Log.i(TAG, "${spec.group}: selected $replacement")
                }
            }

            val immutableUpdated = updated.toMap()
            selected.set(immutableUpdated)
            persist(immutableUpdated)
            if (!closed.get()) {
                onSelectionUpdated(summary(immutableUpdated))
            }
        } finally {
            probePool.shutdownNow()
        }
    }

    private fun probeMatrix(
        pool: ExecutorService,
        candidatesByGroup: Map<GatewayGroup, List<String>>,
    ): Map<GatewayGroup, Map<String, Boolean>> {
        data class ProbeKey(
            val group: GatewayGroup,
            val address: String,
            val domain: String,
        )

        val futures = buildMap {
            for (spec in GATEWAY_SPECS) {
                val candidates = candidatesByGroup[spec.group].orEmpty()
                for (candidate in candidates) {
                    for (domain in spec.checkDomains) {
                        val key = ProbeKey(spec.group, candidate, domain)
                        put(key, pool.submit<Boolean> { probe(candidate, domain) })
                    }
                }
            }
        }

        return buildMap {
            for (spec in GATEWAY_SPECS) {
                val groupResults = buildMap {
                    for (candidate in candidatesByGroup[spec.group].orEmpty()) {
                        val passed = spec.checkDomains.all { domain ->
                            val key = ProbeKey(spec.group, candidate, domain)
                            runCatching { futures.getValue(key).get() }.getOrDefault(false)
                        }
                        put(candidate, passed)
                    }
                }
                if (groupResults.isNotEmpty()) {
                    put(spec.group, groupResults)
                }
            }
        }
    }

    private fun probe(address: String, domain: String): Boolean {
        val plainSocket = Socket()
        return try {
            // java.net.Socket creates its OS descriptor lazily. Bind first so
            // VpnService.protect() can exclude the probe from this VPN.
            plainSocket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            if (!vpnService.protect(plainSocket)) {
                Log.w(TAG, "Probe $address for $domain could not protect its socket")
                return false
            }
            activeNetwork()?.bindSocket(plainSocket)
            plainSocket.tcpNoDelay = true
            plainSocket.soTimeout = READ_TIMEOUT_MS
            plainSocket.connect(
                InetSocketAddress(InetAddress.getByName(address), 443),
                CONNECT_TIMEOUT_MS,
            )

            val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plainSocket, domain, 443, true) as SSLSocket
            sslSocket.use { socket ->
                val parameters = socket.sslParameters
                parameters.endpointIdentificationAlgorithm = "HTTPS"
                parameters.serverNames = listOf(SNIHostName(domain))
                if (Build.VERSION.SDK_INT >= 29) {
                    parameters.applicationProtocols = arrayOf("http/1.1")
                }
                socket.sslParameters = parameters
                socket.soTimeout = READ_TIMEOUT_MS
                socket.startHandshake()

                socket.outputStream.write(
                    (
                        "GET / HTTP/1.1\r\n" +
                            "Host: $domain\r\n" +
                            "User-Agent: AIUnblock/${BuildConfig.VERSION_NAME}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII),
                )
                socket.outputStream.flush()
                val statusLine = BufferedReader(
                    InputStreamReader(socket.inputStream, StandardCharsets.US_ASCII),
                ).readLine().orEmpty()
                val status = statusLine
                    .split(' ')
                    .getOrNull(1)
                    ?.toIntOrNull()
                val passed = status != null && status in 200..499
                if (passed) {
                    Log.i(TAG, "Probe $address for $domain passed with HTTP $status")
                } else {
                    Log.w(TAG, "Probe $address for $domain returned '$statusLine'")
                }
                passed
            }
        } catch (error: Exception) {
            Log.w(TAG, "Probe $address for $domain failed: ${error.javaClass.simpleName}")
            false
        } finally {
            runCatching { plainSocket.close() }
        }
    }

    private fun authorizeSmartDns(pool: ExecutorService) {
        val query = dnsQuery("chatgpt.com")
        val futures = AUTH_DNS.map { address ->
            pool.submit<Boolean> {
                val socket = DatagramSocket(null)
                try {
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(0))
                    if (!vpnService.protect(socket)) return@submit false
                    activeNetwork()?.bindSocket(socket)
                    socket.soTimeout = DNS_TIMEOUT_MS
                    socket.send(
                        DatagramPacket(
                            query,
                            query.size,
                            InetSocketAddress(InetAddress.getByName(address), 53),
                        ),
                    )
                    val response = DatagramPacket(ByteArray(4_096), 4_096)
                    socket.receive(response)
                    response.length >= 12
                } catch (_: Exception) {
                    false
                } finally {
                    socket.close()
                }
            }
        }
        val successes = futures.count { future ->
            runCatching { future.get() }.getOrDefault(false)
        }
        Log.i(TAG, "Smart DNS authorization completed: $successes/${AUTH_DNS.size}")
    }

    private fun dnsQuery(domain: String): ByteArray {
        val labels = domain.split('.')
        val size = 12 + labels.sumOf { it.length + 1 } + 1 + 4
        val packet = ByteArray(size)
        val id = System.nanoTime().toInt() and 0xffff
        packet[0] = (id ushr 8).toByte()
        packet[1] = id.toByte()
        packet[2] = 0x01
        packet[5] = 0x01
        var offset = 12
        for (label in labels) {
            packet[offset++] = label.length.toByte()
            val bytes = label.toByteArray(StandardCharsets.US_ASCII)
            bytes.copyInto(packet, offset)
            offset += bytes.size
        }
        packet[offset++] = 0
        packet[offset++] = 0
        packet[offset++] = 1
        packet[offset++] = 0
        packet[offset] = 1
        return packet
    }

    private fun persist(gateways: Map<GatewayGroup, String>) {
        val editor = preferences.edit()
        for (spec in GATEWAY_SPECS) {
            editor.putString(spec.preferenceKey, gateways.getValue(spec.group))
        }
        editor.apply()
    }

    private fun summary(gateways: Map<GatewayGroup, String>): String {
        val google = gateways.getValue(GatewayGroup.GOOGLE_AI)
        val notebook = gateways.getValue(GatewayGroup.NOTEBOOK_LM)
        val chatGpt = gateways.getValue(GatewayGroup.CHATGPT)
        val claude = gateways.getValue(GatewayGroup.CLAUDE)
        val geoHide = gateways.getValue(GatewayGroup.GEOHIDE)
        return buildList {
            if (google == notebook) {
                add("Gemini/NotebookLM · $google")
            } else {
                add("Gemini/Google · $google")
                add("NotebookLM · $notebook")
            }
            if (chatGpt == claude) {
                add("ChatGPT/Claude · $chatGpt")
            } else {
                add("ChatGPT · $chatGpt")
                add("Claude · $claude")
            }
            add("GeoHide · $geoHide")
        }.joinToString("\n")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        periodicTask?.cancel(true)
        periodicTask = null
        scheduler.shutdownNow()
    }
}
