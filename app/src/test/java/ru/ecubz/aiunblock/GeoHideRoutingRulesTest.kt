package ru.ecubz.aiunblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoHideRoutingRulesTest {
    @Test
    fun routesSupportedDomainAndItsSubdomains() {
        val rules = GeoHideRoutingRules.fromRules(setOf("spotify.com"))

        assertEquals(GeoHideTarget.AutomaticGateway, rules.targetFor("spotify.com"))
        assertEquals(GeoHideTarget.AutomaticGateway, rules.targetFor("api.spotify.com"))
    }

    @Test
    fun fixedAddressHasPriorityOverAutomaticGateway() {
        val rules = GeoHideRoutingRules.fromRules(
            supportedDomains = setOf("openai.com"),
            fixedAddresses = mapOf("api.openai.com" to "45.155.204.190"),
        )

        assertEquals(
            GeoHideTarget.FixedAddress("45.155.204.190"),
            rules.targetFor("api.openai.com"),
        )
        assertEquals(GeoHideTarget.AutomaticGateway, rules.targetFor("chat.openai.com"))
    }

    @Test
    fun leavesUnsupportedAndLookalikeDomainsDirect() {
        val rules = GeoHideRoutingRules.fromRules(setOf("deepl.com"))

        assertNull(rules.targetFor("notdeepl.com"))
        assertNull(rules.targetFor("deepl.com.example.org"))
    }
}
