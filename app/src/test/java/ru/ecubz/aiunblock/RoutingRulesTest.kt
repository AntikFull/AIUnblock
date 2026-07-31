package ru.ecubz.aiunblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutingRulesTest {
    @Test
    fun routesGoogleAiDomainsToGoogleGateway() {
        assertEquals(GatewayGroup.GOOGLE_AI, RoutingRules.gatewayFor("gemini.google.com"))
        assertEquals(
            GatewayGroup.GOOGLE_AI,
            RoutingRules.gatewayFor("alkalimakersuite-pa.clients6.google.com"),
        )
        assertEquals(
            GatewayGroup.GOOGLE_AI,
            RoutingRules.gatewayFor("signaler-pa.googleapis.com"),
        )
    }

    @Test
    fun routesNotebookToItsOwnGateway() {
        assertEquals(
            GatewayGroup.NOTEBOOK_LM,
            RoutingRules.gatewayFor("notebooklm-pa.googleapis.com"),
        )
    }

    @Test
    fun routesOpenAiAndClaudeDomainsIndependently() {
        assertEquals(
            GatewayGroup.CHATGPT,
            RoutingRules.gatewayFor("android.chat.openai.com"),
        )
        assertEquals(
            GatewayGroup.CHATGPT,
            RoutingRules.gatewayFor("files.oaiusercontent.com"),
        )
        assertEquals(
            GatewayGroup.CLAUDE,
            RoutingRules.gatewayFor("api.anthropic.com"),
        )
    }

    @Test
    fun leavesUnrelatedGoogleTrafficDirect() {
        assertNull(RoutingRules.gatewayFor("www.google.com"))
        assertNull(RoutingRules.gatewayFor("news.google.com"))
        assertNull(RoutingRules.gatewayFor("subdomain.gemini.google.com"))
        assertNull(RoutingRules.gatewayFor("fake.notebooklm-pa.googleapis.com"))
        assertNull(RoutingRules.gatewayFor("example.com"))
    }

    @Test
    fun doesNotAcceptLookalikeSuffixes() {
        assertNull(RoutingRules.gatewayFor("notopenai.com"))
        assertNull(RoutingRules.gatewayFor("claude.ai.example.com"))
    }
}
