package ru.ecubz.aiunblock

import android.content.Context
import java.util.Locale

sealed interface GeoHideTarget {
    data class FixedAddress(val address: String) : GeoHideTarget
    data object AutomaticGateway : GeoHideTarget
}

class GeoHideRoutingRules private constructor(
    private val supportedDomains: Set<String>,
    private val fixedAddresses: Map<String, String>,
) {
    companion object {
        private const val DOMAINS_ASSET = "geohide_domains.txt"
        private const val HOSTS_ASSET = "geohide_hosts.txt"

        fun load(context: Context): GeoHideRoutingRules {
            val domains = context.assets.open(DOMAINS_ASSET).bufferedReader().useLines { lines ->
                lines.mapNotNull(::normalizeRule).toSet()
            }
            val hosts = context.assets.open(HOSTS_ASSET).bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    if (parts.size != 2 || parts[0] == "0.0.0.0") return@mapNotNull null
                    val domain = normalizeRule(parts[1]) ?: return@mapNotNull null
                    domain to parts[0]
                }.toMap()
            }
            return GeoHideRoutingRules(
                supportedDomains = domains + hosts.keys,
                fixedAddresses = hosts,
            )
        }

        internal fun fromRules(
            supportedDomains: Set<String>,
            fixedAddresses: Map<String, String> = emptyMap(),
        ): GeoHideRoutingRules = GeoHideRoutingRules(
            supportedDomains = supportedDomains.mapNotNull(::normalizeRule).toSet(),
            fixedAddresses = fixedAddresses.mapNotNull { (domain, address) ->
                normalizeRule(domain)?.let { it to address }
            }.toMap(),
        )

        private fun normalizeRule(value: String): String? =
            value.substringBefore('#')
                .trim()
                .trimEnd('.')
                .lowercase(Locale.ROOT)
                .takeIf { it.isNotEmpty() }
    }

    fun targetFor(host: String?): GeoHideTarget? {
        var candidate = host
            ?.trim()
            ?.trimEnd('.')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        while (true) {
            fixedAddresses[candidate]?.let { return GeoHideTarget.FixedAddress(it) }
            if (candidate in supportedDomains) return GeoHideTarget.AutomaticGateway
            val dot = candidate.indexOf('.')
            if (dot < 0) return null
            candidate = candidate.substring(dot + 1)
        }
    }

    fun fixedAddressFor(host: String): String? =
        fixedAddresses[
            host.trim()
                .trimEnd('.')
                .lowercase(Locale.ROOT),
        ]
}
